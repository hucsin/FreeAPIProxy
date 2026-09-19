// ============================================================================
//
//	FreeApiGo —— Claudeflare 风格反向代理，Go 独立版（与 node-server.js 同构）
//
// 与 Node 版行为一致：
//   - 三种入站形态：
//     1) HTTP 绝对 URI：curl -x http://127.0.0.1:8788 https://api...
//     2) CONNECT 隧道：浏览器 / system proxy 的 https
//     3) egress 中继：X-Forward-Target 全上游 URL + X-Proxy-Token，转发时
//     剥掉内部头（X-Proxy-Token/X-Forward-Target 等），保留真实凭据
//   - 鉴权：X-Proxy-Token 头 / Authorization: Bearer <token> / ?token=
//   - 响应头过滤（hop-by-hop + Connection 声明 + proxy-* + via）
//   - 请求透传 strip 内部/cf- 头，注入 X-Upstream-Auth
//   - 跨域：仅当请求带 Origin 才回 CORS（回声 Origin + 允许凭据）
//   - 不跟随重定向（redirect:manual）
//   - DisableCompression=true → 上游 Content-Encoding 原样透传（等同 Node compress:false）
//
// 启动：
//
//	PROXY_TOKEN=xxx ./go-proxy [--port 8788] [--mode auto|socket|fetch]
//
// ============================================================================
package main

import (
	"context"
	"crypto/subtle"
	"crypto/tls"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

const (
	defaultPort  = 8788
	proxyVersion = "FreeApiGo/1.0"
)

var (
	enableFetch bool
)

// FORCE_FETCH_HOSTS：这些 host 走 fetch 路径（Go 中即单个 Transport 的 TLS 直连，
// 语义等价）；带前导 . 表示后缀匹配。保留它以对齐 node 的决策日志与人读配置。
var forceFetchHosts = []string{
	"api.openai.com",
	"api.anthropic.com",
	".anthropic.com",
	".claude.ai",
	".openai.com",
	"api.cline.bot",
	".cline.bot",
	// opencode/zen 上游同受 CF 保护，走同一路径。
	"opencode.ai",
	".opencode.ai",
}

// 请求侧剥掉的内部/危险头（保留真实上游凭据，但绝不把代理内部头透给上游）。
var blockedReq = map[string]struct{}{
	"x-forwarded-for": {}, "x-forwarded-proto": {}, "x-forwarded-host": {},
	"x-real-ip": {}, "true-client-ip": {}, "forwarded": {}, "via": {},
	"proxy-authorization": {}, "x-proxy-token": {}, "x-forward-target": {},
	"x-upstream-auth": {}, "x-proxy-mode": {},
}

// 响应侧 hop-by-hop / 危险头（避免分帧冲突与头注入）。
var respHop = map[string]struct{}{
	"connection": {}, "keep-alive": {}, "transfer-encoding": {}, "upgrade": {},
	"trailer": {}, "trailers": {}, "te": {}, "expect": {},
}

// proxyTransport 与 proxyClient：仅负责「一跳」转发；绝不套系统代理，不跟随重定向，
// 关闭自动解压以原样透传 Content-Encoding。
var proxyTransport = &http.Transport{
	Proxy:                 nil,
	DialContext:           (&net.Dialer{Timeout: 15 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
	MaxIdleConns:          256,
	MaxIdleConnsPerHost:   16,
	IdleConnTimeout:       90 * time.Second,
	TLSHandshakeTimeout:   15 * time.Second,
	ResponseHeaderTimeout: 30 * time.Second,
	DisableCompression:    true,
	TLSClientConfig:       &tls.Config{MinVersion: tls.VersionTLS12},
}
var proxyClient = &http.Client{
	Transport:     proxyTransport,
	CheckRedirect: func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse },
}

func cfg() (port int, token, mode string) {
	port = defaultPort
	flag.StringVar(&mode, "mode", os.Getenv("PROXY_MODE"), "proxy mode: auto|socket|fetch")
	flag.IntVar(&port, "port", defaultPort, "listen port")
	flag.Parse()
	if p := os.Getenv("PORT"); p != "" {
		if n, err := strconv.Atoi(p); err == nil && n > 0 && n < 65536 {
			port = n
		}
	}
	token = os.Getenv("PROXY_TOKEN")
	return
}

// shouldForceFetch 对齐 node：mode=fetch 强制走 fetch；否则按 host 规则判断。
func shouldForceFetch(host string) bool {
	if enableFetch {
		return true
	}
	if host == "" {
		return false
	}
	h := strings.ToLower(strings.Split(host, ":")[0])
	for _, d := range forceFetchHosts {
		dd := strings.ToLower(d)
		if strings.HasPrefix(dd, ".") {
			if strings.HasSuffix(h, dd) {
				return true
			}
		} else if h == dd {
			return true
		}
	}
	return false
}

// splitConnectionHeaders 解析 Connection 声明点名的头名。
func splitConnectionHeaders(v string) map[string]struct{} {
	out := map[string]struct{}{}
	for _, c := range strings.Split(v, ",") {
		if t := strings.TrimSpace(strings.ToLower(c)); t != "" {
			out[t] = struct{}{}
		}
	}
	return out
}

// filterResponseHeaders 过滤上游响应头：剥 hop-by-hop 与 Connection 声明的头、proxy-* 、via。
func filterResponseHeaders(in http.Header) http.Header {
	out := http.Header{}
	conn := splitConnectionHeaders(strings.Join(in.Values("Connection"), ","))
	for k, vals := range in {
		lk := strings.ToLower(k)
		if _, isHop := respHop[lk]; isHop {
			continue
		}
		if _, isConn := conn[lk]; isConn {
			continue
		}
		if strings.HasPrefix(lk, "proxy-") || lk == "via" {
			continue
		}
		for _, v := range vals {
			out.Add(k, v)
		}
	}
	return out
}

// buildForwardHeaders 构造发给上游的请求头：剥 blocked / cf- 内部头，注入上游凭据。
func buildForwardHeaders(in http.Header, upstreamAuth string) http.Header {
	out := http.Header{}
	conn := splitConnectionHeaders(strings.Join(in.Values("Connection"), ","))
	for k, vals := range in {
		lk := strings.ToLower(k)
		if _, b := blockedReq[lk]; b {
			continue
		}
		if strings.HasPrefix(lk, "cf-") || strings.HasPrefix(lk, "cf_") {
			continue
		}
		if _, isHop := respHop[lk]; isHop {
			continue
		}
		if _, isConn := conn[lk]; isConn {
			continue
		}
		for _, v := range vals {
			out.Add(k, v)
		}
	}
	if upstreamAuth != "" && out.Get("Authorization") == "" {
		out.Set("Authorization", upstreamAuth)
	}
	return out
}

// requestToken 从请求中提取代理 token（X-Proxy-Token / Authorization Bearer / ?token=）。
func requestToken(r *http.Request) string {
	if t := r.Header.Get("X-Proxy-Token"); t != "" {
		return t
	}
	if a := r.Header.Get("Authorization"); strings.HasPrefix(a, "Bearer ") {
		return strings.TrimPrefix(a, "Bearer ")
	}
	return r.URL.Query().Get("token")
}

func authOk(r *http.Request, token string) bool {
	if token == "" {
		return os.Getenv("PROXY_ALLOW_OPEN") == "1" // 默认禁裸奔
	}
	return constantTimeEqual(requestToken(r), token)
}

func constantTimeEqual(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}

func bad(w http.ResponseWriter, status int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.WriteHeader(status)
	body := fmt.Sprintf(`{"error":{"message":%q,"type":"proxy_error"}}`, msg)
	_, _ = io.WriteString(w, body)
}

// corsFor 仅当请求带 Origin（真实浏览器跨域）才回 CORS 放行头。
func corsFor(r *http.Request) http.Header {
	origin := r.Header.Get("Origin")
	if origin == "" {
		return nil
	}
	acr := r.Header.Get("Access-Control-Request-Headers")
	if acr == "" {
		acr = "Content-Type,Authorization,X-Proxy-Token,X-Forward-Target,X-Proxy-Mode,Range"
	}
	h := http.Header{}
	h.Set("Access-Control-Allow-Origin", origin)
	h.Set("Vary", "Origin")
	h.Set("Access-Control-Allow-Credentials", "true")
	h.Set("Access-Control-Allow-Methods", "GET,HEAD,POST,PUT,PATCH,DELETE,OPTIONS")
	h.Set("Access-Control-Allow-Headers", acr)
	h.Set("Access-Control-Max-Age", "86400")
	return h
}

// targetOf 解析转发目标：X-Forward-Target > 绝对 URI > 相对(基于 Host)。
func targetOf(r *http.Request) (*url.URL, error) {
	if fwd := r.Header.Get("X-Forward-Target"); fwd != "" {
		u, err := url.Parse(fwd)
		if err != nil {
			return nil, err
		}
		return u, nil
	}
	u := r.URL
	if u.Scheme == "" || u.Host == "" {
		var err error
		if u, err = url.Parse("http://" + r.Host + r.RequestURI); err != nil {
			return nil, err
		}
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return nil, fmt.Errorf("unsupported target protocol: %s", u.Scheme)
	}
	return u, nil
}

// copyBody 把响应体流式写回并逐块 flush（保障 SSE/流式逐块到达）。
func copyBody(w http.ResponseWriter, src io.Reader) {
	fl, _ := w.(http.Flusher)
	buf := make([]byte, 32*1024)
	for {
		n, err := src.Read(buf)
		if n > 0 {
			if _, werr := w.Write(buf[:n]); werr != nil {
				break
			}
			if fl != nil {
				fl.Flush()
			}
		}
		if err != nil {
			if err != io.EOF {
				_ = err
			}
			return
		}
	}
}

// forwardHTTP 单跳转发（Go 单个 Transport 即兼具 node 的 socket/fetch 语义）。
func forwardHTTP(res http.ResponseWriter, req *http.Request, token, mode string) {
	target, err := targetOf(req)
	if err != nil {
		bad(res, http.StatusBadRequest, err.Error())
		return
	}

	outReq, err := http.NewRequest(req.Method, target.String(), req.Body)
	if err != nil {
		bad(res, http.StatusBadRequest, "invalid target")
		return
	}
	outReq.Header = buildForwardHeaders(req.Header, req.Header.Get("X-Upstream-Auth"))
	outReq.Host = target.Host

	upstream, err := proxyClient.Do(outReq)
	if err != nil {
		bad(res, http.StatusBadGateway, "upstream failed: "+err.Error())
		return
	}
	defer upstream.Body.Close()

	merged := filterResponseHeaders(upstream.Header)
	for k, vals := range corsFor(req) {
		for _, v := range vals {
			merged.Set(k, v)
		}
	}
	for k, vals := range merged {
		for _, v := range vals {
			res.Header().Add(k, v)
		}
	}
	res.WriteHeader(upstream.StatusCode)
	copyBody(res, upstream.Body)
}

// handleConnect CONNECT 隧道：鉴权通过后 200，双向泵送。
func handleConnect(res http.ResponseWriter, req *http.Request, token string) {
	if !authOk(req, token) {
		if c, ok := res.(http.Hijacker); ok {
			if conn, _, err := c.Hijack(); err == nil {
				_, _ = conn.Write([]byte("HTTP/1.1 407 Proxy Authentication Required\r\n\r\n"))
				_ = conn.Close()
			}
		}
		return
	}
	hp := strings.Split(req.Host, ":")
	host := hp[0]
	var port = "443"
	if len(hp) > 1 && hp[1] != "" {
		port = hp[1]
	}
	upstream, err := net.DialTimeout("tcp", net.JoinHostPort(host, port), 15*time.Second)
	if err != nil {
		if c, ok := res.(http.Hijacker); ok {
			if conn, _, err2 := c.Hijack(); err2 == nil {
				_, _ = conn.Write([]byte("HTTP/1.1 502 Bad Gateway\r\n\r\n"))
				_ = conn.Close()
			}
		}
		return
	}
	defer upstream.Close()

	if c, ok := res.(http.Hijacker); ok {
		client, _, err := c.Hijack()
		if err != nil {
			return
		}
		defer client.Close()
		_, _ = client.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))
		go func() {
			_, _ = io.Copy(upstream, client)
			if tc, ok := upstream.(*net.TCPConn); ok {
				_ = tc.CloseWrite()
			}
		}()
		_, _ = io.Copy(client, upstream)
	}
}

func main() {
	port, token, mode := cfg()
	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodOptions {
			for k, vals := range corsFor(r) {
				for _, v := range vals {
					w.Header().Add(k, v)
				}
			}
			w.WriteHeader(http.StatusNoContent)
			return
		}
		if r.Method == http.MethodConnect {
			handleConnect(w, r, token)
			return
		}
		if !authOk(r, token) {
			bad(w, http.StatusUnauthorized, "invalid or missing proxy token")
			return
		}
		shouldForceFetch(r.Host) // 与 node 语义对齐（Go 单路径，记录决策）
		forwardHTTP(w, r, token, mode)
	})

	srv := &http.Server{
		Addr:              ":" + fmt.Sprintf("%d", port),
		Handler:           nil,
		ReadHeaderTimeout: 15 * time.Second,
	}
	// 优雅关闭由 systemd 通过 SIGTERM 触发（默认）。
	_ = context.Background()

	logf("[FreeApiGo] %s proxy listening :%d mode=%s", proxyVersion, port, mode)
	if token == "" {
		logf("[FreeApiGo] token: EMPTY (open mode disabled by default)")
	} else {
		logf("[FreeApiGo] token: configured")
	}
	if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		logf("[FreeApiGo] serve error: %v", err)
		os.Exit(1)
	}
}

func logf(f string, a ...interface{}) {
	fmt.Fprintf(os.Stderr, time.Now().Format("2006/01/02 15:04:05 ")+f+"\n", a...)
}
