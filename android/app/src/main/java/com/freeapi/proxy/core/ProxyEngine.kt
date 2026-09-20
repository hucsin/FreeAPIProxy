package com.freeapi.proxy.core

import com.freeapi.proxy.ProxyConfig
import java.io.BufferedOutputStream
import java.net.URL
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 代理服务主引擎：监听 + 并发调度 + 路由，逻辑对应 node 的
 * `http.createServer((req,res)=>{…})` 那一段。
 *
 * 并发模型：每条连接交给线程池，accept 由「每个监听器一个线程」负责。
 * 池用 core=0 / max=N / SynchronousQueue，一旦排队即拒绝并回 503——
 * 宁可明确拒绝，也不让线程数无限膨胀把手机拖死。
 *
 * ## 多监听器
 *
 * 引擎同时监听多个来源：至少一个系统 socket（[SysListener]，覆盖内核所有接口），
 * 以及可选的 ZeroTier 虚拟网络（由 [listenerFactory] 提供，见 `zerotier.ZtListener`）。
 *
 * 两者绑的是**完全不同的协议栈**（内核 vs lwIP 用户态），所以可以同端口共存，
 * 互不冲突。
 *
 * 特别处理：**额外监听器创建失败不能拖垮整个引擎**。ZeroTier 的网络可能尚未就绪、
 * 虚拟 IP 还没分配，此时只记日志跳过；系统监听器照常服务。
 */
class ProxyEngine(
    @Volatile var config: ProxyConfig,
    private val stats: ProxyStats,
    private val log: ProxyLog,
    /**
     * 额外入站监听器的工厂。返回空列表表示当前无需额外监听。
     * 传入配置便于工厂按端口/开关决定是否创建。
     */
    private val listenerFactory: ((ProxyConfig) -> List<InboundListener>)? = null,
) {

    private val listeners = ArrayList<InboundListener>()
    private val acceptThreads = ArrayList<Thread>()
    private var pool: ThreadPoolExecutor? = null
    private val live = LiveSockets()

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    /** 当前生效的监听来源，如 ["socket", "zerotier"]。UI 用来显示"哪些入口活着"。 */
    @Synchronized
    fun activeListeners(): List<String> = listeners.map { it.label }

    /** 活动连接数（含上游 socket 与入站连接） */
    val liveCount: Int get() = live.size

    @Synchronized
    fun start(): String? {
        if (running) return null
        val cfg = config

        // 1) 系统监听器：绑不上就直接失败，这是主入口
        val sys = try {
            SysListener(cfg.listenPort)
        } catch (t: Throwable) {
            val msg = "监听 ${cfg.listenPort} 失败：${t.message ?: t.javaClass.simpleName}"
            lastError = msg
            log.error(msg)
            return msg
        }

        pool = ThreadPoolExecutor(
            0, cfg.concurrency, 60L, TimeUnit.SECONDS, SynchronousQueue(),
            { r -> Thread(r, "proxy-conn").apply { isDaemon = true } },
        ).apply { allowCoreThreadTimeOut(true) }

        stats.onStart()
        lastError = null
        running = true

        attach(sys)

        // 2) 额外监听器（ZeroTier）。失败只记日志，不影响主入口。
        if (listenerFactory != null) {
            val extra = try {
                listenerFactory.invoke(cfg)
            } catch (t: Throwable) {
                log.warn("额外监听器创建失败：${t.javaClass.simpleName}: ${t.message}")
                emptyList()
            }
            for (l in extra) attachSafely(l)
        }

        log.info(
            "已启动 · 监听 0.0.0.0:${cfg.listenPort}" +
                " · 入口 ${activeListeners().joinToString("+")}" +
                " · 模式 ${cfg.mode} · 并发上限 ${cfg.concurrency}"
        )
        if (cfg.token.isEmpty()) {
            if (cfg.allowOpen) {
                log.warn("未设置 Token 且已开启「允许无鉴权」——任何人都能借用这条出口")
            } else {
                log.warn("未设置 Token，裸奔默认禁止：所有请求都会返回 401")
            }
        }
        return null
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        for (l in listeners) {
            runCatching { l.close() }
        }
        listeners.clear()
        acceptThreads.clear()
        live.closeAll()
        pool?.let {
            it.shutdown()
            runCatching { it.awaitTermination(2, TimeUnit.SECONDS) }
            it.shutdownNow()
        }
        pool = null
        stats.onStop()
        log.info("已停止")
    }

    /**
     * 运行期挂上一个额外监听器（例如 ZeroTier 网络刚就绪）。
     * @return 是否成功挂上（标签订已存在则视为成功，不重复挂）
     */
    @Synchronized
    fun addListener(l: InboundListener): Boolean {
        if (!running) return false
        if (listeners.any { it.label == l.label }) return false
        return attachSafely(l)
    }

    /** 运行期摘掉一个额外监听器（例如离开 ZeroTier 网络）。系统监听器不受影响。 */
    @Synchronized
    fun removeListener(label: String): Boolean {
        if (label == SysListener.LABEL) return false
        val target = listeners.firstOrNull { it.label == label } ?: return false
        listeners.remove(target)
        runCatching { target.close() }
        log.info("已摘除监听入口：$label")
        return true
    }

    /** 监听参数变了就重启监听；只改 token / 超时之类的无需重启。 */
    @Synchronized
    fun applyConfig(next: ProxyConfig): Boolean {
        val needRestart = running && !config.sameListenerAs(next)
        config = next
        if (!needRestart) return false
        stop()
        start()
        return true
    }

    fun snapshot(startedAtFallback: Long = 0L) = stats.snapshot(running, startedAtFallback)

    // -----------------------------------------------------------------------
    // 内部
    // -----------------------------------------------------------------------

    private fun attachSafely(l: InboundListener): Boolean {
        listeners.add(l)
        val t = Thread({ acceptLoop(l) }, "proxy-accept-${l.label}").apply {
            isDaemon = true
            start()
        }
        acceptThreads.add(t)
        log.info("已挂载监听入口：${l.label}")
        return true
    }

    private fun attach(l: InboundListener) {
        listeners.add(l)
        val t = Thread({ acceptLoop(l) }, "proxy-accept-${l.label}").apply {
            isDaemon = true
            start()
        }
        acceptThreads.add(t)
    }

    private fun acceptLoop(listener: InboundListener) {
        while (running) {
            val conn = try {
                listener.accept() ?: break
            } catch (t: Throwable) {
                if (running) log.error("accept 异常(${listener.label})：${t.message}")
                break
            }
            val p = pool
            if (p == null) {
                conn.close()
                continue
            }
            try {
                p.execute { serve(conn) }
            } catch (_: Throwable) {
                // 并发已满：明确拒绝而不是排队堆积
                runCatching {
                    val o = conn.output
                    o.write(
                        ("HTTP/1.1 503 Service Unavailable\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: 71\r\n" +
                            "Connection: close\r\n\r\n" +
                            "{\"error\":{\"message\":\"proxy concurrency limit reached\"," +
                            "\"type\":\"proxy_error\"}}").toByteArray(Charsets.UTF_8)
                    )
                    o.flush()
                }
                conn.close()
            }
        }
    }

    private fun serve(conn: InboundConn) {
        live.add(conn)
        stats.connectionOpened(conn.source)
        val peer = conn.remoteLabel
        // ZeroTier 入站是"虚拟网这条路通不通"的唯一硬证据，单独留一行——
        // 界面上的「最近入站」就靠它，排查时也能直接从 logcat 看到时刻。
        if (conn.source == InboundConn.Source.ZEROTIER) {
            log.info("ZeroTier 入站连接已建立 · $peer")
        }
        try {
            val reader = BufReader(conn.input)
            val out = BufferedOutputStream(conn.output, 16 * 1024)
            val responder = ClientResponder(out)

            var keepAlive = true
            while (keepAlive && running) {
                // ZeroTier 侧这是 no-op：它不支持 SO_RCVTIMEO，
                // 设了会把空闲误判成 EOF。见 InboundConn 类注释。
                conn.setReadTimeout(HEADER_TIMEOUT_MS)
                val head = try {
                    HttpHead.read(reader)
                } catch (t: Throwable) {
                    if (running) log.warn("请求解析失败($peer)：${t.message}")
                    null
                } ?: break
                keepAlive = handleOne(head, reader, responder, conn, peer)
            }
        } catch (t: Throwable) {
            if (running) log.warn("连接异常($peer)：${t.javaClass.simpleName}: ${t.message}")
        } finally {
            conn.close()
            live.remove(conn)
            stats.connectionClosed()
        }
    }

    /** @return 客户端连接是否继续保持 */
    private fun handleOne(
        head: RequestHead,
        reader: BufReader,
        responder: ClientResponder,
        conn: InboundConn,
        peer: String,
    ): Boolean {
        val cfg = config

        // 1) OPTIONS 预检：鉴权之前就放行（与 node 顺序一致）
        if (head.method.equals("OPTIONS", ignoreCase = true)) {
            val cors = ProxyRules.corsHeaders(
                head.header("origin"),
                head.header("access-control-request-headers"),
            )
            responder.writeHead(204, "No Content", emptyList(), Framing.NONE, -1L, cors, close = false)
            return head.keepAliveHint
        }

        // 2) CONNECT 隧道（鉴权只用头，因为目标不是 URL，没有 ?token= 可用）
        if (head.isConnect) {
            if (!ProxyRules.connectAuthOk(head, cfg.token, cfg.allowOpen)) {
                stats.authRejectedCount()
                log.warn("CONNECT 鉴权失败 $peer → ${head.target.take(80)}")
                responder.out().write(
                    "HTTP/1.1 407 Proxy Authentication Required\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray(Charsets.ISO_8859_1)
                )
                responder.out().flush()
                return false
            }
            Tunnel.handle(cfg, head, conn, reader, responder, live, stats, log)
            return false
        }

        // 3) 鉴权
        if (!ProxyRules.authOk(head, cfg.token, cfg.allowOpen)) {
            stats.authRejectedCount()
            log.warn("鉴权失败 $peer → ${head.target.take(80)}")
            responder.writeError(401, "invalid or missing proxy token")
            return false
        }

        // 4) 解析目标：X-Forward-Target > 绝对 URI > 基于 Host 的相对路径
        val target = try {
            resolveTarget(head)
        } catch (t: Throwable) {
            responder.writeError(400, t.message ?: "invalid target url")
            return false
        }

        val cors = ProxyRules.corsHeaders(
            head.header("origin"),
            head.header("access-control-request-headers"),
        )

        // 5) 读请求体（含 Expect: 100-continue 处理）
        val body = try {
            BodyReader.read(head, reader, responder.out())
        } catch (t: Throwable) {
            log.warn("读取请求体失败($peer)：${t.message}")
            return false
        }

        val mode = head.header("x-proxy-mode")?.takeIf { it in ProxyConfig.MODES } ?: cfg.mode
        val startedAt = System.currentTimeMillis()
        var keepAlive: Boolean

        try {
            stats.requestOk()
            val forceFetch = ProxyRules.shouldForceFetch(target.host, mode)
            var via = "fetch"
            if (forceFetch) {
                keepAlive = FetchForwarder.forward(cfg, head, target, body, responder, cors, stats)
                stats.fetchHit()
            } else {
                via = "socket"
                try {
                    keepAlive = RawForwarder.forward(cfg, head, target, body, responder, cors, stats)
                    stats.socketHit()
                } catch (e: PreResponseFailure) {
                    if (!body.replayable) throw e
                    stats.failover()
                    log.warn("socket 直连失败 → 降级 fetch：${target.host} · ${e.message}")
                    keepAlive = FetchForwarder.forward(cfg, head, target, body, responder, cors, stats)
                    stats.fetchHit()
                    via = "socket→fetch"
                }
            }
            log.traffic(
                "${head.method} ${target.host}${target.path} → ${responder.status} " +
                    "· ${System.currentTimeMillis() - startedAt}ms · $via" +
                    if (conn.source == InboundConn.Source.ZEROTIER) " · zt" else ""
            )
        } catch (t: Throwable) {
            val why = t.message ?: t.javaClass.simpleName
            stats.markError("${target.host}: $why")
            log.error("转发失败 ${head.method} ${target.host}：$why")
            responder.writeError(502, "upstream failed: $why")
            keepAlive = false
        }

        return keepAlive && running
    }

    @Throws(IllegalArgumentException::class)
    private fun resolveTarget(head: RequestHead): URL {
        val fwd = head.header("x-forward-target")
        val raw = when {
            !fwd.isNullOrEmpty() -> fwd

            head.target.startsWith("http://", ignoreCase = true) ||
                head.target.startsWith("https://", ignoreCase = true) -> head.target

            else -> {
                val host = head.header("host") ?: "localhost"
                val path = if (head.target.startsWith("/")) head.target else "/" + head.target
                "http://$host$path"
            }
        }

        val u = try {
            URL(raw.substringBefore('#'))
        } catch (t: Throwable) {
            throw IllegalArgumentException("invalid target url")
        }
        val proto = u.protocol.lowercase()
        if (proto != "http" && proto != "https") {
            throw IllegalArgumentException("unsupported target protocol: $proto")
        }
        if (u.host.isNullOrEmpty()) throw IllegalArgumentException("invalid target url")
        return u
    }

    companion object {
        /** 空闲客户端连接等请求行的超时；keep-alive 复用期间也用它兜底。 */
        const val HEADER_TIMEOUT_MS = 30_000
    }
}
