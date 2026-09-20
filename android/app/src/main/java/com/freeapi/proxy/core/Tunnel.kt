package com.freeapi.proxy.core

import com.freeapi.proxy.ProxyConfig
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * CONNECT 隧道（对应 node 的 handleConnect）。
 *
 * 两个容易翻车的点：
 *  1. 头解析阶段用的 [BufReader] 可能已经预读了隧道载荷（TLS ClientHello 常常紧跟
 *     CONNECT 的 CRLF 一起到达），必须把它们先倒给上游，否则上游收到的 TLS 记录是残的。
 *  2. 隧道是长连接，进入泵送阶段后必须把两边 soTimeout 归零，否则会被空闲超时掐断。
 */
object Tunnel {

    private const val JOIN_MS = 30_000L
    private val COPY_BUF = 32 * 1024

    fun handle(
        cfg: ProxyConfig,
        head: RequestHead,
        conn: InboundConn,
        clientReader: BufReader,
        responder: ClientResponder,
        live: LiveSockets,
        stats: ProxyStats,
        log: ProxyLog,
    ) {
        val raw = head.target
        val colon = raw.lastIndexOf(':')
        val host = (if (colon > 0) raw.substring(0, colon) else raw).trim().trim('[', ']')
        val port = if (colon > 0) raw.substring(colon + 1).trim().toIntOrNull() ?: 443 else 443

        if (host.isEmpty() || port !in 1..65535) {
            log.warn("CONNECT 目标非法：${raw.take(80)}")
            raw503(responder, "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            return
        }

        val up = Socket()
        try {
            up.tcpNoDelay = true
            up.soTimeout = cfg.connectTimeoutMs
            up.connect(InetSocketAddress(host, port), cfg.connectTimeoutMs)
        } catch (t: Throwable) {
            closeQuietly(up)
            log.warn("CONNECT $host:$port 建连失败：${t.message}")
            raw503(responder, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            return
        }

        live.add(up)
        stats.tunnelOpened()
        try {
            up.soTimeout = 0
            // 隧道是长连接，读超时必须归零。注意对 ZeroTier 侧这是 no-op——
            // 它本来就不支持 SO_RCVTIMEO（见 InboundConn 类注释）。
            conn.setReadTimeout(0)

            val clientOut = responder.out()
            raw503(responder, "HTTP/1.1 200 Connection Established\r\n\r\n")

            val upOut = BufferedOutputStream(up.getOutputStream(), 16 * 1024)
            val carried = clientReader.drainBuffered()
            if (carried.isNotEmpty()) {
                upOut.write(carried)
                upOut.flush()
            }

            val clientIn = conn.input
            val upIn = up.getInputStream()

            val c2s = Thread({
                try {
                    copyLoop(clientIn, upOut)
                    runCatching { up.shutdownOutput() }
                } catch (_: Throwable) {
                }
            }, "proxy-tunnel-up").apply {
                isDaemon = true
                start()
            }

            try {
                copyLoop(upIn, clientOut)
                conn.shutdownOutput()
            } catch (_: Throwable) {
            }
            runCatching { c2s.join(JOIN_MS) }
        } finally {
            closeQuietly(up)
            live.remove(up)
        }
    }

    private fun copyLoop(src: InputStream, dst: OutputStream) {
        val buf = ByteArray(COPY_BUF)
        while (true) {
            val n = src.read(buf)
            if (n < 0) return
            if (n == 0) continue
            dst.write(buf, 0, n)
            dst.flush()
        }
    }

    private fun raw503(responder: ClientResponder, text: String) {
        responder.out().write(text.toByteArray(Charsets.ISO_8859_1))
        responder.out().flush()
    }
}
