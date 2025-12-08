import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class IgrisClient {

    private static final Logger LOG = Logger.getLogger(IgrisClient.class.getName());

    // Configurações
    private final String remoteHost;
    private final int remotePort;
    private final String rawPayload;
    private final String hostPayload;
    private final int bufferSize = 16384; // 16KB (Sincronizado com server.go bufferSize)
    private final int typeConnection;
    private final String SNI;

    // Estado da Conexão
    private Socket vpnSocket;
    private OutputStream vpnOutput;
    private InputStream vpnInput;
    private ServerSocket localServerSocket;
    
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    // Gerenciamento SMUX
    private final Map<Integer, Socket> streamMap = new ConcurrentHashMap<>();
    // Inicia em 1 e incrementa de 2 em 2 (Client-side streams são ímpares no protocolo smux padrão)
    private final AtomicInteger streamIdCounter = new AtomicInteger(1);

    // Constantes do Protocolo SMUX (xtaci/smux)
    private static final int SMUX_CMD_SYN = 0; // Abrir Stream
    private static final int SMUX_CMD_FIN = 1; // Fechar Stream
    private static final int SMUX_CMD_PSH = 2; // Dados (Push)
    private static final int SMUX_CMD_NOP = 3; // Keep-alive
    private static final int HEADER_SIZE = 8;
    private static final byte VERSION = 1;

    public IgrisClient(String remoteHost, int remotePort, String payload, String hostPayload, int typeConnection, String SNI) {
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.rawPayload = payload;
        this.hostPayload = hostPayload;
        this.typeConnection = typeConnection; // 0 - http | 1 - ssl
        this.SNI = SNI != null ? SNI : "";
    }

    public boolean connect() {
        LOG.log(Level.INFO, "Iniciando conexão com " + remoteHost + ":" + remotePort);
        try {
            // Configuração do Socket Base
            Socket rawSocket = new Socket();
            rawSocket.setSoTimeout(0); // Timeout infinito (controlado pelo keep-alive)
            rawSocket.setTcpNoDelay(true); // Importante para latência no túnel

            // Estabelecer conexão TCP ou SSL
            if (this.typeConnection == 1) {
                // Conexão SSL/TLS
                rawSocket.connect(new InetSocketAddress(remoteHost, remotePort), 10000);
                
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket sslSocket = (SSLSocket) factory.createSocket(rawSocket, remoteHost, remotePort, true);
                
                // Configuração SNI (Server Name Indication) - Crucial para servidores modernos
                if (this.SNI != null && !this.SNI.isEmpty()) {
                    SSLParameters params = sslSocket.getSSLParameters();
                    try {
                        params.setServerNames(Collections.singletonList(new SNIHostName(this.SNI)));
                    } catch (Exception e) {
                         // Ignora se for IP inválido para SNI, mas tenta configurar
                    }
                    sslSocket.setSSLParameters(params);
                }
                
                sslSocket.startHandshake();
                vpnSocket = sslSocket;
            } else {
                // Conexão HTTP Pura (TCP)
                rawSocket.connect(new InetSocketAddress(remoteHost, remotePort), 10000);
                vpnSocket = rawSocket;
            }

            // Streams globais (usando Buffered para performance, mas com cuidado no handshake)
            vpnOutput = new BufferedOutputStream(vpnSocket.getOutputStream(), bufferSize);
            vpnInput = new BufferedInputStream(vpnSocket.getInputStream(), bufferSize);

            // Preparar Payload
            String payloadToSend = rawPayload
                    .replace("[crlf]", "\r\n")
                    .replace("[lf]", "\n")
                    .replace("[cr]", "\r")
                    .replace("[host]", hostPayload);
            
            // Suporte a split (fragmentação intencional)
            String[] parts = payloadToSend.split("\\[split\\]");
            for (String part : parts) {
                if (!part.isEmpty()) {
                    vpnOutput.write(part.getBytes(StandardCharsets.UTF_8));
                    vpnOutput.flush();
                }
            }

            // --- Handshake Leitura (Sincronizado com server.go) ---
            // O server.go envia os headers e depois IMEDIATAMENTE inicia o smux.
            // Não podemos ler um buffer grande demais e "engolir" o início do smux.
            // Lemos byte a byte até encontrar \r\n\r\n
            
            ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
            int b;
            int matches = 0; // conta sequência \r\n\r\n
            
            // Loop de leitura segura do Header
            while ((b = vpnInput.read()) != -1) {
                headerBuffer.write(b);
                
                // Detecção simples de \r\n\r\n
                if (b == '\n' || b == '\r') {
                    // Check pattern
                    byte[] curr = headerBuffer.toByteArray();
                    if (curr.length >= 4) {
                        if (curr[curr.length-1] == '\n' && 
                            curr[curr.length-2] == '\r' && 
                            curr[curr.length-3] == '\n' && 
                            curr[curr.length-4] == '\r') {
                            break; // Fim dos headers
                        }
                    }
                }
            }

            String responseHeader = headerBuffer.toString("UTF-8");
            
            if (responseHeader.contains("HTTP/1.1 101") || responseHeader.contains("Switching Protocols")) {
                LOG.log(Level.INFO, "✅ Handshake Sucesso: 101 Switching Protocols");
                return true;
            } else {
                LOG.log(Level.SEVERE, "❌ Erro do servidor (Resposta inesperada):\n" + responseHeader);
                return false;
            }

        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Falha na conexão VPN: " + e.getMessage());
            return false;
        }
    }

    public void startLocalProxy(int localPort) {
        if (vpnSocket == null || vpnSocket.isClosed()) return;

        isRunning.set(true);

        // 1. Thread de Leitura do Túnel (Smux Reader)
        new Thread(this::smuxReaderLoop, "Smux-Reader").start();
        
        // 2. Thread de Keep-Alive (Necessário para server.go sessionKeepAlive = 10s)
        new Thread(this::keepAliveLoop, "Smux-KeepAlive").start();

        // 3. Thread Principal do Proxy Local (Aceita conexões do navegador/sistema)
        new Thread(() -> {
            try {
                localServerSocket = new ServerSocket(localPort);
                LOG.log(Level.INFO, "🚀 Proxy Socks/HTTP Local rodando em 127.0.0.1:" + localPort);

                while (isRunning.get()) {
                    Socket clientSocket = localServerSocket.accept();
                    clientSocket.setSoTimeout(0);
                    clientSocket.setTcpNoDelay(true);

                    // Cria um novo Stream ID (SIDs ímpares para o cliente)
                    int sid = streamIdCounter.getAndAdd(2);
                    streamMap.put(sid, clientSocket);

                    // Avisa o servidor Go para criar a ponte para o SOCKS remoto
                    sendSmuxFrame(SMUX_CMD_SYN, sid, null, 0);

                    // Inicia thread para ler do navegador e enviar para o túnel
                    new Thread(() -> handleLocalClient(clientSocket, sid)).start();
                }
            } catch (IOException e) {
                if (isRunning.get()) LOG.log(Level.WARNING, "Erro no LocalServer: " + e.getMessage());
            }
        }, "Local-Listener").start();
    }

    /**
     * Envia "NOP" (Ping) periodicamente para o servidor não fechar a conexão.
     * O server.go tem timeouts definidos, isso mantém o túnel vivo.
     */
    private void keepAliveLoop() {
        while (isRunning.get()) {
            try {
                Thread.sleep(10000); // 10 Segundos
                sendSmuxFrame(SMUX_CMD_NOP, 0, null, 0);
            } catch (InterruptedException e) {
                break;
            } catch (IOException e) {
                // Se der erro de IO ao enviar ping, a conexão provavelmente caiu
                LOG.log(Level.WARNING, "Falha no KeepAlive, encerrando: " + e.getMessage());
                close(); 
                break;
            }
        }
    }

    private void handleLocalClient(Socket localSocket, int sid) {
        try {
            InputStream in = localSocket.getInputStream();
            byte[] buffer = new byte[bufferSize];
            int len;

            // Lê do navegador -> Envia empacotado (SMUX) para o servidor
            while ((len = in.read(buffer)) != -1) {
                sendSmuxFrame(SMUX_CMD_PSH, sid, buffer, len);
            }
        } catch (Exception e) {
            // Conexão local fechada ou erro
        } finally {
            closeStream(sid);
        }
    }

    private void smuxReaderLoop() {
        try {
            // DataInputStream para facilitar a leitura de inteiros/shorts
            DataInputStream dis = new DataInputStream(vpnInput);
            byte[] headerBuffer = new byte[HEADER_SIZE];

            while (isRunning.get()) {
                // Lê o cabeçalho fixo de 8 bytes
                dis.readFully(headerBuffer);

                // Decodifica Little Endian (Padrão Go/Smux)
                ByteBuffer bb = ByteBuffer.wrap(headerBuffer).order(ByteOrder.LITTLE_ENDIAN);
                byte ver = bb.get();
                byte cmd = bb.get();
                int length = Short.toUnsignedInt(bb.getShort()); // Unsigned Short
                int sid = bb.getInt();

                if (ver != VERSION) {
                    throw new IOException("Versão Smux inválida: " + ver);
                }

                // Lê o corpo da mensagem (payload)
                byte[] payload = new byte[length];
                if (length > 0) {
                    dis.readFully(payload);
                }

                // Processa o comando
                try {
                    Socket localSocket = streamMap.get(sid);

                    switch (cmd) {
                        case SMUX_CMD_PSH: // Dados chegando do servidor
                            if (localSocket != null && !localSocket.isClosed()) {
                                localSocket.getOutputStream().write(payload);
                                localSocket.getOutputStream().flush();
                            }
                            break;

                        case SMUX_CMD_FIN: // Servidor fechou o stream
                            closeLocalSocket(sid);
                            break;

                        case SMUX_CMD_NOP: // Keep-alive do servidor
                            // Apenas ignoramos, serve para manter o TCP ativo
                            break;

                        case SMUX_CMD_SYN: // Servidor solicitando stream (incomum neste setup)
                            break;
                    }
                } catch (IOException e) {
                    // Erro ao escrever no socket local, fecha o stream
                    closeStream(sid);
                }
            }
        } catch (IOException e) {
            if (isRunning.get()) {
                LOG.log(Level.INFO, "Conexão VPN encerrada pelo servidor: " + e.getMessage());
                close();
            }
        }
    }

    // Sincronizado para evitar corrupção de pacotes no túnel único
    private synchronized void sendSmuxFrame(int cmd, int sid, byte[] data, int len) throws IOException {
        if (vpnOutput == null) return;

        // Monta Header
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        header.put(VERSION);
        header.put((byte) cmd);
        header.putShort((short) len);
        header.putInt(sid);

        // Escreve Header
        vpnOutput.write(header.array());

        // Escreve Dados (se houver)
        if (len > 0 && data != null) {
            vpnOutput.write(data, 0, len);
        }
        vpnOutput.flush();
    }

    private void closeStream(int sid) {
        try {
            // Envia FIN para o servidor liberar recursos
            sendSmuxFrame(SMUX_CMD_FIN, sid, null, 0);
        } catch (IOException e) {
            // Ignora erro no fechamento
        }
        closeLocalSocket(sid);
    }

    private void closeLocalSocket(int sid) {
        Socket s = streamMap.remove(sid);
        if (s != null) {
            try {
                s.close();
            } catch (IOException e) {
            }
        }
    }

    public void close() {
        if (!isRunning.getAndSet(false)) return; // Já fechado

        try {
            if (localServerSocket != null) localServerSocket.close();
        } catch (IOException e) {}

        try {
            if (vpnSocket != null) vpnSocket.close();
        } catch (IOException e) {}

        streamMap.values().forEach(s -> {
            try { s.close(); } catch (IOException e) {}
        });
        streamMap.clear();
        LOG.log(Level.INFO, "IgrisClient Parado.");
        
        // Mata o processo se estiver rodando via Main (opcional, bom para debug)
        System.exit(0);
    }
}