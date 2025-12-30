package main

import (
	"bufio"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"strings"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/armon/go-socks5"
	"github.com/xtaci/smux"
)

var (
	currentConnections int64 = 0
	maxConnections     int64 = 5000
)

const (
	listenIP         = "0.0.0.0"
	listenPort       = 80
	listenIPSocks    = "127.0.0.1"
	listenPortSocks  = 8999
	bufferSize       = 16 * 1024
	readHeaderLimit  = 8 * 1024
	connDialTimeout  = 10 * time.Second
	streamIdleTO     = 60 * time.Second
	sessionKeepAlive = 20 * time.Second
)

var logger = log.New(os.Stdout, "[!] ", log.LstdFlags)
var socksServer *socks5.Server

func main() {
	go startSocks()
	startServer()
}

func startSocks() {
	var err error
	// Parâmetros: addr, ip, user, pass, tcpTimeout, udpTimeout
	socksServer, err = socks5.New(&socks5.Config{})
	if err != nil {
		panic(err)
	}
}

func startServer() {
	listener, err := net.Listen("tcp", fmt.Sprintf("%s:%d", listenIP, listenPort))
	if err != nil {
		logger.Fatalf("Erro ao iniciar servidor: %v", err)
	}

	logger.Printf("IgrisServer na porta %d", listenPort)

	go func() {
		sigChan := make(chan os.Signal, 1)
		signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
		<-sigChan
		os.Exit(0)
	}()

	for {
		conn, err := listener.Accept()
		if err != nil {
			continue
		}

		go handleConnection(conn)
	}
}

func handleConnection(conn net.Conn) {
	conn.SetReadDeadline(time.Now().Add(10 * time.Second))

	// Usamos um buffer para ler o cabeçalho sem perder os bytes para o SMUX
	bufReader := bufio.NewReaderSize(conn, readHeaderLimit)
	head, _ := bufReader.Peek(1024)
	headerStr := string(head)

	conn.SetReadDeadline(time.Time{})

	// --- NOVA ROTA /USERS ---
	if strings.Contains(headerStr, "GET /users") {
		handleUsersStatus(conn)
		return
	}

	// Verifica limite de conexões apenas para o Túnel VPN
	if atomic.LoadInt64(&currentConnections) >= maxConnections {
		conn.Write([]byte("HTTP/1.1 503 Service Unavailable\r\n\r\nServer Full"))
		conn.Close()
		return
	}

	// Handshake do Túnel
	if strings.Contains(headerStr, "Upgrade:") || strings.Contains(headerStr, "HTTP/1.1") {
		conn.Write([]byte("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n"))
	}

	smuxConfig := smux.DefaultConfig()
	smuxConfig.Version = 1

	smuxConfig.MaxReceiveBuffer = 4194304
	smuxConfig.MaxStreamBuffer = 1048576

	smuxConfig.KeepAliveInterval = 20 * time.Second
	smuxConfig.KeepAliveTimeout = 60 * time.Second

	session, err := smux.Server(conn, smuxConfig)
	if err != nil {
		conn.Close()
		return
	}
	defer session.Close()

	atomic.AddInt64(&currentConnections, 1)
	defer atomic.AddInt64(&currentConnections, -1)

	for {
		stream, err := session.AcceptStream()
		if err != nil {
			return
		}
		go handleStream(stream)
	}
}

// Função para exibir o status
func handleUsersStatus(conn net.Conn) {
	defer conn.Close()

	online := atomic.LoadInt64(&currentConnections)

	body := fmt.Sprintf(`{"data": "%d/%d"}`, online, maxConnections)

	response := fmt.Sprintf("HTTP/1.1 200 OK\r\n"+
		"Content-Type: text/plain\r\n"+
		"Content-Length: %d\r\n"+
		"Connection: close\r\n"+
		"\r\n%s", len(body), body)

	conn.Write([]byte(response))
}

func handleStream(stream *smux.Stream) {
	defer stream.Close()

	// A mágica acontece aqui:
	// A lib txthinking processa o stream como se fosse uma conexão direta
	// CORREÇÃO AQUI: Acessamos o campo Handler e chamamos o método Handle
	if err := socksServer.ServeConn(stream); err != nil {
		return
	}

}
