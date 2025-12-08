public class IgrisMain {

    // Configurações do seu servidor IGRIS
    private static final String REMOTE_IP = "signup.ao"; // ⬅️ TROQUE ESTE IP PELO IP DO SEU SERVIDOR!
    private static final int REMOTE_PORT = 80;                   
    private static final String HOST_PAYLOAD = "br01.kaihovpn.site";  // ⬅️ TROQUE ESTE HOST (SNI)
    private static final int LOCAL_PORT = 8991;                  // Porta do proxy local (127.0.0.1:8999)
    private static final String SNI = "ao.goodinternet.org";
    private static final int typeConnection = 0; // 0 - http | 1 - ssl
    
    // Payload (fiel ao igris-client.go)
    private static final String PAYLOAD = 
            "GET / HTTP/1.1[crlf]Host: [host][crlf]" +
            "Upgrade: websocket[crlf]Connection: Keep-Alive[crlf]" +
            "User-Agent: Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36[crlf]" +
            "user: sung[crlf]password: 123.456[crlf][crlf]";


    public static void main(String[] args) {
        System.out.println("--- Igris Client Standalone ---");
        System.out.println("Servidor: " + REMOTE_IP + ":" + REMOTE_PORT);
        System.out.println("Proxy Local: 127.0.0.1:" + LOCAL_PORT);

        IgrisClient client = new IgrisClient(REMOTE_IP, REMOTE_PORT, PAYLOAD, HOST_PAYLOAD, typeConnection, SNI);
        
        try {
            if (client.connect()) {
                System.out.println("\n✅ Conexão VPN estabelecida com sucesso.");
                client.startLocalProxy(LOCAL_PORT);
                
                System.out.println("\n📡 Proxy em 127.0.0.1:" + LOCAL_PORT + " está ativo.");
                System.out.println("Configure seu navegador ou sistema para usar este proxy.");
                System.out.println("Pressione Ctrl+C para parar o cliente.");
                
                // Mantém a thread principal viva
                Thread.currentThread().join(); 

            } else {
                System.err.println("\n❌ Falha ao conectar. Verifique logs e configurações.");
            }
        } catch (Exception e) {
            System.err.println("Erro Crítico na Execução: " + e.getMessage());
        } finally {
            client.close();
        }
    }
}


