package main

import (
	"bufio"
	"context"
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

// safeLog wrapper
var logger = log.New(os.Stdout, "", log.LstdFlags)

// smux config (ajustes para performance)
func newSmuxConfig() *smux.Config {
	cfg := smux.DefaultConfig()
	// ajusta valores sensíveis à latência / throughput
	cfg.KeepAliveInterval = sessionKeepAlive
	cfg.MaxReceiveBuffer = 64 * 1024 // janela de recebimento por stream
	cfg.MaxStreamBuffer = 64 * 1024  // buffer por stream
	cfg.KeepAliveTimeout = 2 * time.Minute
	return cfg
}

// readRequestHeaders lê até o CRLF CRLF (com limite) e retorna um map de headers (lowercase).
func readRequestHeaders(r *bufio.Reader, limit int) (string, map[string]string, error) {
	var sb strings.Builder
	hdrs := make(map[string]string)
	total := 0

	for {
		line, err := r.ReadString('\n')
		if err != nil {
			return "", nil, err
		}
		total += len(line)
		if total > limit {
			return "", nil, errors.New("header too large")
		}
		sb.WriteString(line)
		// fim de headers
		if line == "\r\n" {
			break
		}
		// coletar key: value
		if strings.Contains(line, ":") {
			parts := strings.SplitN(line, ":", 2)
			k := strings.ToLower(strings.TrimSpace(parts[0]))
			v := strings.TrimSpace(parts[1])
			hdrs[k] = v
		}
	}
	return sb.String(), hdrs, nil
}

// proxyCopy garante fechamento apropriado quando uma das cópias termina
func proxyCopy(dst net.Conn, src net.Conn, cancel context.CancelFunc) {
	bufp := bufPool.Get().(*[]byte)
	defer bufPool.Put(bufp)

	_, _ = io.CopyBuffer(dst, src, *bufp)
	// quando uma direção terminar, cancela contexto para forçar fechamento coordenado
	if cancel != nil {
		cancel()
	}
}

// handleSession aceita streams numa sessão smux e cria ponte para SOCKS local
func handleSession(ctx context.Context, session *smux.Session) {
	defer session.Close()

	for {
		select {
		case <-ctx.Done():
			return
		default:
			stream, err := session.AcceptStream()
			if err != nil {
				// smux returns EOF on normal close
				if errors.Is(err, io.EOF) || strings.Contains(err.Error(), "read/write on closed pipe") {
					return
				}
				logger.Println("Erro ao aceitar stream:", err)
				return
			}
			// cada stream cria sua ponte ao socks
			go func(s *smux.Stream) {
				// recuperação para evitar crash na goroutine
				defer func() {
					if r := recover(); r != nil {
						logger.Println("panic na stream:", r)
					}
				}()
				if s == nil {
					return
				}

				dialer := net.Dialer{Timeout: connDialTimeout, KeepAlive: 30 * time.Second}
				socksConn, err := dialer.Dial("tcp", fmt.Sprintf("%s:%d", listenIPSocks, listenPortSocks))
				if err != nil {
					logger.Println("Erro ao abrir socks:", err)
					s.Close()
					return
				}
				// contexto para fechar cópias quando uma terminar
				ctxC, cancel := context.WithCancel(context.Background())
				defer cancel()

				// usar duas goroutines de cópia e fechar conexões após
				go func() {
					proxyCopy(socksConn, s, cancel)
					// garantir fechamento adequado
					_ = socksConn.Close()
					_ = s.Close()
				}()
				proxyCopy(s, socksConn, cancel)
				// garantir fechamento final (idempotente)
				_ = socksConn.Close()
				_ = s.Close()
				<-ctxC.Done() // espera cancel se necessário
			}(stream)
		}
	}
}

// cria_bridge: monta sessão smux no conn
func createBridge(conn net.Conn) {
	// log e contador
	logger.Printf("[!] Conexão iniciada: %s\n", conn.RemoteAddr().String())
	atomic.AddInt64(&currentConnections, 1)
	defer func() {
		atomic.AddInt64(&currentConnections, -1)
		logger.Printf("[!] Conexão fechada: %s\n", conn.RemoteAddr().String())
		conn.Close()
	}()

	// configurar sessão smux (servidor)
	session, err := smux.Server(conn, newSmuxConfig())
	if err != nil {
		// responder HTTP simples com erro
		msg := err.Error()
		resp := fmt.Sprintf("HTTP/1.1 403 Nn criou o streaming :(\r\nServer: KaihoVPN\r\nContent-Type: text/plain\r\nContent-Length: %d\r\n\r\n%s", len(msg), msg)
		_, _ = conn.Write([]byte(resp))
		return
	}

	// criar contexto para a sessão
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// desligar sessão quando o contexto encerrar
	go func() {
		<-ctx.Done()
		session.Close()
	}()

	handleSession(ctx, session)
}

// clientHandler: valida handshake HTTP-like, autentica e cria ponte
func clientHandler(conn net.Conn) {
	// adicionar proteção em caso de panics
	defer func() {
		if r := recover(); r != nil {
			logger.Println("panic em clientHandler:", r)
			conn.Close()
		}
	}()

	// definir deadline curto para leitura do header
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	r := bufio.NewReader(conn)

	_, hdrs, err := readRequestHeaders(r, readHeaderLimit)
	if err != nil {
		// não fez handshake corretamente: fechar
		logger.Println("Erro leitura headers:", err)
		conn.Write([]byte("HTTP/1.1 400 Bad Request\r\nServer: KaihoVPN\r\n\r\n"))
		conn.Close()
		return
	}
	// limpar deadline
	_ = conn.SetReadDeadline(time.Time{})

	// endpoint de status simples: GET /users
	buf := new(strings.Builder)
	for k, v := range hdrs {
		buf.WriteString(fmt.Sprintf("%s: %s\n", k, v))
	}
	joined := buf.String()

	if strings.Contains(strings.ToLower(joined), "get /users") {
		status := fmt.Sprintf(`{"data": "%d/%d"}`, atomic.LoadInt64(&currentConnections), maxConnections)
		resp := fmt.Sprintf("HTTP/1.1 200 OK\r\nServer: KaihoVPN\r\nContent-Type: application/json\r\nContent-Length: %d\r\n\r\n%s", len(status), status)
		_, _ = conn.Write([]byte(resp))
		conn.Close()
		return
	}

	// limitar conexões
	if atomic.LoadInt64(&currentConnections) >= maxConnections {
		_, _ = conn.Write([]byte("HTTP/1.1 503 Servidor lotado\r\nServer: KaihoVPN\r\n\r\n"))
		conn.Close()
		return
	}

	// verificar upgrade websocket mínimo
	if v, ok := hdrs["upgrade"]; !ok || !strings.Contains(strings.ToLower(v), "websocket") {
		_, _ = conn.Write([]byte("HTTP/1.1 403 Payload invalida\r\nServer: KaihoVPN\r\n\r\nPayload Invalida :)"))
		conn.Close()
		return
	}

	// autenticação: procurar headers "user" e "password"
	u, uok := hdrs["user"]
	p, pok := hdrs["password"]
	if !uok || !pok || u != user || p != password {
		_, _ = conn.Write([]byte("HTTP/1.1 403 Credenciais incorrectas\r\nServer: KaihoVPN\r\n\r\nPayload Invalida :)"))
		conn.Close()
		return
	}

	// enviar handshake de upgrade
	_, _ = conn.Write([]byte("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n"))

	// agora criamos a ponte smux
	logger.Printf("{!} Nova conexão autenticada: %s\n", conn.RemoteAddr())
	createBridge(conn)
}

func main() {
	// logger básico
	logger.Printf("[!] Inicializando SOCKS e servidor na porta %d (HTTP: %d)\n", listenPortSocks, listenPort)

	// iniciar SOCKS em goroutine
	server := socks5.NewServer(
		socks5.WithLogger(socks5.NewLogger(logger)),
	)
	go func() {
		logger.Printf("[!] SOCKS Server iniciado na porta %d\n", listenPortSocks)
		if err := server.ListenAndServe("tcp", fmt.Sprintf("%s:%d", listenIPSocks, listenPortSocks)); err != nil {
			logger.Fatalf("socks listen error: %v", err)
		}
	}()

	// permitir override de max_connections via arg
	if len(os.Args) > 1 {
		if val, err := strconv.ParseInt(os.Args[1], 10, 64); err == nil && val > 0 {
			maxConnections = val
		}
	}

	ln, err := net.Listen("tcp", fmt.Sprintf("%s:%d", listenIP, listenPort))
	if err != nil {
		logger.Fatal(err)
	}
	defer ln.Close()

	// trap de sinais para shutdown gracioso
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)

	// --- CORREÇÃO DE SHUTDOWN ---
	go func() {
		<-stop
		logger.Println("\n[!] Shutdown recebido, fechando listener...")
		// Fechamos o canal para que leituras nele não bloqueiem mais
		close(stop)
		// Forçamos o fechamento do listener para desbloquear o Accept()
		_ = ln.Close()
	}()

	logger.Printf("[!] Sung WebSocket iniciado na porta %d\n", listenPort)
	logger.Printf("[!] MaxConnections: %d\n", maxConnections)

	var wg sync.WaitGroup
	for {
		conn, err := ln.Accept()
		if err != nil {
			// Se o erro ocorreu porque fechamos o listener (shutdown), saímos do loop
			select {
			case <-stop:
				// Shutdown em andamento, sair limpo
				return
			default:
			}

			// Verificação extra para erros de rede fechada
			if errors.Is(err, net.ErrClosed) || strings.Contains(err.Error(), "use of closed network connection") {
				return
			}

			logger.Println("Erro ao aceitar conexao:", err)
			continue
		}

		// aplicar TCP keepalive e otimizações
		if tcpConn, ok := conn.(*net.TCPConn); ok {
			_ = tcpConn.SetKeepAlive(true)
			_ = tcpConn.SetKeepAlivePeriod(30 * time.Second)
			_ = tcpConn.SetNoDelay(true)
		}

		wg.Add(1)
		go func(c net.Conn) {
			defer wg.Done()
			clientHandler(c)
		}(conn)
	}
}
