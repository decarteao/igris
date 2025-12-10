public class IgrisMain {

    // Configurações do seu servidor IGRIS
    private static final String REMOTE_IP = "botvision.com.br"; // ⬅️ TROQUE ESTE IP PELO IP DO SEU SERVIDOR!
    private static final int REMOTE_PORT = 80;                   
    private static final String HOST_PAYLOAD = "botvision.com.br";  // ⬅️ TROQUE ESTE HOST (SNI)
    private static final int LOCAL_PORT = 8991;                  // Porta do proxy local (127.0.0.1:8999)
    private static final String SNI = "botvision.com.br";
    private static final int typeConnection = 0; // 0 - http | 1 - ssl
    
    // Limite de falhas
    private static final int MAX_FAILURES = 5;
    private static final long RECONNECT_DELAY_MS = 5000; // 5 segundos

    // Payload (fiel ao igris-client.go)
    private static String PAYLOAD1 = 
            "GET / HTTP/1.1[crlf]Host: [host][crlf]" +
            "Upgrade: websocket[crlf]Connection: Keep-Alive[crlf]" +
            "User-Agent: Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36[crlf]" +
            "user: sung[crlf]password: 123.456[crlf][crlf]";
    
    private static String PAYLOAD = "GET /cdn-cgi/trace HTTP/1.1[crlf]Host: ib.bancobai.ao[crlf][crlf]GET-RAY / HTTP/1.1[crlf]Host: [host][crlf]Connection: Upgrade[crlf]User-Agent: IgrisClient 1.0[crlf]Upgrade: websocket[crlf]user: sung[crlf]password: 123.456[crlf][crlf]";


    public static void main(String[] args) {
        System.out.println("--- Igris Client Standalone com Persistência ---");
        System.out.println("Servidor: " + REMOTE_IP + ":" + REMOTE_PORT);
        System.out.println("Proxy Local: 127.0.0.1:" + LOCAL_PORT);
        System.out.println("Limite de Falhas: " + MAX_FAILURES);

        int consecutiveFailures = 0;

        // Loop principal para garantir a persistência e reconexão
        while (consecutiveFailures < MAX_FAILURES) {
            
            System.out.println("\n🌐 Tentando conectar... (Falhas Consecutivas: " + consecutiveFailures + "/" + MAX_FAILURES + ")");
            
            IgrisClient client = new IgrisClient(REMOTE_IP, REMOTE_PORT, PAYLOAD, HOST_PAYLOAD, typeConnection, SNI);
            
            try {
                // 1. Tenta estabelecer a conexão VPN
                if (client.connect()) {
                    consecutiveFailures = 0; // Sucesso! Zera o contador de falhas.
                    
                    System.out.println("✅ Conexão VPN estabelecida com sucesso.");
                    client.startLocalProxy(LOCAL_PORT);
                    
                    System.out.println("\n📡 Proxy em 127.0.0.1:" + LOCAL_PORT + " está ativo.");
                    System.out.println("Configure seu navegador ou sistema para usar este proxy.");
                    System.out.println("Aguardando falha de IO para reconectar...");
                    
                    // Mantém a thread principal viva indefinidamente.
                    // O controle de encerramento/reconexão será feito quando client.close()
                    // for chamado internamente (em SmuxReaderLoop ou KeepAliveLoop).
                    Thread.currentThread().join(); 

                } else {
                    // 2. Falha na conexão inicial
                    System.err.println("❌ Falha ao conectar. Tentando novamente em " + (RECONNECT_DELAY_MS / 1000) + "s.");
                    consecutiveFailures++;
                    
                    // Pequena pausa para evitar loop rápido
                    Thread.sleep(RECONNECT_DELAY_MS);
                }
            } catch (InterruptedException e) {
                // Interrupção no sleep ou join (Ctrl+C)
                System.out.println("\nProcesso interrompido pelo usuário.");
                Thread.currentThread().interrupt();
                break; // Sai do loop de reconexão
            } catch (Exception e) {
                // Outro erro crítico
                System.err.println("Erro Crítico na Execução: " + e.getMessage());
                consecutiveFailures++;
                
                try {
                    Thread.sleep(RECONNECT_DELAY_MS);
                } catch (InterruptedException ignored) {}
            } finally {
                // Garante que o cliente anterior seja fechado, liberando recursos (sockets/ports)
                client.close(); 
            }
        }
        
        // Se sair do loop 'while' (consecutiveFailures >= MAX_FAILURES)
        if (consecutiveFailures >= MAX_FAILURES) {
             System.err.println("🛑 Limite de " + MAX_FAILURES + " falhas de conexão atingido. Encerrando processo.");
             System.exit(1); // Encerra com código de erro
        }
        
        System.exit(0); // Encerra normalmente
    }
}
