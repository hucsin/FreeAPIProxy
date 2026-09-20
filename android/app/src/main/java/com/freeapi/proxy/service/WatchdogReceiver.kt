package com.freeapi.proxy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.freeapi.proxy.ConfigStore
import com.freeapi.proxy.ProxyRuntime

/**
 * 守护巡检：定时检查服务是否还活着，掉线就拉起来，然后重新排下一次闹钟。
 *
 * 注意 Android 12+ 的后台 FGS 启动限制：从后台（闹钟广播）启动前台服务需要豁免，
 * 「已在电池优化白名单」正是豁免条件之一——这就是为什么界面里必须引导用户去授权。
 * 没授权时会抛 ForegroundServiceStartNotAllowedException，这里捕获并记日志。
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent?) {
        val app = ctx.applicationContext
        Wake.acquire(app, LOCK, 60_000L)
        try {
            val cfg = ConfigStore.load(app)
            if (!cfg.watchdogEnabled) return

            if (ProxyRuntime.isRunning && ProxyService.isAlive) return

            if (!ProxyRuntime.isRunning) {
                ProxyRuntime.log.warn("守护巡检：代理未在运行，正在拉起")
            } else {
                ProxyRuntime.log.warn("守护巡检：代理在跑但前台服务已丢，正在补起")
            }
            runCatching {
                ContextCompat.startForegroundService(
                    app,
                    Intent(app, ProxyService::class.java).setAction(ProxyService.ACTION_START),
                )
            }.onFailure {
                ProxyRuntime.log.error(
                    "守护拉起失败（多为未加入电池优化白名单）：" +
                        "${it.javaClass.simpleName}: ${it.message}"
                )
            }
        } finally {
            // 无论成败都重排下一次，别让巡检链断掉
            ProxyScheduler.armWatchdog(app)
            Wake.release(LOCK)
        }
    }

    companion object {
        const val ACTION_TICK = "com.freeapi.proxy.action.WATCHDOG"
        private const val LOCK = "watchdog"
    }
}
