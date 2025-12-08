import java.util.logging.Logger;

// import android.os.Build;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import java.util.logging.Level;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
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

public class IgrisClient {

    private static final Logger LOG = Logger.getLogger(IgrisClient.class.getName());

    // Configurações
    private final String remoteHost;
    private final int remotePort;
    private final String rawPayload;
    private final String hostPayload;
    private final int bufferSize = 16384;
    private final int typeConnection;
    private final String SNI;

    // Estado da Conexão
    private Socket vpnSocket;
    private OutputStream vpnOutput;
    private InputStream vpnInput;
    private ServerSocket localServerSocket;
    public static SSLSocket vpnSocketSSL; // que vai pelo ssh mas com ssl

    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    // Gerenciamento SMUX
    // Mapa para associar StreamID -> Socket Local (Navegador/App)
    private final Map<Integer, Socket> streamMap = new ConcurrentHashMap<>();
    private final AtomicInteger streamIdCounter = new AtomicInteger(1);

    // Constantes do Protocolo SMUX (xtaci/smux)
    private static final int SMUX_CMD_SYN = 0; // Abrir Stream
    private static final int SMUX_CMD_FIN = 1; // Fechar Stream
    private static final int SMUX_CMD_PSH = 2; // Dados (Push)
    private static final int SMUX_CMD_NOP = 3; // Keep-alive
    private static final int HEADER_SIZE = 8;
    private static final byte VERSION = 1;

    public IgrisClient(String remoteHost, int remotePort, String payload, String hostPayload, int typeConnection,
            String SNI) {
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.rawPayload = payload;
        this.hostPayload = hostPayload;

        // typeConnection: 0 - http no ssl | 1 - http with ssl(tls/proxy)
        this.typeConnection = typeConnection;
        this.SNI = SNI != null ? SNI : "";
    }

    public boolean connect() {
        LOG.log(Level.INFO, "Iniciando conexão com " + remoteHost + ":" + remotePort);
        try {
            vpnSocket = new Socket();
            vpnSocket.setSoTimeout(0);
            // vpnSocket.setTcpNoDelay(true);

            // vsk.protect(vpnSocket);

            // formatar a payload
            String payloadToSend = rawPayload
                    .replace("[crlf]", "\r\n")
                    .replace("[lf]", "\n")
                    .replace("[cr]", "\r")
                    .replace("[host]", hostPayload);

            String[] parts = payloadToSend.split("\\[split\\]");

            if (this.typeConnection == 0) {
                // conexao http direta
                vpnSocket.connect(new InetSocketAddress(remoteHost, remotePort));

                // vpnOutput = vpnSocket.getOutputStream();
                // vpnInput = vpnSocket.getInputStream();

                vpnOutput = new BufferedOutputStream(vpnSocket.getOutputStream(), bufferSize);
                vpnInput = new BufferedInputStream(vpnSocket.getInputStream(), bufferSize);

                for (String part : parts) {
                    if (!part.isEmpty()) {
                        vpnOutput.write(part.getBytes(StandardCharsets.UTF_8));
                        vpnOutput.flush();
                    }
                }

                byte[] buffer = new byte[1024];
                int len;
                String responseBuffer = "";

                while ((len = vpnInput.read(buffer)) != -1) {
                    String chunk = new String(buffer, 0, len, StandardCharsets.UTF_8);
                    responseBuffer += chunk;

                    if (responseBuffer.contains("HTTP/1.1 200")) {
                        responseBuffer = "";
                    } else if (responseBuffer.contains("HTTP/1.1 101")) {
                        LOG.log(Level.INFO, "Handshake Sucesso: 101 Switching Protocols");
                        return true;
                    } else if (responseBuffer.contains("HTTP/1.1 4") || responseBuffer.contains("HTTP/1.1 5")) {
                        LOG.log(Level.INFO, "Erro do servidor: " + responseBuffer);
                        return false;
                    }
                }
            } else if (this.typeConnection == 1 && this.SNI != null & !this.SNI.isEmpty()) {
                // conexao http com ssl
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                vpnSocketSSL = (SSLSocket) factory.createSocket(vpnSocket, remoteHost, remotePort, true);

                // vsk.protect(vpnSocketSSL);

                // Desativar verificação do hostname e definir SNI
                SSLParameters sslParams = vpnSocketSSL.getSSLParameters();
                // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                //     sslParams.setEndpointIdentificationAlgorithm(""); // Desativar check_hostname
                //     sslParams.setServerNames(Collections.singletonList(new SNIHostName(this.SNI))); // Passar SNI
                // }

                vpnSocketSSL.setSSLParameters(sslParams);

                vpnSocketSSL.startHandshake();

                // vpnOutput = vpnSocket.getOutputStream();
                // vpnInput = vpnSocket.getInputStream();

                vpnOutput = new BufferedOutputStream(vpnSocket.getOutputStream(), bufferSize);
                vpnInput = new BufferedInputStream(vpnSocket.getInputStream(), bufferSize);

                for (String part : parts) {
                    if (!part.isEmpty()) {
                        vpnOutput.write(part.getBytes(StandardCharsets.UTF_8));
                        vpnOutput.flush();
                    }
                }

                byte[] buffer = new byte[1024];
                int len;
                String responseBuffer = "";

                while ((len = vpnInput.read(buffer)) != -1) {
                    String chunk = new String(buffer, 0, len, StandardCharsets.UTF_8);
                    responseBuffer += chunk;

                    if (responseBuffer.contains("HTTP/1.1 200")) {
                        responseBuffer = "";
                    } else if (responseBuffer.contains("HTTP/1.1 101")) {
                        LOG.log(Level.INFO, "Handshake Sucesso: 101 Switching Protocols");
                        return true;
                    } else if (responseBuffer.contains("HTTP/1.1 4") || responseBuffer.contains("HTTP/1.1 5")) {
                        LOG.log(Level.INFO, "Erro do servidor: " + responseBuffer);
                        return false;
                    }
                }
            } else {
                // desconhecido, deve dar um erro qualquer
                return false;
            }
        } catch (Exception e) {
            LOG.log(Level.INFO, "Falha na conexão VPN: " + e.getMessage());
        }
        return false;
    }

    public void startLocalProxy(int localPort) {
        if (vpnSocket == null || vpnSocket.isClosed())
            return;

        isRunning.set(true);

        new Thread(this::smuxReaderLoop, "Smux-Reader").start();

        new Thread(() -> {
            try {
                localServerSocket = new ServerSocket(localPort);
                LOG.log(Level.INFO, "Proxy Socks/HTTP Local rodando na porta " + localPort);

                while (isRunning.get()) {
                    Socket clientSocket = localServerSocket.accept();

                    int sid = streamIdCounter.getAndAdd(2);
                    streamMap.put(sid, clientSocket);

                    sendSmuxFrame(SMUX_CMD_SYN, sid, null, 0);

                    new Thread(() -> handleLocalClient(clientSocket, sid)).start();
                }
            } catch (IOException e) {
                if (isRunning.get())
                    LOG.log(Level.INFO, "Erro no LocalServer: " + e.getMessage());
            }
        }, "Local-Listener").start();
    }

    private void handleLocalClient(Socket localSocket, int sid) {
        try {
            InputStream in = localSocket.getInputStream();
            byte[] buffer = new byte[bufferSize];
            int len;

            while ((len = in.read(buffer)) != -1) {
                sendSmuxFrame(SMUX_CMD_PSH, sid, buffer, len);
            }
        } catch (Exception e) {
            // Conexão caiu
        } finally {
            closeStream(sid);
        }
    }

    private void smuxReaderLoop() {
        try {
            DataInputStream dis = new DataInputStream(vpnInput);
            byte[] headerBuffer = new byte[HEADER_SIZE];

            while (isRunning.get()) {
                dis.readFully(headerBuffer);

                ByteBuffer bb = ByteBuffer.wrap(headerBuffer).order(ByteOrder.LITTLE_ENDIAN);
                byte ver = bb.get();
                byte cmd = bb.get();
                int length = Short.toUnsignedInt(bb.getShort());
                int sid = bb.getInt();

                if (ver != VERSION) {
                    throw new IOException("Versão Smux inválida: " + ver);
                }

                byte[] payload = new byte[length];
                if (length > 0) {
                    dis.readFully(payload);
                }

                Socket localSocket = streamMap.get(sid);

                switch (cmd) {
                    case SMUX_CMD_PSH:
                        if (localSocket != null && !localSocket.isClosed()) {
                            localSocket.getOutputStream().write(payload);
                            localSocket.getOutputStream().flush();
                        }
                        break;

                    case SMUX_CMD_FIN:
                        closeLocalSocket(sid);
                        break;

                    case SMUX_CMD_NOP:
                        break;

                    case SMUX_CMD_SYN:
                        break;
                }
            }
        } catch (IOException e) {
            LOG.log(Level.INFO, "Conexão VPN encerrada ou erro de protocolo: " + e.getMessage());
            close();
        }
    }

    private synchronized void sendSmuxFrame(int cmd, int sid, byte[] data, int len) throws IOException {
        if (vpnOutput == null)
            return;

        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        header.put(VERSION);
        header.put((byte) cmd);
        header.putShort((short) len);
        header.putInt(sid);

        vpnOutput.write(header.array());

        if (len > 0 && data != null) {
            vpnOutput.write(data, 0, len);
        }
        vpnOutput.flush();
    }

    private void closeStream(int sid) {
        try {
            sendSmuxFrame(SMUX_CMD_FIN, sid, null, 0);
        } catch (IOException e) {
            // Ignora
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
        isRunning.set(false);
        try {
            if (localServerSocket != null)
                localServerSocket.close();
        } catch (IOException e) {
        }
        try {
            if (vpnSocket != null)
                vpnSocket.close();
        } catch (IOException e) {
        }
        try {
            if (vpnSocketSSL != null)
            vpnSocketSSL.close();
        } catch (IOException e) {
        }
        streamMap.clear();
        LOG.log(Level.INFO, "IgrisClient Parado.");
    }
}