package IgrisClient

import (
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"strings"
	"time"

	"github.com/xtaci/smux"
)

type IgrisClient struct {
	BufferSize       int64
	HostPayload      string
	Payload          string
	RemoteHost       string
	RemotePort       int64
	StatusProxy      bool
	LocalProxySocket net.Listener
}

func (ic *IgrisClient) Read(c *net.Conn) (string, error) {
	payload := make([]byte, 2048)
	n, err := (*c).Read(payload)

	if err != nil {
		log.Println("ERRO AO LER PAYLOAD DE RESPOSTA:", err)
	}

	return string(payload[:n]), err
}

func (ic *IgrisClient) Client_handler(client *net.Conn, session *smux.Session) int {
	stream, err := session.OpenStream()
	if err != nil {
		fmt.Println("Erro ao abrir stream:", err)
		session.Close()
		(*client).Close()
		return 0
	}

	// log.Println("[!] Session Exists:", !session.IsClosed())

	go func(s *smux.Stream) {
		// log.Println(" | Stream ID: ", s.ID())
		defer s.Close()

		// Fazendo o streaming de dados
		buff_1 := make([]byte, ic.BufferSize)
		buff_2 := make([]byte, ic.BufferSize)

		go io.CopyBuffer(*client, s, buff_1)
		io.CopyBuffer(s, *client, buff_2)
	}(stream)

	return 1
}

func (ic *IgrisClient) AbrirProxyLocal(ws *net.Conn, local_port int) {
	client, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", local_port))
	if err != nil {
		log.Println("Erro ao abrir porta: " + err.Error())
		(*ws).Close()
	}

	ic.StatusProxy = true
	ic.LocalProxySocket = client

	log.Println("Abriu o proxy local")

	defer (*ws).Close()
	defer client.Close()

	session, err := smux.Client(*ws, nil)
	if err != nil {
		panic(err)
	}

	// Listener aqui
	sair := false
	for !sair && ic.StatusProxy {
		client_socket, err := client.Accept()
		if err != nil {
			log.Println("Erro ao aceitar nova conexao: " + err.Error())
			ic.StatusProxy = false
			break
		}

		// log.Println("New Connection:", client_socket.RemoteAddr())

		// abrir um go-routine aqui
		go func() {
			saida := ic.Client_handler(&client_socket, session)
			if saida == 0 {
				client.Close()
				sair = true
			}
		}()
	}
}

func (ic *IgrisClient) Connect2ServidorVPN() (*net.Conn, error) {
	log.Println("Conectando...")
	conn, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", ic.RemoteHost, ic.RemotePort), 30*time.Second)
	if err != nil {
		return nil, err
	}

	ic.Payload = strings.Replace(ic.Payload, "[crlf]", "\r\n", -1)
	ic.Payload = strings.Replace(ic.Payload, "[lf]", "\n", -1)
	ic.Payload = strings.Replace(ic.Payload, "[cr]", "\r", -1)
	ic.Payload = strings.Replace(ic.Payload, "[host]", ic.HostPayload, -1)

	list_payloads := strings.Split(ic.Payload, "[split]")
	for i := 0; i < len(list_payloads); i++ {
		conn.Write([]byte(list_payloads[i]))
	}

	var payload string = ""
	for {
		payload, err = ic.Read(&conn)
		if err != nil {
			return &conn, err
		} else if strings.Contains(payload, "HTTP/1.1 4") || strings.Contains(payload, "HTTP/1.1 5") {
			// deu algum erro no servidor
			return &conn, errors.New(strings.Split(payload, "\n")[0])
		} else if strings.Contains(payload, "HTTP/1.1 200") {
			// resposta do cloudflare
			continue
		} else if strings.Contains(payload, "HTTP/1.1 101") {
			log.Println(strings.Split(payload, "\n")[0])
			break
		}
	}

	// log.Println(payload+"\n\nLen: ", len([]byte(payload)))

	log.Println("Handshake feito com sucesso")

	return &conn, nil
}

func (ic *IgrisClient) Close() {
	ic.StatusProxy = false
	if ic.LocalProxySocket != nil {
		ic.LocalProxySocket.Close()
	}
}
