package com.freeapi.proxy.core

import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * 分帧方式。
 *  - NONE   ：无消息体（HEAD / 204 / 304）
 *  - LENGTH ：定长（Content-Length）
 *  - CHUNKED：chunked 分帧
 *  - CLOSE  ：上游用「关连接」界定结束（HTTP/1.0 或 chunked 缺失）——只用于上游读取侧
 */
enum class Framing { NONE, LENGTH, CHUNKED, CLOSE }

object HttpReason {
    fun of(status: Int): String = when (status) {
        100 -> "Continue"; 101 -> "Switching Protocols"
        200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 204 -> "No Content"
        206 -> "Partial Content"
        301 -> "Moved Permanently"; 302 -> "Found"; 303 -> "See Other"
        304 -> "Not Modified"; 307 -> "Temporary Redirect"; 308 -> "Permanent Redirect"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
        404 -> "Not Found"; 405 -> "Method Not Allowed"; 408 -> "Request Timeout"
        413 -> "Payload Too Large"; 414 -> "URI Too Long"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"; 501 -> "Not Implemented"; 502 -> "Bad Gateway"
        503 -> "Service Unavailable"; 504 -> "Gateway Timeout"
        else -> "Unknown"
    }
}

/**
 * 面向客户端的响应写出器，同时记录"响应头是否已发出"。
 * 语义对齐 node 的 res：头发出去之后就不能再改状态码，只能切连接（见 [writeError]）。
 */
class ClientResponder(private val raw: OutputStream) {

    @Volatile
    var headWritten: Boolean = false
        private set

    @Volatile
    var status: Int = 0
        private set

    fun out(): OutputStream = raw

    fun writeHead(
        status: Int,
        reason: String,
        headers: List<Header>,
        framing: Framing,
        contentLength: Long,
        extra: List<Header>,
        close: Boolean,
    ) {
        this.status = status
        val sb = StringBuilder(512)
        sb.append("HTTP/1.1 ").append(status).append(' ')
            .append(if (reason.isNotEmpty()) reason else HttpReason.of(status))
            .append(HttpHead.CRLF)
        for (h in headers) {
            sb.append(h.name).append(": ").append(h.value).append(HttpHead.CRLF)
        }
        when (framing) {
            Framing.LENGTH -> sb.append("Content-Length: ").append(contentLength).append(HttpHead.CRLF)
            Framing.CHUNKED -> sb.append("Transfer-Encoding: chunked").append(HttpHead.CRLF)
            Framing.NONE -> if (contentLength >= 0) {
                sb.append("Content-Length: ").append(contentLength).append(HttpHead.CRLF)
            }
            Framing.CLOSE -> Unit
        }
        for (h in extra) {
            sb.append(h.name).append(": ").append(h.value).append(HttpHead.CRLF)
        }
        sb.append("Connection: ").append(if (close) "close" else "keep-alive").append(HttpHead.CRLF)
        sb.append(HttpHead.CRLF)
        raw.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        raw.flush()
        headWritten = true
    }

    /**
     * 错误响应。对齐 node 的 bad()：固定 `{"error":{"message":…,"type":"proxy_error"}}`
     * 结构，并始终带 `Access-Control-Allow-Origin: *`；
     * 若响应头已发出则不再追加任何东西（只能由调用方切连接）。
     */
    fun writeError(status: Int, message: String) {
        if (headWritten) {
            return
        }
        val body = ("{\"error\":{\"message\":\"" + escapeJson(message) +
            "\",\"type\":\"proxy_error\"}}").toByteArray(Charsets.UTF_8)
        writeHead(
            status = status,
            reason = HttpReason.of(status),
            headers = listOf(
                Header("Content-Type", "application/json"),
                Header("Access-Control-Allow-Origin", "*"),
            ),
            framing = Framing.LENGTH,
            contentLength = body.size.toLong(),
            extra = emptyList(),
            close = true,
        )
        raw.write(body)
        raw.flush()
    }
}

private fun escapeJson(s: String): String {
    val sb = StringBuilder(s.length + 8)
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
    }
    return sb.toString()
}

object Relay {

    private const val BUF = 32 * 1024

    /**
     * 把上游响应体泵给客户端。
     *
     * 上游「读取分帧」与下游「写出分帧」是解耦的：上游 chunked 会被解码，
     * 再按下游分帧重编码。这样即使上游用 chunked、下游因为长度未知也必须用 chunked，
     * SSE 依然是每读到一块就 flush 一块，不会被攒成整包。
     */
    @Throws(IOException::class)
    fun pump(
        src: BufReader,
        dst: OutputStream,
        upFraming: Framing,
        upLen: Long,
        downFraming: Framing,
        flushEach: Boolean,
        onBytes: (Long) -> Unit,
    ): Long {
        val framed: OutputStream =
            if (downFraming == Framing.CHUNKED) ChunkedOutputStream(dst) else dst
        val tmp = ByteArray(BUF)
        var total = 0L
        try {
            when (upFraming) {
                Framing.NONE -> Unit

                Framing.LENGTH -> {
                    var remain = upLen
                    while (remain > 0) {
                        val want = minOf(remain, tmp.size.toLong()).toInt()
                        val got = src.readSome(tmp, 0, want)
                        if (got < 0) break
                        framed.write(tmp, 0, got)
                        if (flushEach) framed.flush()
                        remain -= got
                        total += got
                    }
                }

                Framing.CHUNKED -> {
                    while (true) {
                        val sizeLine = src.readLine() ?: break
                        val size = parseChunkSize(sizeLine)
                        if (size == 0L) {
                            skipTrailers(src)
                            break
                        }
                        var remain = size
                        while (remain > 0) {
                            val want = minOf(remain, tmp.size.toLong()).toInt()
                            val got = src.readSome(tmp, 0, want)
                            if (got < 0) throw IOException("EOF inside upstream chunk")
                            framed.write(tmp, 0, got)
                            if (flushEach) framed.flush()
                            remain -= got
                            total += got
                        }
                        src.readLine() // chunk 数据后的 CRLF
                    }
                }

                Framing.CLOSE -> {
                    while (true) {
                        val got = src.readSome(tmp)
                        if (got < 0) break
                        framed.write(tmp, 0, got)
                        if (flushEach) framed.flush()
                        total += got
                    }
                }
            }
            if (framed is ChunkedOutputStream) framed.finish()
            dst.flush()
        } finally {
            onBytes(total)
        }
        return total
    }

    /**
     * 根据上游响应头判定读取分帧。
     * @return Triple(上游读取分帧, 上游 Content-Length(-1 表示没有), 是否无消息体)
     */
    fun upstreamFraming(headers: List<Header>, method: String, status: Int):
        Triple<Framing, Long, Boolean> {
        val cl = headers.firstOrNull { it.name.equals("content-length", ignoreCase = true) }
            ?.value?.trim()?.toLongOrNull() ?: -1L
        val chunked = headers.any {
            it.name.equals("transfer-encoding", ignoreCase = true) &&
                it.value.contains("chunked", ignoreCase = true)
        }
        val noBody = method.equals("HEAD", ignoreCase = true) ||
            status == 204 || status == 304 || status == 205 || status < 200
        val framing = when {
            noBody -> Framing.NONE
            chunked -> Framing.CHUNKED
            cl >= 0 -> Framing.LENGTH
            else -> Framing.CLOSE
        }
        return Triple(framing, cl, noBody)
    }

    /** 下游写出分帧：定长可定长，其余一律重新 chunked。 */
    fun downstreamFraming(up: Framing): Framing = when (up) {
        Framing.NONE -> Framing.NONE
        Framing.LENGTH -> Framing.LENGTH
        else -> Framing.CHUNKED
    }
}

/**
 * 集中管理"活着的连接"，用于停止服务时一次性掐断。
 *
 * 放进来的有两类：入站连接（[InboundConn]，可能是系统 socket 也可能是 ZeroTier 虚拟 socket）
 * 和 CONNECT 隧道用的上游 `java.net.Socket`。两者唯一的公共能力就是 [Closeable]，
 * 因此按 [Closeable] 持有即可。
 */
class LiveSockets {
    private val set = ConcurrentHashMap.newKeySet<Closeable>()

    fun add(c: Closeable) {
        set.add(c)
    }

    fun remove(c: Closeable) {
        set.remove(c)
    }

    fun closeAll() {
        for (c in set) {
            closeQuietly(c)
        }
        set.clear()
    }

    val size: Int get() = set.size
}

fun closeQuietly(c: Closeable?) {
    if (c == null) return
    try {
        c.close()
    } catch (_: Throwable) {
    }
}
