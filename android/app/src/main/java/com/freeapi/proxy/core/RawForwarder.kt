package com.freeapi.proxy.core

import com.freeapi.proxy.ProxyConfig
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 响应头还没发给客户端就失败 → 可以安全降级到 fetch 路径。
 * 对齐 node 里 `forwardViaSocket` 抛错后回退 `forwardViaFetch` 的语义。
 */
class PreResponseFailure(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Socket 直连转发（对应 node 的 socket 模式）。
 *
 * 与 node 的差别：node 复用 node:http/https 客户端完成协议，这里手写 HTTP/1.1
 * 请求行与头块，并对响应自己做分帧，因此能做到真正的「流式不透支内存」。
 * TLS 走 Android 系统栈（Conscrypt），与 Chrome 同源，指纹上比裸 TCP 库更自然。
 */
object RawForwarder {

    @Throws(IOException::class)
    fun forward(
        cfg: ProxyConfig,
        head: RequestHead,
        target: URL,
        body: BodySource,
        responder: ClientResponder,
        cors: List<Header>,
        stats: ProxyStats,
    ): Boolean {
        val scheme = target.protocol.lowercase()
        val host = target.host
        if (host.isNullOrEmpty()) throw PreResponseFailure("target host is empty")
        val port = if (target.port > 0) target.port else if (scheme == "https") 443 else 80

        // ---------- 阶段 1：建连 + 发请求 + 读响应头（任何失败都可降级 fetch）----------
        var sock: Socket = Socket()
        var status: Int
        var reason: String
        var upHeaders: List<Header>
        val upReader: BufReader

        try {
            sock.tcpNoDelay = true
            sock.soTimeout = cfg.connectTimeoutMs
            sock.connect(InetSocketAddress(host, port), cfg.connectTimeoutMs)

            if (scheme == "https") {
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val tls = factory.createSocket(sock, host, port, true) as SSLSocket
                tls.soTimeout = cfg.connectTimeoutMs
                runCatching {
                    val p = tls.sslParameters
                    // 只留 TLS1.2/1.3，别回到 SSLv3/TLS1.0——上游基本都拒
                    p.protocols = arrayOf("TLSv1.3", "TLSv1.2")
                    // 保持证书链 + 主机名校验（与 node 的 rejectUnauthorized 默认行为一致）
                    p.endpointIdentificationAlgorithm = "HTTPS"
                    tls.sslParameters = p
                }
                tls.startHandshake()
                sock = tls
            }

            val upOut = BufferedOutputStream(sock.getOutputStream(), 16 * 1024)
            upReader = BufReader(sock.getInputStream())

            val sent = sendRequest(upOut, cfg, head, target, body)
            stats.addUp(sent)

            sock.soTimeout = cfg.connectTimeoutMs + 30_000
            val parsed = readResponseHead(upReader)
            status = parsed.status
            reason = parsed.reason
            upHeaders = parsed.headers
        } catch (t: Throwable) {
            closeQuietly(sock)
            throw PreResponseFailure("connect/forward $host:$port failed: ${t.message}", t)
        }

        // ---------- 阶段 2：写响应（此后失败只能切连接，不能再降级）----------
        try {
            val (upFraming, rawCl, _) = Relay.upstreamFraming(upHeaders, head.method, status)
            val downFraming = Relay.downstreamFraming(upFraming)
            val closeClient = !head.keepAliveHint

            responder.writeHead(
                status = status,
                reason = reason,
                headers = ProxyRules.filterResponseHeaders(upHeaders),
                framing = downFraming,
                contentLength = rawCl,
                extra = cors,
                close = closeClient,
            )

            if (upFraming != Framing.NONE) {
                sock.soTimeout = cfg.idleTimeoutMs
                Relay.pump(
                    src = upReader,
                    dst = responder.out(),
                    upFraming = upFraming,
                    upLen = rawCl,
                    downFraming = downFraming,
                    flushEach = true,
                ) { stats.addDown(it) }
            }
            return !closeClient
        } finally {
            closeQuietly(sock)
        }
    }

    /** 组装并发送请求行 + 头块 + body，返回 body 的载荷字节数。 */
    @Throws(IOException::class)
    private fun sendRequest(
        upOut: OutputStream,
        cfg: ProxyConfig,
        head: RequestHead,
        target: URL,
        body: BodySource,
    ): Long {
        val path = buildString {
            val p = target.path
            append(if (p.isNullOrEmpty()) "/" else p)
            val q = target.query
            if (!q.isNullOrEmpty()) append('?').append(q)
        }

        val upUA = head.header("x-upstream-user-agent").orEmpty()
        val hdrs = ProxyRules.forwardRequestHeaders(head.headers, head.header("x-upstream-auth"), upUA)
        hdrs.add(0, Header("Host", target.authority ?: target.host))

        // UA 覆盖仅在宿主没给 X-Upstream-User-Agent 时生效：承载头代表上游真实 UA（opencode 门禁依赖）。
        if (cfg.userAgentOverride.isNotEmpty() && upUA.isEmpty()) {
            hdrs.removeAll { it.name.equals("user-agent", ignoreCase = true) }
            hdrs.add(Header("User-Agent", cfg.userAgentOverride))
        }

        val cl = body.contentLength
        val reqFraming = when {
            cl == null -> Framing.CHUNKED
            cl > 0L -> Framing.LENGTH
            BodyReader.declaresBody(head) -> Framing.LENGTH
            else -> Framing.NONE
        }
        when (reqFraming) {
            Framing.LENGTH -> hdrs.add(Header("Content-Length", (cl ?: 0L).toString()))
            Framing.CHUNKED -> hdrs.add(Header("Transfer-Encoding", "chunked"))
            else -> Unit
        }
        // 不做上游连接复用：一跳一连接，语义最简单也最不容易出分帧错位。
        hdrs.add(Header("Connection", "close"))

        val sb = StringBuilder(512)
        sb.append(head.method).append(' ').append(path).append(" HTTP/1.1").append(HttpHead.CRLF)
        for (h in hdrs) {
            sb.append(h.name).append(": ").append(h.value).append(HttpHead.CRLF)
        }
        sb.append(HttpHead.CRLF)
        upOut.write(sb.toString().toByteArray(Charsets.ISO_8859_1))

        if (reqFraming == Framing.NONE) {
            upOut.flush()
            return 0L
        }
        val framed: OutputStream =
            if (reqFraming == Framing.CHUNKED) ChunkedOutputStream(upOut) else upOut
        val n = body.copyPayloadTo(framed)
        if (framed is ChunkedOutputStream) framed.finish()
        upOut.flush()
        return n
    }

    private data class ParsedResponse(val status: Int, val reason: String, val headers: List<Header>)

    /** 读状态行 + 头块，自动跳过 1xx 中间响应（100/103 等）。 */
    @Throws(IOException::class)
    private fun readResponseHead(reader: BufReader): ParsedResponse {
        var round = 0
        while (true) {
            val line = reader.readLine() ?: throw IOException("upstream closed before response")
            if (line.isEmpty()) continue
            val sp = line.indexOf(' ')
            if (sp <= 0) throw IOException("bad status line: ${line.take(80)}")
            val rest = line.substring(sp + 1).trim()
            val code = rest.substringBefore(' ').toIntOrNull()
                ?: throw IOException("bad status code: ${line.take(80)}")
            val reason = if (rest.length > 4) rest.substring(4).trim() else ""

            val headers = ArrayList<Header>(24)
            while (true) {
                val h = reader.readLine() ?: throw IOException("EOF inside upstream headers")
                if (h.isEmpty()) break
                if (h[0] == ' ' || h[0] == '\t') continue
                val i = h.indexOf(':')
                if (i <= 0) continue
                headers.add(Header(h.substring(0, i).trim(), h.substring(i + 1).trim()))
            }

            if (code in 100..199 && code != 101) {
                if (++round > 4) throw IOException("too many informational responses")
                continue
            }
            return ParsedResponse(code, reason, headers)
        }
    }
}
