package com.freeapi.proxy.core

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** 运行统计。全部原子量，任意线程可写，UI 直接读快照。 */
class ProxyStats {

    data class Snapshot(
        val running: Boolean,
        val startedAt: Long,
        val uptimeMs: Long,
        val active: Int,
        val peakActive: Int,
        val totalConnections: Long,
        val requests: Long,
        val failed: Long,
        val authRejected: Long,
        val tunnels: Long,
        val socketHits: Long,
        val fetchHits: Long,
        val failovers: Long,
        val bytesUp: Long,
        val bytesDown: Long,
        val lastError: String?,
        /**
         * 从 ZeroTier 虚拟网进来的连接数。
         *
         * 存在的意义是**把「路径没通」和「鉴权拒了」区分开**：两者在界面上
         * 的表现都是「对端报错」，但一个要从网络层查（节点授权 / 路径重建），
         * 一个只要去配置页填 Token。没有这个计数就只能靠猜。
         */
        val ztConnections: Long,
        /** 最近一次 ZeroTier 入站连接的时刻（0 = 从未收到） */
        val ztLastAtMs: Long,
    )

    private val startedAt = AtomicLong(0L)
    private val active = AtomicInteger(0)
    private val peakActive = AtomicInteger(0)
    private val totalConnections = AtomicLong(0)
    private val requests = AtomicLong(0)
    private val failed = AtomicLong(0)
    private val authRejected = AtomicLong(0)
    private val tunnels = AtomicLong(0)
    private val socketHits = AtomicLong(0)
    private val fetchHits = AtomicLong(0)
    private val failovers = AtomicLong(0)
    private val bytesUp = AtomicLong(0)
    private val bytesDown = AtomicLong(0)
    private val lastError = AtomicReference<String?>(null)
    private val ztConnections = AtomicLong(0)
    private val ztLastAtMs = AtomicLong(0)

    fun onStart() {
        startedAt.set(System.currentTimeMillis())
        peakActive.set(0)
        lastError.set(null)
    }

    fun onStop() {
        startedAt.set(0L)
    }

    fun reset() {
        requests.set(0); failed.set(0); authRejected.set(0); tunnels.set(0)
        socketHits.set(0); fetchHits.set(0); failovers.set(0)
        bytesUp.set(0); bytesDown.set(0); totalConnections.set(0)
        peakActive.set(0); lastError.set(null)
        ztConnections.set(0); ztLastAtMs.set(0)
    }

    fun connectionOpened() = connectionOpened(null)

    /**
     * 记一次入站连接。
     *
     * @param source 连接来源；传 [InboundConn.Source.ZEROTIER] 会同时刷新 ZeroTier
     *   专属计数与「最近入站时刻」——界面靠它判断虚拟网这条路到底通没通。
     */
    fun connectionOpened(source: InboundConn.Source?) {
        totalConnections.incrementAndGet()
        val now = active.incrementAndGet()
        peakActive.updateAndGet { maxOf(it, now) }
        if (source == InboundConn.Source.ZEROTIER) {
            ztConnections.incrementAndGet()
            ztLastAtMs.set(System.currentTimeMillis())
        }
    }

    fun connectionClosed() {
        active.decrementAndGet()
    }

    fun requestOk() = requests.incrementAndGet()
    fun requestFailed() = failed.incrementAndGet()
    fun authRejectedCount() = authRejected.incrementAndGet()
    fun tunnelOpened() = tunnels.incrementAndGet()
    fun socketHit() = socketHits.incrementAndGet()
    fun fetchHit() = fetchHits.incrementAndGet()
    fun failover() = failovers.incrementAndGet()
    fun addUp(n: Long) { if (n > 0) bytesUp.addAndGet(n) }
    fun addDown(n: Long) { if (n > 0) bytesDown.addAndGet(n) }

    fun markError(msg: String) {
        lastError.set(msg.take(300))
        failed.incrementAndGet()
    }

    fun snapshot(running: Boolean, startedAtFallback: Long = 0L): Snapshot {
        val st = startedAt.get().takeIf { it > 0 } ?: startedAtFallback
        return Snapshot(
            running = running,
            startedAt = st,
            uptimeMs = if (st > 0) System.currentTimeMillis() - st else 0L,
            active = active.get(),
            peakActive = peakActive.get(),
            totalConnections = totalConnections.get(),
            requests = requests.get(),
            failed = failed.get(),
            authRejected = authRejected.get(),
            tunnels = tunnels.get(),
            socketHits = socketHits.get(),
            fetchHits = fetchHits.get(),
            failovers = failovers.get(),
            bytesUp = bytesUp.get(),
            bytesDown = bytesDown.get(),
            lastError = lastError.get(),
            ztConnections = ztConnections.get(),
            ztLastAtMs = ztLastAtMs.get(),
        )
    }

    companion object {
        fun humanBytes(n: Long): String {
            if (n < 1024) return "$n B"
            val kb = n / 1024.0
            if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
            val mb = kb / 1024.0
            if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
            return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
        }

        fun humanUptime(ms: Long): String {
            if (ms <= 0) return "—"
            val s = ms / 1000
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            return when {
                h > 0 -> String.format(java.util.Locale.US, "%d 小时 %02d 分", h, m)
                m > 0 -> String.format(java.util.Locale.US, "%d 分 %02d 秒", m, sec)
                else -> "$sec 秒"
            }
        }
    }
}
