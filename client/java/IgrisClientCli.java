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
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class IgrisClientCli {

    private static final Logger LOG = Logger.getLogger(IgrisClientCli.class.getName());

    private final String remoteHost;
    private final int remotePort;
    private final String rawPayload;
    private final String hostPayload;
    private final int bufferSize = 16384;
    private final int typeConnection;
    private final String SNI;

    private Socket vpnSocket;
    private OutputStream vpnOutput;
    private InputStream vpnInput;
    private ServerSocket localServerSocket;
    
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final Map<Integer, Socket> streamMap = new ConcurrentHashMap<>();
    private final AtomicInteger streamIdCounter = new AtomicInteger(1);

    // SMUX Constants
    private static final int SMUX_CMD_SYN = 0;
    private static final int SMUX_CMD_FIN = 1;
    private static final int SMUX_CMD_PSH = 2;
    private static final int SMUX_CMD_NOP = 3;
    private static final int HEADER_SIZE = 8;
    private static final byte VERSION = 1;

    public IgrisClientCli(String remoteHost, int remotePort, String payload, String hostPayload, int typeConnection, String SNI) {
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.rawPayload = payload;
        this.hostPayload = hostPayload;
        this.typeConnection = typeConnection;
        this.SNI = SNI != null ? SNI : "";
    }

    public boolean connect() {
        LOG.log(Level.INFO, "Iniciando conexão com " + remoteHost + ":" + remotePort);
        try {
            Socket rawSocket = new Socket();
            rawSocket.setSoTimeout(0);
            rawSocket.setTcpNoDelay(true);

            if (this.typeConnection == 1) {
                rawSocket.connect(new InetSocketAddress(remoteHost, remotePort), 10000);
                
                TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                        public X509Certificate[] getAcceptedIssuers() { return null; }
                    }
                 };

                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, trustAllCerts, new SecureRandom());
                SSLSocketFactory factory = sslContext.getSocketFactory();
                SSLSocket sslSocket = (SSLSocket) factory.createSocket(rawSocket, remoteHost, remotePort, true);
                
                if (this.SNI != null && !this.SNI.isEmpty()) {
                    SSLParameters params = sslSocket.getSSLParameters();
                    try {
                        params.setServerNames(Collections.singletonList(new SNIHostName(this.SNI)));
                        sslSocket.setSSLParameters(params);
                    } catch (Exception e) {}
                }
                sslSocket.startHandshake();
                vpnSocket = sslSocket;
            } else {
                rawSocket.connect(new InetSocketAddress(remoteHost, remotePort), 10000);
                vpnSocket = rawSocket;
            }

            vpnOutput = new BufferedOutputStream(vpnSocket.getOutputStream(), bufferSize);
            vpnInput = new BufferedInputStream(vpnSocket.getInputStream(), bufferSize);

            String payloadToSend = rawPayload
                    .replace("[crlf]", "\r\n")
                    .replace("[lf]", "\n")
                    .replace("[cr]", "\r")
                    .replace("[host]", hostPayload);
            
            String[] parts = payloadToSend.split("\\[split\\]");
            for (String part : parts) {
                if (!part.isEmpty()) {
                    vpnOutput.write(part.getBytes(StandardCharsets.UTF_8));
                    vpnOutput.flush();
                }
            }

            // Handshake Simplificado
            ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
            int b;
            int count = 0;
            while ((b = vpnInput.read()) != -1) {
                headerBuffer.write(b);
                if (b == '\n') { // Check simples de fim de linha
                     String partial = headerBuffer.toString("UTF-8");
                     if (partial.contains("\r\n\r\n")) break;
                }
            }

            String responseHeader = headerBuffer.toString("UTF-8");
            
            if (responseHeader.contains("101 Switching Protocols") || responseHeader.contains("200 Connection established")) {
                LOG.log(Level.INFO, "✅ Handshake Sucesso!");
                return true;
            } else {
                LOG.log(Level.SEVERE, "❌ Resposta inesperada: " + responseHeader.substring(0, Math.min(50, responseHeader.length())));
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

        // Configura Threads como DAEMON para não impedir o fechamento do app
        Thread tReader = new Thread(this::smuxReaderLoop, "Smux-Reader");
        tReader.setDaemon(true);
        tReader.start();
        
        Thread tKeep = new Thread(this::keepAliveLoop, "Smux-KeepAlive");
        tKeep.setDaemon(true);
        tKeep.start();

        Thread tListener = new Thread(() -> {
            try {
                localServerSocket = new ServerSocket(localPort);
                LOG.log(Level.INFO, "🚀 Proxy Socks/HTTP Local rodando em 127.0.0.1:" + localPort);

                while (isRunning.get()) {
                    Socket clientSocket = localServerSocket.accept();
                    clientSocket.setSoTimeout(0);
                    clientSocket.setTcpNoDelay(true);

                    int sid = streamIdCounter.getAndAdd(2);
                    streamMap.put(sid, clientSocket);

                    sendSmuxFrame(SMUX_CMD_SYN, sid, null, 0);
                    
                    Thread tClient = new Thread(() -> handleLocalClient(clientSocket, sid));
                    tClient.setDaemon(true);
                    tClient.start();
                }
            } catch (IOException e) {
                if (isRunning.get()) LOG.log(Level.WARNING, "Erro no LocalServer: " + e.getMessage());
            }
        }, "Local-Listener");
        tListener.setDaemon(true); // Importante!
        tListener.start();
    }

    private void keepAliveLoop() {
        while (isRunning.get()) {
            try {
                Thread.sleep(8000); // 8 segundos (menor que timeout do server)
                if (!isRunning.get()) break;
                sendSmuxFrame(SMUX_CMD_NOP, 0, null, 0);
            } catch (InterruptedException e) {
                break;
            } catch (IOException e) {
                // Se der erro aqui, é porque o socket já caiu.
                // Não chamamos close() aqui para evitar conflito com o Reader, 
                // apenas saímos do loop.
                if (isRunning.get()) {
                     LOG.log(Level.WARNING, "KeepAlive falhou (Conexão perdida).");
                     close();
                }
                break;
            }
        }
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

                if (ver != VERSION) throw new IOException("Versão Smux inválida");

                byte[] payload = new byte[length];
                if (length > 0) dis.readFully(payload);

                try {
                    Socket localSocket = streamMap.get(sid);
                    switch (cmd) {
                        case SMUX_CMD_PSH:
                            if (localSocket != null) {
                                localSocket.getOutputStream().write(payload);
                                localSocket.getOutputStream().flush();
                            }
                            break;
                        case SMUX_CMD_FIN:
                            closeLocalSocket(sid);
                            break;
                        case SMUX_CMD_NOP:
                            break;
                    }
                } catch (IOException e) {
                    closeStream(sid);
                }
            }
        } catch (IOException e) {
            if (isRunning.get()) {
                LOG.log(Level.INFO, "Conexão VPN encerrada (IO Error): " + e.getMessage());
                close(); // Isso notifica a Main
            }
        }
    }

    private synchronized void sendSmuxFrame(int cmd, int sid, byte[] data, int len) throws IOException {
        if (vpnOutput == null) throw new IOException("VPN Output Closed");
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        header.put(VERSION);
        header.put((byte) cmd);
        header.putShort((short) len);
        header.putInt(sid);
        vpnOutput.write(header.array());
        if (len > 0 && data != null) vpnOutput.write(data, 0, len);
        vpnOutput.flush();
    }

    private void closeStream(int sid) {
        try { sendSmuxFrame(SMUX_CMD_FIN, sid, null, 0); } catch (Exception e) {}
        closeLocalSocket(sid);
    }

    private void closeLocalSocket(int sid) {
        Socket s = streamMap.remove(sid);
        if (s != null) try { s.close(); } catch (IOException e) {}
    }

    public void close() {
        if (!isRunning.getAndSet(false)) return; 
    
        LOG.log(Level.INFO, "Iniciando encerramento do IgrisClient...");
    
        // Fecha Listener Local
        try { if (localServerSocket != null) localServerSocket.close(); } catch (Exception e) {}
    
        // Fecha VPN Socket (Isso força as threads de leitura/escrita a pararem com Exception)
        try { if (vpnSocket != null) vpnSocket.close(); } catch (Exception e) {}
    
        // Limpa streams
        streamMap.forEach((sid, socket) -> { try { socket.close(); } catch (Exception e) {} });
        streamMap.clear();
    
        LOG.log(Level.INFO, "IgrisClient recursos liberados.");
    }

    public boolean isRunning() {
        return isRunning.get();
    }
}