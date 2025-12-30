public class IgrisMainCli {

    // Configurações do seu servidor IGRIS
    private static final String REMOTE_IP = "br02.kaihovpn.site";
    private static final int REMOTE_PORT = 80;                   
    private static final String HOST_PAYLOAD = "saldo.unitel.ao";
    private static final int LOCAL_PORT = 8991;
    private static final String SNI = "";
    private static final int typeConnection = 0; // 0 - http | 1 - ssl
    
    private static final int MAX_FAILURES = 999; // Aumentado para persistência real
    private static final long RECONNECT_DELAY_MS = 5000;

    private static String PAYLOAD = "GET / HTTP/1.1[crlf]Host: [host][crlf]Connection: Upgrade[crlf]User-Agent: IgrisClient 1.0[crlf]Upgrade: websocket[crlf]user: sung[crlf]password: 123.456[crlf][crlf]";

    public static void main(String[] args) {
        System.out.println("--- Igris Client Standalone Otimizado ---");
        System.out.println("Servidor: " + REMOTE_IP + ":" + REMOTE_PORT);
        
        int consecutiveFailures = 0;

        while (consecutiveFailures < MAX_FAILURES) {
            
            System.out.println("\n🌐 [Main] Tentando conectar... (Tentativa: " + (consecutiveFailures + 1) + ")");
            
            IgrisClientCli client = new IgrisClientCli(REMOTE_IP, REMOTE_PORT, PAYLOAD, HOST_PAYLOAD, typeConnection, SNI);
            
            try {
                if (client.connect()) {
                    consecutiveFailures = 0;
                    System.out.println("✅ [Main] Conexão estabelecida. Monitorando...");
                    client.startLocalProxy(LOCAL_PORT);
            
                    // Loop de monitoramento
                    while (client.isRunning()) {
                        try {
                            Thread.sleep(5000); 
                            // Verifica explicitamente se a conexão ainda está viva
                            if (!client.isRunning()) break;
                            System.out.print("."); // Batimento visual
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    
                    System.out.println("\n⚠️ [Main] Queda de conexão detectada.");
                } else {
                    System.err.println("❌ [Main] Falha ao conectar.");
                    consecutiveFailures++;
                }
            } catch (Exception e) {
                System.err.println("💥 [Main] Erro Crítico: " + e.getMessage());
                consecutiveFailures++;
            } finally {
                if (client != null) {
                    client.close();
                }
                try {
                    System.out.println("⏳ [Main] Aguardando " + (RECONNECT_DELAY_MS/1000) + "s para reconectar...");
                    Thread.sleep(RECONNECT_DELAY_MS);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }
}