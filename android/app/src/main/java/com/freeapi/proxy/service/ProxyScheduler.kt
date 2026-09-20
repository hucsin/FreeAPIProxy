package com.freeapi.proxy.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.freeapi.proxy.ConfigStore

/**
 * 守护闹钟调度。用 `setAndAllowWhileIdle` 而不是 `setExactAndAllowWhileIdle`：
 * 前者**不需要 SCHEDULE_EXACT_ALARM 特殊权限**（那个权限在 Android 12+ 要用户去设置里点，
 * 很多 ROM 还默认拒绝），Doze 下最坏延迟到一个维护窗口，用来做"守护巡检"完全够。
 */
object ProxyScheduler {

    const val WATCHDOG_INTERVAL_MS = 15 * 60_000L
    private const val REQ_CODE = 0x5151

    fun armWatchdog(ctx: Context, delayMs: Long = WATCHDOG_INTERVAL_MS) {
        val am = ctx.applicationContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(ctx, create = true) ?: return
        val at = System.currentTimeMillis() + delayMs
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
    }

    fun armWatchdogIfEnabled(ctx: Context) {
        if (ConfigStore.load(ctx).watchdogEnabled) armWatchdog(ctx)
    }

    fun cancelWatchdog(ctx: Context) {
        val am = ctx.applicationContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(ctx, create = false) ?: return
        runCatching { am.cancel(pi) }
    }

    private fun pendingIntent(ctx: Context, create: Boolean): PendingIntent? {
        val intent = Intent(ctx, WatchdogReceiver::class.java)
            .setAction(WatchdogReceiver.ACTION_TICK)
        val flags = PendingIntent.FLAG_IMMUTABLE or
            if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(ctx.applicationContext, REQ_CODE, intent, flags)
    }
}
