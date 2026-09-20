package com.freeapi.proxy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.freeapi.proxy.ConfigStore
import com.freeapi.proxy.ProxyRuntime

/**
 * 开机自启。BOOT_COMPLETED 是 Android 12+ 起前台服务的少数豁免场景之一，
 * 所以这里启动 FGS 是合法的；但仍然套 try/catch，防止个别 ROM 直接拒。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent?) {
        val app = ctx.applicationContext
        val cfg = ConfigStore.load(app)
        if (!cfg.autoStartOnBoot) return

        Wake.acquire(app, "boot", 60_000L)
        try {
            ProxyRuntime.log.info("开机广播：按配置拉起代理服务")
            runCatching {
                ContextCompat.startForegroundService(
                    app,
                    Intent(app, ProxyService::class.java).setAction(ProxyService.ACTION_START),
                )
            }.onFailure {
                ProxyRuntime.log.error("开机自启失败：${it.javaClass.simpleName}: ${it.message}")
            }
        } finally {
            Wake.release("boot")
        }
    }
}
