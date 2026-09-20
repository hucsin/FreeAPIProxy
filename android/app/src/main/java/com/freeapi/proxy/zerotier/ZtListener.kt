package com.freeapi.proxy.zerotier

import com.freeapi.proxy.core.InboundConn
import com.freeapi.proxy.core.InboundListener
import com.zerotier.sockets.ZeroTierNative
import com.zerotier.sockets.ZeroTierSocket
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * 监听在 ZeroTier 虚拟网络上的入站监听器（libzt / lwIP 用户态协议栈）。
 *
 * 与 [com.freeapi.proxy.core.SysListener] 的差异全都来自 libzt 的实现细节。
 *
 * ### 0. 为什么不用 `ZeroTierServerSocket`（重要）
 *
 * libzt 提供了 `ZeroTierServerSocket`，看起来是现成的服务端封装，但它有个
 * **对 IPv4 必然抛异常**的设计缺陷：
 *
 * ```java
 * // ZeroTierServerSocket 的所有构造函数都是这么建 socket 的
 * _socket = new ZeroTierSocket(ZTS_AF_INET6, ZTS_SOCK_STREAM, 0);
 * _socket.bind(localAddr.getHostAddress(), localPort);   // ← 传 IPv4 就炸
 *
 * // 而 ZeroTierSocket.bind 里有这段类型校验
 * if ((localAddr instanceof Inet4Address) && _family != ZTS_AF_INET) {
 *     throw new IOException("Invalid address type. Socket is of type AF_INET");
 * }
 * ```
 *
 * socket 建成 `AF_INET6`，却要绑 IPv4 字面量 → 立即抛 IOException。
 * ZeroTier 网络分配的绝大多数是 IPv4，所以这条路是死的。
 *
 * **绕法**：不用 `ZeroTierServerSocket`，直接用 `ZeroTierSocket` 显式建一个
 * `AF_INET` 的流式 socket，再自己 `bind` + `listen` + `accept`。
 *
 * ### 1. 绑通配地址（`0.0.0.0`）而不是具体虚拟 IP
 *
 * 绑具体虚拟 IP 有两个问题：① 要等网络就绪、虚拟 IP 分配后才能绑，天然有延迟；
 * ② 网络重建后 IP 可能变，监听器就指向了一个失效地址。
 * 绑 `0.0.0.0` 覆盖 lwIP 上**当前和将来**的所有地址，因此：
 * **节点一起来就能挂上监听，不必等网络就绪**。
 *
 * ### 2. 绝不设读超时（硬约束）
 *
 * `ZeroTierInputStream.read(byte[],int,int)` 的实现：
 * ```java
 * int retval = ZeroTierNative.zts_bsd_read_offset(zfd, destBuffer, offset, numBytes);
 * if ((retval == 0) | (retval == -104) /* EINTR, from SO_RCVTIMEO */) {
 *     return -1;
 * }
 * ```
 * `-104` 是 `EINTR`（由 `SO_RCVTIMEO` 超时产生），却被和 `0`（真 EOF）一起
 * 归成 `-1`。而 `-1` 在 `InputStream` 契约里表示**对端关闭**——于是
 * 一次正常的空闲超时会被 `Relay.pump()` 判定成「对端断开」，
 * 把健康的 keep-alive / SSE 长连接掐掉。
 *
 * 所以本类给连接的 `timeoutSetter` 传 **null**（[InboundConn.setReadTimeout] 是 no-op），
 * 空闲治理交给 [reapLoop]。
 *
 * ### 3. 只能走 `read(byte[],int,int)`
 *
 * `ZeroTierInputStream.read()` 返回 `buf[0]`，而 Java 的 `byte` 有符号，
 * 读到 `0x80`–`0xFF`（UTF-8 中文、二进制体里到处都是）会返回**负数**，
 * 违反 `InputStream.read()` 必须返回 0–255 或 -1 的契约。
 * 本项目一律用带 offset 的 `read(byte[],int,int)`，[com.freeapi.proxy.core.BufReader]
 * 走的正是该重载，天然安全。
 */
class ZtListener(
    port: Int,
    /** 绑定的本端地址；null 表示绑 `0.0.0.0`（推荐） */
    bindAddr: InetAddress? = null,
    /** 该监听器服务的网络 ID（仅用于日志/展示） */
    private val netIdLabel: String = "",
    backlog: Int = 64,
    /** 空闲多久回收连接。因为不能设读超时，只能靠这个兜底防线程泄漏。 */
    private val idleReapMs: Long = DEFAULT_IDLE_REAP_MS,
) : InboundListener {

    override val label: String = LABEL

    /** 实际绑定到的地址（用于日志与 UI 展示） */
    val boundAddress: String

    private val server: ZeroTierSocket
    private val live = ConcurrentHashMap.newKeySet<InboundConn>()

    @Volatile
    private var closed = false

    @Volatile
    private var reaper: Thread? = null

    /** 被空闲回收掉的连接数，用于排查 */
    @Volatile
    var connsReaped: Int = 0
        private set

    init {
        val target = bindAddr ?: InetAddress.getByName(WILDCARD)
        // 显式 AF_INET：绕开 ZeroTierServerSocket 的 AF_INET6/IPv4 类型冲突
        val s = ZeroTierSocket(
            ZeroTierNative.ZTS_AF_INET,
            ZeroTierNative.ZTS_SOCK_STREAM,
            0,
        )
        try {
            runCatching { s.setReuseAddress(true) }
            s.bind(target, port)
            s.listen(backlog)
        } catch (t: Throwable) {
            runCatching { s.close() }
            throw IOException(
                "ZeroTier 监听器绑定 ${target.hostAddress}:$port 失败：${t.message}", t
            )
        }
        server = s
        boundAddress = "${target.hostAddress}:$port"

        reaper = Thread({ reapLoop() }, "zt-reaper").apply {
            isDaemon = true
            start()
        }
    }

    @Throws(IOException::class)
    override fun accept(): InboundConn? {
        val client: ZeroTierSocket = try {
            server.accept()
        } catch (t: Throwable) {
            if (closed) return null
            throw t
        }

        val conn = try {
            InboundConn(
                rawInput = client.getInputStream(),
                rawOutput = BufferedOutputStream(client.getOutputStream(), OUT_BUF),
                remoteLabel = remoteLabelOf(client),
                source = InboundConn.Source.ZEROTIER,
                closer = { runCatching { client.close() } },
                // ZeroTierSocket 侧对应 zts_bsd_shutdown(fd, ZTS_SHUT_WR)
                halfCloser = { client.shutdownOutput() },
                // ★ 必须为 null：不支持 SO_RCVTIMEO，理由见类注释第 2 条
                timeoutSetter = null,
            )
        } catch (t: Throwable) {
            runCatching { client.close() }
            throw t
        }

        live.add(conn)
        return conn
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        reaper?.interrupt()
        reaper = null
        for (c in live) {
            runCatching { c.close() }
        }
        live.clear()
    }

    /** 当前活动连接数（UI 展示用） */
    val connectionCount: Int get() = live.size

    private fun remoteLabelOf(s: ZeroTierSocket): String = runCatching {
        val ip = s.getRemoteAddress()?.hostAddress ?: "?"
        "zt:$ip:${s.getRemotePort()}"
    }.getOrDefault("zt:?")

    /**
     * 空闲回收。
     *
     * ZeroTier 连接没有 socket 读超时，一条对端静默消失（休眠、掉网）的连接会
     * 永久阻塞在 read 上、占住线程池的一个线程；这里做兜底清理。
     *
     * 判据用 [InboundConn.lastActivityMs]（**读写都刷新**），所以
     * 「服务端持续推送、客户端几乎不发数据」的 SSE 长连接不会被误杀。
     */
    private fun reapLoop() {
        while (!closed) {
            try {
                Thread.sleep(REAP_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
            if (closed) return
            val now = System.currentTimeMillis()
            for (c in live) {
                if (now - c.lastActivityMs > idleReapMs) {
                    connsReaped++
                    runCatching { c.close() }
                    live.remove(c)
                }
            }
        }
    }

    companion object {
        const val LABEL = "zerotier"

        private const val WILDCARD = "0.0.0.0"
        private const val OUT_BUF = 16 * 1024
        private const val DEFAULT_IDLE_REAP_MS = 10 * 60_000L
        private const val REAP_INTERVAL_MS = 30_000L
    }
}
