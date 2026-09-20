package com.freeapi.proxy.core

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * 单个入站连接的抽象，用来屏蔽 `java.net.Socket` 与 libzt `ZeroTierSocket` 的差异。
 *
 * 为什么需要它——两者有三个不可忽视的不同点：
 *
 * 1. **没有公共父类**。`ZeroTierSocket` 不是 `java.net.Socket` 的子类（源码里
 *    就一行 `public class ZeroTierSocket`，不 extends 也不 implements），
 *    所以任何接受 `Socket` 的函数都收不下它。
 * 2. **`setSoTimeout` 语义有毒**。`ZeroTierInputStream.read()` 里
 *    `retval == -104`（EINTR，由 `SO_RCVTIMEO` 超时产生）会被直接当作 `-1` 返回，
 *    而 `InputStream` 契约里 `-1` 表示 EOF。也就是说，一旦给 ZeroTier 侧设了读超时，
 *    一次正常的空闲超时会被上层误判成「对端断开」，把健康的 keep-alive / SSE
 *    长连接掐掉。因此 ZT 侧的 [setReadTimeout] 必须是 no-op，这里用可空的
 *    `timeoutSetter` 表达「不支持」而不是「设了也没用」。
 * 3. **半关闭能力不同**。系统 socket 有 `shutdownOutput()`，ZT 侧对应
 *    `zts_bsd_shutdown(fd, ZTS_SHUT_WR)`，包装成同一个 [shutdownOutput] 入口。
 *
 * 另外这里顺带做了一件保活相关的事：包一层输入/输出流记录**最近一次进出的时刻**
 * （[lastActivityMs]）。因为 ZT 侧不能设读超时，一条半死不活的连接会永久占住
 * 线程池里的一个线程；[ZtListener] 靠这个时间戳做空闲回收。
 *
 * 注意是**读写都记**，不能只记读：SSE 这种服务端持续推送、客户端几乎不发数据的场景，
 * 只看读会把一条正在正常工作的长连接判成空闲掐掉。
 */
class InboundConn(
    rawInput: InputStream,
    rawOutput: OutputStream,
    /** 日志用的来源标识，例如 `192.168.1.5:51234` 或 `zt:10.147.20.5:51234` */
    val remoteLabel: String,
    val source: Source,
    private val closer: () -> Unit,
    private val halfCloser: () -> Unit = {},
    /** null 表示该连接类型不支持读超时（ZeroTier 侧即如此） */
    private val timeoutSetter: ((Int) -> Unit)? = null,
) : Closeable {

    enum class Source { SYS, ZEROTIER }

    /** 是否支持 socket 级读超时 */
    val supportsReadTimeout: Boolean get() = timeoutSetter != null

    @Volatile
    var lastActivityMs: Long = System.currentTimeMillis()
        private set

    private fun touch() {
        lastActivityMs = System.currentTimeMillis()
    }

    /** 包一层，只为了在读到数据时刷新活跃时间 */
    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val v = rawInput.read()
            if (v >= 0) touch()
            return v
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = rawInput.read(b, off, len)
            if (n > 0) touch()
            return n
        }

        override fun available(): Int = rawInput.available()
        override fun close() = rawInput.close()
    }

    /** 同理包一层输出流：写出去也算活跃，避免误杀正在推送的 SSE 长连接 */
    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            rawOutput.write(b)
            touch()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            rawOutput.write(b, off, len)
            if (len > 0) touch()
        }

        override fun flush() = rawOutput.flush()
        override fun close() = rawOutput.close()
    }

    /**
     * 设置读超时。对不支持的类型（ZeroTier）静默忽略——
     * 超时控制交给应用层，见类注释第 2 条。
     */
    fun setReadTimeout(ms: Int) {
        timeoutSetter?.invoke(ms)
    }

    fun shutdownOutput() {
        runCatching { halfCloser() }
    }

    override fun close() {
        runCatching { closer() }
    }

    override fun toString(): String = "${source.name.lowercase()}://$remoteLabel"
}

/**
 * 入站监听器。每种监听来源（系统 socket / ZeroTier 虚拟网）实现一个。
 *
 * [accept] 阻塞等待；返回 null 表示监听器已被 [close]，接受循环应当退出。
 */
interface InboundListener {
    /** 日志与去重用的短名，如 `socket` / `zerotier` */
    val label: String

    @Throws(IOException::class)
    fun accept(): InboundConn?

    fun close()
}

/**
 * 基于 `java.net.ServerSocket` 的监听器——即原有行为，覆盖
 * WiFi / 局域网 / USB 转发 / 官方 ZeroTier 客户端建出的 TUN 网卡等所有内核接口。
 */
class SysListener(
    port: Int,
    backlog: Int = 256,
) : InboundListener {

    override val label: String = LABEL

    private val server: ServerSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(port), backlog)
    }

    @Volatile
    private var closed = false

    override fun accept(): InboundConn? {
        val client = try {
            server.accept()
        } catch (t: Throwable) {
            // 关闭监听时会走到这里（accept 抛 SocketException），属正常路径
            if (closed) return null
            throw t
        }
        return try {
            client.tcpNoDelay = true
            InboundConn(
                rawInput = client.getInputStream(),
                rawOutput = BufferedOutputStream(client.getOutputStream(), OUT_BUF),
                remoteLabel = runCatching {
                    client.inetAddress?.hostAddress + ":" + client.port
                }.getOrDefault("-"),
                source = InboundConn.Source.SYS,
                closer = { closeQuietly(client) },
                halfCloser = { client.shutdownOutput() },
                // 系统 socket 支持读超时：空闲连接会被 SO_RCVTIMEO 唤醒并断开
                timeoutSetter = { ms -> runCatching { client.soTimeout = ms } },
            )
        } catch (t: Throwable) {
            closeQuietly(client)
            throw t
        }
    }

    override fun close() {
        closed = true
        closeQuietly(server)
    }

    companion object {
        const val LABEL = "socket"
        private const val OUT_BUF = 16 * 1024
    }
}
