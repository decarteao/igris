package main

import (
	"bufio"
	// "context"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/things-go/go-socks5"
	"github.com/xtaci/smux"
)

var (
	currentConnections int64 = 0    // total de usuarios ativos
	maxConnections     int64 = 5000 // maximo de usuarios ativos
)

const (
	listenIP         = "0.0.0.0"
	listenPort       = 80
	listenIPSocks    = "127.0.0.1"
	listenPortSocks  = 8999
	bufferSize       = 16 * 1024
	readHeaderLimit  = 8 * 1024 // limite para leitura de header
	connDialTimeout  = 8 * time.Second
	streamIdleTO     = 30 * time.Second
	sessionKeepAlive = 10 * time.Second

	// autenticação
	user     = "sung"
	password = "123.456"
)

// pools para buffers (reuso)
var bufPool = sync.Pool{
	New: func() interface{} {
		b := make([]byte, bufferSize)
		return &b
	},
}

var logger = log.New(os.Stdout, "", log.LstdFlags)

func main() {
	// Carregar configurações de ambiente
	if val, err := strconv.ParseInt(os.Getenv("MAX_CONN"), 10, 64); err == nil {
		maxConnections = val
	}

	// Iniciar servidor Socks5 interno
	go startSocks5()

	ln, err := net.Listen("tcp", fmt.Sprintf("%s:%d", listenIP, listenPort))
	if err != nil {
		logger.Fatal(err)
	}
	defer ln.Close()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)

	go func() {
		<-stop
		logger.Println("\n[!] Shutdown recebido, fechando listener...")
		_ = ln.Close()
	}()

	logger.Printf("[!] Servidor Otimizado na porta %d\n", listenPort)
	logger.Printf("[!] Limite de Conexões: %d\n", maxConnections)

	for {
		conn, err := ln.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return
			}
			continue
		}

		// Controle de conexões máximas
		if atomic.LoadInt64(&currentConnections) >= maxConnections {
			conn.Close()
			continue
		}

		go handleConnection(conn)
	}
}

func handleConnection(conn net.Conn) {
	// 1. Definir um tempo limite para o handshake inicial (Proteção anti-slowloris)
	conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	defer conn.Close()

	reader := bufio.NewReaderSize(conn, bufferSize)

	// 2. FAST-PATH: Ler apenas a primeira linha para o endpoint /users
	firstLine, err := reader.ReadString('\n')
	if err != nil {
		return
	}

	if strings.Contains(firstLine, "GET /users") {
		handleUsers(conn)
		return
	}

	// 3. Processar o resto do Header para WebSocket/VPN
	// (Caso o cliente envie headers em múltiplas linhas)
	fullHeader := firstLine
	for {
		line, err := reader.ReadString('\n')
		if err != nil || line == "\r\n" || line == "\n" {
			break
		}
		fullHeader += line
		if len(fullHeader) > readHeaderLimit {
			return
		}
	}

	// 4. Se chegou aqui, é uma tentativa de túnel. Limpar Deadline para o tráfego fluir.
	conn.SetReadDeadline(time.Time{})

	// Responder ao cliente que a conexão foi aceita (Handshake HTTP)
	_, _ = conn.Write([]byte("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n"))

	// Configuração Otimizada do Smux para Redes Móveis
	smuxConfig := smux.DefaultConfig()
	smuxConfig.MaxReceiveBuffer = 4 * 1024 * 1024 // 4MB Buffer
	smuxConfig.KeepAliveInterval = sessionKeepAlive
	smuxConfig.KeepAliveTimeout = 30 * time.Second

	session, err := smux.Server(conn, smuxConfig)
	if err != nil {
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

func handleUsers(conn net.Conn) {
	// Resposta HTTP simples e rápida
	usersOnline := atomic.LoadInt64(&currentConnections)
	response := fmt.Sprintf("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: %d\r\n\r\n%d",
		len(strconv.FormatInt(usersOnline, 10)), usersOnline)

	conn.Write([]byte(response))
}

func handleStream(stream *smux.Stream) {
	defer stream.Close()

	// Conectar ao Socks5 local
	target, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", listenIPSocks, listenPortSocks), connDialTimeout)
	if err != nil {
		return
	}
	defer target.Close()

	done := make(chan struct{})
	go func() {
		copyData(target, stream)
		close(done)
	}()

	go func() {
		copyData(stream, target)
		close(done)
	}()

	<-done
}

func copyData(dst io.Writer, src io.Reader) {
	bufRef := bufPool.Get().(*[]byte)
	defer bufPool.Put(bufRef)
	_, _ = io.CopyBuffer(dst, src, *bufRef)
}

func startSocks5() {
	server := socks5.NewServer(
		socks5.WithAuthMethods([]socks5.Authenticator{
			socks5.UserPassAuthenticator{Credentials: socks5.StaticCredentials{user: password}},
		}),
	)
	if err := server.ListenAndServe("tcp", fmt.Sprintf("%s:%d", listenIPSocks, listenPortSocks)); err != nil {
		logger.Fatal("Erro no Socks5:", err)
	}
}
