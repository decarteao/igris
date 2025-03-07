package main

import (
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"github.com/things-go/go-socks5"
	"github.com/xtaci/smux"
)

var current_connections int64 = 0 // Contador de conexões ativas
var max_connections int64 = 1000

const (
	listen_ip         = "0.0.0.0"
	listen_port       = 80
	listen_ip_socks   = "127.0.0.1"
	listen_port_socks = 8999
	buffer_size       = 16384
	timeout_secs      = 30

	// autenticacao
	user     = "sung"
	password = "123.456"
)

func handle_session(session *smux.Session) {
	if session != nil {
		defer session.Close()
	}

	for {
		stream, err := session.AcceptStream()
		if err != nil {
			log.Println("Erro ao aceitar stream: ", err)
			if err == io.EOF {
				break // Se for um erro crítico, sai do loop
			}
		}

		// iniciar o stream
		go func(s *smux.Stream) {
			if s == nil {
				// log.Println("Stream inválido, ignorando...")
				return
			}

			// log.Println("[!] Novo ID Stream:", s.ID())

			// iniciar o socks
			socks, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", listen_ip_socks, listen_port_socks), timeout_secs*time.Second)
			if err != nil {
				// log.Println("Erro ao abrir socks: ", err)
				socks.Close()
				s.Close()
				return
			}

			// defer s.Close()
			if socks != nil {
				defer socks.Close()
			}

			// iniciado com sucesso
			buff_1 := make([]byte, buffer_size)
			buff_2 := make([]byte, buffer_size)
			go io.CopyBuffer(socks, s, buff_1)
			io.CopyBuffer(s, socks, buff_2)
		}(stream)
	}
}

func create_bridge(conn *net.Conn) {
	// executar por ultimo na funcao
	defer log.Println("[!] Conexão fechada:", (*conn).RemoteAddr())
	defer (*conn).Close()
	defer atomic.AddInt64(&current_connections, -1)

	// adicionar cliente na conexao
	atomic.AddInt64(&current_connections, 1)

	// iniciar multiplexacao
	session, err := smux.Server(*conn, nil)
	if err != nil {
		length := fmt.Sprintf("%d", len([]byte(err.Error())))
		(*conn).Write([]byte("HTTP/1.1 403 Nn criou o streaming :(\r\nServer: KaihoVPN\r\nMime-Version: 1.0\r\nContent-Type: text/html\r\nContent-Length: " + length + "\r\n\r\n" + err.Error()))
		(*conn).Close()
		return
	}

	// abrir go-routine para thread
	log.Println("[!] Iniciando Stream:", (*conn).RemoteAddr())
	handle_session(session)
}

func client_handler(conn net.Conn) {
	if atomic.LoadInt64(&current_connections) >= max_connections {
		conn.Write([]byte("HTTP/1.1 403 Servidor lotado\r\nServer: KaihoVPN\r\nMime-Version: 1.0\r\nContent-Type: text/html\r\n\r\n"))
		conn.Close()
		return
	}

	// ler payload
	payload := make([]byte, 512)
	conn.Read(payload)

	// log.Println(string(payload))

	if strings.Contains(string(payload), "GET /users HTTP/1.1") {
		// ver o total de users
		status := fmt.Sprintf(`{"data": "%d/%d"}`, atomic.LoadInt64(&current_connections), max_connections)
		conn.Write([]byte("HTTP/1.1 200 OK\r\nServer: KaihoVPN\r\nContent-Type: application/json\r\nContent-Length: " + fmt.Sprintf("%d", len([]byte(status))) + "\r\n\r\n" + status))
		conn.Close()
		return
	} else if !strings.Contains(strings.ToLower(string(payload)), "upgrade: websocket") {
		// validar payload para evitar spammers
		conn.Write([]byte("HTTP/1.1 403 Payload invalida\r\nServer: KaihoVPN\r\nMime-Version: 1.0\r\nContent-Type: text/html\r\n\r\nPayload Invalida :)"))
		conn.Close()
		return
	} else if !strings.Contains(strings.ToLower(string(payload)), fmt.Sprintf("\r\nuser: %s\r\n", user)) || !strings.Contains(strings.ToLower(string(payload)), fmt.Sprintf("\r\npassword: %s\r\n", password)) {
		// autenticar pela payload
		conn.Write([]byte("HTTP/1.1 403 Credenciais incorrectas\r\nServer: KaihoVPN\r\nMime-Version: 1.0\r\nContent-Type: text/html\r\n\r\nPayload Invalida :)"))
		conn.Close()
		return
	}

	// mandar o handshake
	conn.Write([]byte("HTTP/1.1 101 Ergam-se :)\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n"))

	// iniciar o fluxo
	log.Println("\n{!} Nova conexão autenticada:", conn.RemoteAddr())
	create_bridge(&conn)
}

func main() {
	server := socks5.NewServer(
		socks5.WithLogger(socks5.NewLogger(log.New(os.Stdout, "socks5: ", log.LstdFlags))),
	)

	go func() {
		// servidor proxy
		log.Printf("[!] SOCKS Server iniciado na porta %d\n", listen_port_socks)
		if err := server.ListenAndServe("tcp", fmt.Sprintf("%s:%d", listen_ip_socks, listen_port_socks)); err != nil {
			panic(err)
		}
	}()

	if len(os.Args) > 1 {
		val, err := strconv.ParseInt(os.Args[1], 10, 64)
		if err == nil {
			max_connections = val
		}
	}

	// servidor principal
	ln, err := net.Listen("tcp", fmt.Sprintf("%s:%d", listen_ip, listen_port))
	if err != nil {
		log.Fatal(err)
	}

	log.Printf("[!] Sung WebSocket iniciado na porta %d\n", listen_port)
	log.Printf("[!] MaxConnections: %d\n", max_connections)

	// listener
	for {
		conn, err := ln.Accept()
		if err != nil {
			log.Println("Erro ao aceitar conexao:", err)
			continue
		}

		// abrir uma go-routine, para melhor performance
		go client_handler(conn)
	}
}

