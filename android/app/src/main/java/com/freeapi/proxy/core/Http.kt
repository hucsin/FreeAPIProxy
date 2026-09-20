package com.freeapi.proxy.core

import java.io.IOException
import java.net.URLDecoder

/** 一个大小写不敏感、允许重名的头字段。 */
data class Header(val name: String, val value: String)

/** 解析后的请求行 + 头块。 */
data class RequestHead(
    val method: String,
    val target: String,
    val version: String,
    val headers: List<Header>,
) {
    val isConnect: Boolean get() = method.equals("CONNECT", ignoreCase = true)

    fun header(name: String): String? =
        headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

    fun hasHeader(name: String): Boolean =
        headers.any { it.name.equals(name, ignoreCase = true) }

    val keepAliveHint: Boolean
        get() {
            val conn = header("connection")?.lowercase().orEmpty()
            return if (version.equals("HTTP/1.0", ignoreCase = true)) {
                conn.contains("keep-alive")
            } else {
                !conn.contains("close")
            }
        }
}

object HttpHead {

    val CRLF_BYTES = "\r\n".toByteArray(Charsets.ISO_8859_1)
    const val CRLF = "\r\n"

    /**
     * 从连接读一个完整的请求头块。对端正常关闭返回 null，协议错误抛 IOException。
     * 请求行之前的空行按 RFC 7230 容忍（最多 4 个）。
     */
    @Throws(IOException::class)
    fun read(reader: BufReader): RequestHead? {
        var line = reader.readLine() ?: return null
        var guard = 0
        while (line.isEmpty()) {
            if (++guard > 4) return null
            line = reader.readLine() ?: return null
        }

        val sp1 = line.indexOf(' ')
        if (sp1 <= 0) throw IOException("bad request line: ${line.take(120)}")
        val sp2 = line.indexOf(' ', sp1 + 1)
        val method = line.substring(0, sp1)
        val target = if (sp2 > 0) line.substring(sp1 + 1, sp2) else line.substring(sp1 + 1)
        val version = if (sp2 > 0) line.substring(sp2 + 1).trim() else "HTTP/1.0"

        val headers = ArrayList<Header>(16)
        while (true) {
            val h = reader.readLine() ?: throw IOException("unexpected EOF inside headers")
            if (h.isEmpty()) break
            if (h[0] == ' ' || h[0] == '\t') {
                // obs-fold 续行：按 RFC 7230 可拒绝，这里直接忽略以免解析错位
                continue
            }
            val i = h.indexOf(':')
            if (i <= 0) continue
            headers.add(Header(h.substring(0, i).trim(), h.substring(i + 1).trim()))
        }
        return RequestHead(method, target, version, headers)
    }
}

/**
 * 全部转发规则集中在这里，逐条对齐 node-server.js（并在少数地方做了修正，见注释）。
 */
object ProxyRules {

    /**
     * 请求侧要剥掉的代理内部头 / 隐私特征头。
     * 对应 node 的 BLOCKED 集合，外加 Go 版补上的 `x-proxy-mode`——node 会把
     * 这个内部头一路透给上游，属于泄漏，这里按 Go 的做法一并剥掉。
     */
    val BLOCKED_REQUEST = setOf(
        "x-forwarded-for", "x-forwarded-proto", "x-forwarded-host", "x-real-ip",
        "true-client-ip", "forwarded", "via", "proxy-authorization",
        "x-proxy-token", "x-forward-target", "x-upstream-auth", "x-proxy-mode",
    )

    /**
     * hop-by-hop 头：请求侧必须剥掉（因为我们重打分帧），响应侧也必须剥掉。
     * 对应 node 的 RESP_HOP。
     */
    val HOP_BY_HOP = setOf(
        "connection", "keep-alive", "transfer-encoding", "upgrade", "trailer",
        "trailers", "te", "expect", "proxy-connection",
    )

    /** 与 node/Go 完全一致的强制 fetch 名单；带前导 . 表示后缀匹配。 */
    val FORCE_FETCH_HOSTS = listOf(
        "api.openai.com",
        "api.anthropic.com",
        ".anthropic.com",
        ".claude.ai",
        ".openai.com",
        "api.cline.bot",
        ".cline.bot",
        "opencode.ai",
        ".opencode.ai",
    )

    fun shouldForceFetch(host: String?, mode: String): Boolean {
        if (mode == com.freeapi.proxy.ProxyConfig.MODE_FETCH) return true
        val h = host.orEmpty().substringBefore(':').lowercase()
        if (h.isEmpty()) return false
        for (d in FORCE_FETCH_HOSTS) {
            if (d.startsWith(".")) {
                if (h.endsWith(d)) return true
            } else if (h == d) {
                return true
            }
        }
        return false
    }

    /** 解析 Connection 头点名的头字段（这些也必须当成 hop-by-hop 剥掉）。 */
    fun connectionTokens(headers: List<Header>): Set<String> {
        val out = HashSet<String>(4)
        for (h in headers) {
            if (!h.name.equals("connection", ignoreCase = true)) continue
            for (c in h.value.split(',')) {
                val t = c.trim().lowercase()
                if (t.isNotEmpty()) out.add(t)
            }
        }
        return out
    }

    /**
     * 构造发往上游的请求头：剥内部头、cf- 特征头、hop-by-hop、Connection 点名的头；
     * Host / Content-Length / Transfer-Encoding 由分帧逻辑自己决定，这里一并剥掉。
     * Authorization 缺失时注入 X-Upstream-Auth 的值。
     */
    fun forwardRequestHeaders(headers: List<Header>, upstreamAuth: String?): MutableList<Header> {
        val conn = connectionTokens(headers)
        val out = ArrayList<Header>(headers.size + 4)
        for (h in headers) {
            val lk = h.name.lowercase()
            if (lk in BLOCKED_REQUEST) continue
            if (lk in HOP_BY_HOP) continue
            if (lk in conn) continue
            if (lk.startsWith("cf-") || lk.startsWith("cf_")) continue
            if (lk == "host" || lk == "content-length") continue
            out.add(h)
        }
        if (!upstreamAuth.isNullOrEmpty() &&
            out.none { it.name.equals("authorization", ignoreCase = true) }
        ) {
            out.add(Header("Authorization", upstreamAuth))
        }
        return out
    }

    /**
     * 过滤上游响应头：剥 hop-by-hop + Connection 点名的 + proxy-* + via。
     * content-length 也剥掉——由下游分帧逻辑重新决定（上游 chunked 时长度根本不存在）。
     */
    fun filterResponseHeaders(headers: List<Header>): MutableList<Header> {
        val conn = connectionTokens(headers)
        val out = ArrayList<Header>(headers.size)
        for (h in headers) {
            val lk = h.name.lowercase()
            if (lk in HOP_BY_HOP) continue
            if (lk in conn) continue
            if (lk.startsWith("proxy-") || lk == "via") continue
            if (lk == "content-length") continue
            out.add(h)
        }
        return out
    }

    /**
     * CORS：仅在请求带 Origin（真实浏览器跨域）时才回，回声 Origin 并允许携带凭据；
     * 服务端到服务端（无 Origin）保持响应干净——与 node 的 corsFor 一致。
     */
    fun corsHeaders(origin: String?, requestedHeaders: String?): MutableList<Header> {
        if (origin.isNullOrEmpty()) return ArrayList(0)
        return arrayListOf(
            Header("Access-Control-Allow-Origin", origin),
            Header("Vary", "Origin"),
            Header("Access-Control-Allow-Credentials", "true"),
            Header(
                "Access-Control-Allow-Headers",
                requestedHeaders?.takeIf { it.isNotEmpty() }
                    ?: "Content-Type,Authorization,X-Proxy-Token,X-Forward-Target,X-Proxy-Mode,Range"
            ),
            Header("Access-Control-Allow-Methods", "GET,HEAD,POST,PUT,PATCH,DELETE,OPTIONS"),
            Header("Access-Control-Max-Age", "86400"),
        )
    }

    /**
     * 提取代理自身鉴权 token：X-Proxy-Token > Authorization: Bearer > ?token=
     * 与 node 的 requestToken 三通道一致。
     */
    fun requestToken(head: RequestHead): String {
        head.header("x-proxy-token")?.takeIf { it.isNotEmpty() }?.let { return it }
        val a = head.header("authorization").orEmpty()
        if (a.startsWith("Bearer ")) return a.substring(7)
        return queryParam(head.target, "token")
    }

    /**
     * 从 `Proxy-Authorization` 提取 token。**仅 CONNECT 隧道用**。
     *
     * 为什么需要它：curl 的 `-H` 不会带到 CONNECT 请求上（只有 `--proxy-header` 才会），
     * 而浏览器 / Android 系统代理只会发 `Proxy-Authorization`。node 版只认
     * `X-Proxy-Token`，导致隧道在真实浏览器场景里根本没法鉴权——这里补上这个标准通道。
     * 支持三种写法：`Bearer <token>`、`Basic base64(user:token)`、`Basic base64(token)`。
     * 注意这只是**多一个 token 出处**，校验强度不变；普通请求仍严格对齐 node（不认这个头）。
     */
    fun proxyAuthToken(head: RequestHead): String {
        val pa = head.header("proxy-authorization").orEmpty().trim()
        if (pa.isEmpty()) return ""
        if (pa.startsWith("Bearer ", ignoreCase = true)) return pa.substring(7).trim()
        if (pa.startsWith("Basic ", ignoreCase = true)) {
            val raw = try {
                String(java.util.Base64.getDecoder().decode(pa.substring(6).trim()), Charsets.UTF_8)
            } catch (t: Throwable) {
                return ""
            }
            val idx = raw.indexOf(':')
            return (if (idx >= 0) raw.substring(idx + 1) else raw).trim()
        }
        return ""
    }

    fun authOk(head: RequestHead, token: String, allowOpen: Boolean): Boolean {
        if (token.isEmpty()) return allowOpen
        return constantTimeEquals(requestToken(head), token)
    }

    /** CONNECT 隧道的鉴权：额外接受 Proxy-Authorization（浏览器 / 系统代理的标准姿势）。 */
    fun connectAuthOk(head: RequestHead, token: String, allowOpen: Boolean): Boolean {
        if (token.isEmpty()) return allowOpen
        val t = requestToken(head).ifEmpty { proxyAuthToken(head) }
        return constantTimeEquals(t, token)
    }

    /** 定长比较，避免 token 逐字节比较时的时序侧信道。 */
    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var d = 0
        for (i in a.indices) d = d or (a[i].code xor b[i].code)
        return d == 0
    }

    private fun queryParam(target: String, key: String): String {
        val qi = target.indexOf('?')
        if (qi < 0) return ""
        val q = target.substring(qi + 1).substringBefore('#')
        for (part in q.split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            if (part.substring(0, eq) == key) {
                return try {
                    URLDecoder.decode(part.substring(eq + 1), "UTF-8")
                } catch (t: Throwable) {
                    part.substring(eq + 1)
                }
            }
        }
        return ""
    }
}
