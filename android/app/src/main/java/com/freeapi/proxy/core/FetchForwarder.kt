package com.freeapi.proxy.core

import com.freeapi.proxy.ProxyConfig
import java.io.IOException
import java.io.OutputStream
import java.net.URL
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

/**
 * fetch 兜底转发（对应 node 的 `forwardViaFetch` / undici）。
 *
 * 关键点在于 **不关闭 OkHttp 的透明 gzip 解压**：OkHttp 只在「自己加的 Accept-Encoding」
 * 时才透明解压，所以这里显式把客户端的 Accept-Encoding 原值带上（没有就填 identity），
 * 于是上游的 Content-Encoding / Content-Length 会原样透传 —— 等价 node 的 `compress:false`。
 */
object FetchForwarder {

    private val lock = Any()
    private var cached: OkHttpClient? = null
    private var cachedKey: String = ""

    private fun client(cfg: ProxyConfig): OkHttpClient {
        val key = "${cfg.connectTimeoutMs}|${cfg.idleTimeoutMs}"
        synchronized(lock) {
            cached?.let { if (cachedKey == key) return it }
            val built = OkHttpClient.Builder()
                // 一跳转发：绝不跟随重定向（对齐 node 的 redirect:'manual'）
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(true)
                .connectTimeout(cfg.connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                // 读超时按"空闲"算：SSE 长时间没数据会触发，但不会因为整体耗时长而中断
                .readTimeout(cfg.idleTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .writeTimeout(cfg.idleTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .connectionPool(ConnectionPool(32, 60, TimeUnit.SECONDS))
                .build()
            cached = built
            cachedKey = key
            return built
        }
    }

    /** @return 客户端连接是否可以保持（keep-alive） */
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
        val hdrs = ProxyRules.forwardRequestHeaders(head.headers, head.header("x-upstream-auth"))

        // Content-Type 不能当普通头塞（OkHttp 会拒），改用 RequestBody.contentType 承载
        val ctHeader = hdrs.firstOrNull { it.name.equals("content-type", ignoreCase = true) }
        hdrs.removeAll { it.name.equals("content-type", ignoreCase = true) }
        val mediaType: MediaType? = ctHeader?.value?.toMediaTypeOrNull()

        if (hdrs.none { it.name.equals("accept-encoding", ignoreCase = true) }) {
            hdrs.add(Header("Accept-Encoding", "identity"))
        }

        if (cfg.userAgentOverride.isNotEmpty()) {
            hdrs.removeAll { it.name.equals("user-agent", ignoreCase = true) }
            hdrs.add(Header("User-Agent", cfg.userAgentOverride))
        }

        val rb = Request.Builder().url(target)
        for (h in hdrs) rb.addHeader(h.name, h.value)

        val method = head.method.uppercase()
        val needsBody = method in BODY_METHODS || body !is EmptyBody
        rb.method(method, if (needsBody) BodyRequestBody(body, mediaType) else null)

        val closeClient = !head.keepAliveHint

        client(cfg).newCall(rb.build()).execute().use { resp: Response ->
            val raw = ArrayList<Header>(resp.headers.size)
            for (i in 0 until resp.headers.size) {
                raw.add(Header(resp.headers.name(i), resp.headers.value(i)))
            }
            val (upFraming, rawCl, _) = Relay.upstreamFraming(raw, head.method, resp.code)
            // OkHttp 已把 chunked 解码成连续字节流，所以这里只可能是 LENGTH / CLOSE
            val readFraming = if (upFraming == Framing.CHUNKED) Framing.CLOSE else upFraming
            val downFraming = Relay.downstreamFraming(upFraming)
            val bodyLen = resp.body?.contentLength() ?: -1L

            // 声称的长度必须和实际读的字节数严格一致，否则客户端会挂住等剩余字节
            val headLen = when (downFraming) {
                Framing.LENGTH -> bodyLen
                Framing.NONE -> rawCl
                else -> -1L
            }

            responder.writeHead(
                status = resp.code,
                reason = resp.message,
                headers = ProxyRules.filterResponseHeaders(raw),
                framing = downFraming,
                contentLength = headLen,
                extra = cors,
                close = closeClient,
            )

            if (readFraming != Framing.NONE) {
                val stream = resp.body?.byteStream()
                if (stream != null) {
                    val reader = BufReader(stream)
                    Relay.pump(
                        src = reader,
                        dst = responder.out(),
                        upFraming = readFraming,
                        upLen = if (readFraming == Framing.LENGTH) bodyLen else -1L,
                        downFraming = downFraming,
                        flushEach = true,
                    ) { stats.addDown(it) }
                }
            }
        }
        return !closeClient
    }

    private val BODY_METHODS = setOf("POST", "PUT", "PATCH")

    /**
     * 直接把 [BodySource] 流式写进 OkHttp 的 sink。
     * isOneShot：socket 流式 body 只能读一次，禁止 OkHttp 重试时二次读取。
     */
    private class BodyRequestBody(
        private val src: BodySource,
        private val type: MediaType?,
    ) : RequestBody() {
        override fun contentType(): MediaType? = type
        override fun contentLength(): Long = src.contentLength ?: -1L
        override fun isOneShot(): Boolean = !src.replayable

        override fun writeTo(sink: BufferedSink) {
            val buffer = sink.buffer
            val os = object : OutputStream() {
                override fun write(b: Int) {
                    buffer.writeByte(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    buffer.write(b, off, len)
                }
            }
            src.copyPayloadTo(os)
            sink.flush()
        }
    }
}
