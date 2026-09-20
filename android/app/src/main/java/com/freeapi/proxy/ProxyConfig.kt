package com.freeapi.proxy

import android.content.Context

/**
 * 代理运行配置。字段语义与 node-server.js 的环境变量/参数一一对应：
 *   port            ← --port / PORT            （默认 8788）
 *   token           ← PROXY_TOKEN              （空 = 拒绝一切，除非 allowOpen）
 *   allowOpen       ← PROXY_ALLOW_OPEN=1       （允许无鉴权裸奔）
 *   mode            ← --mode / PROXY_MODE      （auto|socket|fetch）
 *   userAgentOverride 覆盖转发时的 User-Agent（node 里这段被注释掉了，这里做成可选项）
 */
data class ProxyConfig(
    val port: Int = DEFAULT_PORT,
    val token: String = "",
    val allowOpen: Boolean = false,
    val mode: String = MODE_AUTO,
    val maxConnections: Int = 256,
    val idleTimeoutSec: Int = 300,
    val connectTimeoutSec: Int = 15,
    val userAgentOverride: String = "",
    val autoStartOnBoot: Boolean = false,
    val watchdogEnabled: Boolean = true,
    val holdWakeLock: Boolean = true,
) {
    /** 1024 以下为特权端口，非 root 绑不上，直接夹到合法区间 */
    val listenPort: Int get() = port.coerceIn(1024, 65535)
    val concurrency: Int get() = maxConnections.coerceIn(8, 2048)
    val idleTimeoutMs: Int get() = idleTimeoutSec.coerceIn(15, 3600) * 1000
    val connectTimeoutMs: Int get() = connectTimeoutSec.coerceIn(3, 120) * 1000

    /** 用于判断"是否需要重启监听" */
    fun sameListenerAs(other: ProxyConfig): Boolean =
        listenPort == other.listenPort && concurrency == other.concurrency

    companion object {
        const val DEFAULT_PORT = 8788
        const val MODE_AUTO = "auto"
        const val MODE_SOCKET = "socket"
        const val MODE_FETCH = "fetch"
        val MODES = listOf(MODE_AUTO, MODE_SOCKET, MODE_FETCH)
    }
}

object ConfigStore {
    private const val PREF = "freeapi_proxy_config"

    fun load(ctx: Context): ProxyConfig {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return ProxyConfig(
            port = sp.getInt("port", ProxyConfig.DEFAULT_PORT),
            token = sp.getString("token", "").orEmpty(),
            allowOpen = sp.getBoolean("allowOpen", false),
            mode = sp.getString("mode", ProxyConfig.MODE_AUTO).orEmpty()
                .takeIf { it in ProxyConfig.MODES } ?: ProxyConfig.MODE_AUTO,
            maxConnections = sp.getInt("maxConnections", 256),
            idleTimeoutSec = sp.getInt("idleTimeoutSec", 300),
            connectTimeoutSec = sp.getInt("connectTimeoutSec", 15),
            userAgentOverride = sp.getString("ua", "").orEmpty(),
            autoStartOnBoot = sp.getBoolean("autoStart", false),
            watchdogEnabled = sp.getBoolean("watchdog", true),
            holdWakeLock = sp.getBoolean("wakeLock", true),
        )
    }

    fun save(ctx: Context, c: ProxyConfig) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt("port", c.port)
            .putString("token", c.token)
            .putBoolean("allowOpen", c.allowOpen)
            .putString("mode", c.mode)
            .putInt("maxConnections", c.maxConnections)
            .putInt("idleTimeoutSec", c.idleTimeoutSec)
            .putInt("connectTimeoutSec", c.connectTimeoutSec)
            .putString("ua", c.userAgentOverride)
            .putBoolean("autoStart", c.autoStartOnBoot)
            .putBoolean("watchdog", c.watchdogEnabled)
            // 修复：此前漏写 holdWakeLock，导致「持有唤醒锁」开关重启后回落到默认值
            .putBoolean("wakeLock", c.holdWakeLock)
            .apply()
    }
}
