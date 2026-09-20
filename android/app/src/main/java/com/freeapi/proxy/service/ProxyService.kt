package com.freeapi.proxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.freeapi.proxy.ConfigStore
import com.freeapi.proxy.MainActivity
import com.freeapi.proxy.ProxyRuntime
import com.freeapi.proxy.R
import com.freeapi.proxy.RunState
import com.freeapi.proxy.zerotier.ZeroTierRuntime
import com.freeapi.proxy.zerotier.ZeroTierStore

/**
 * 代理前台服务。
 *
 * 类型固定 `specialUse`——这个类型**没有运行时长上限**，不会像 `dataSync` 那样
 * 在 Android 15 上被 6 小时/24 小时上限掐掉。同时按 Android 14+ 的规矩：
 * 类型不硬编码、startForeground 一律 try/catch 降级，避免 SecurityException 直接崩包。
 */
class ProxyService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        alive = this
        ProxyRuntime.serviceAlive = true
        ProxyRuntime.loadConfig(this)
        createChannel()
        // startForegroundService 之后必须尽快 startForeground，否则系统直接判定 ANR/崩溃
        startForegroundCompat(buildNotification("正在启动…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // 用户明确要停：记下意图为 false，App 被杀后重开时不要自作主张恢复
                RunState.setProxyDesired(this, false)
                shutdown(removeAlarm = true)
                return START_NOT_STICKY
            }

            ACTION_RESTART -> {
                RunState.setProxyDesired(this, true)
                ProxyRuntime.stop()
                launch()
            }

            ACTION_START -> {
                RunState.setProxyDesired(this, true)
                launch()
            }

            // action 为空 = 系统按 START_STICKY 重建，不是用户的动作，意图保持原样
            else -> launch()
        }
        ProxyScheduler.armWatchdogIfEnabled(this)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 用户主动停过就不必续命
        if (!RunState.proxyDesired(this)) return

        // 划掉最近任务：**立即**补一次启动请求。
        // 在部分 ROM（尤其 MIUI / HyperOS）上，划卡片会随后走 force-stop，
        // 而这一刻进程往往还活着 —— 抢在它之前重新 startForegroundService，
        // 有机会让服务被标记为"仍在运行"而躲过清理。真正的兜底是下面的闹钟。
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, ProxyService::class.java).setAction(ACTION_START),
            )
        }
        if (ProxyRuntime.config.watchdogEnabled) {
            ProxyScheduler.armWatchdog(this, 3_000L)
        }
    }

    override fun onDestroy() {
        alive = null
        ProxyRuntime.serviceAlive = false
        ticker = null
        handler.removeCallbacksAndMessages(null)
        ProxyRuntime.stop()
        Wake.release(LOCK_RENEW)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 内部

    private fun launch() {
        val cfg = ConfigStore.load(this)
        ProxyRuntime.stageConfig(cfg)

        // 幂等：引擎已经在跑就别重建。
        // 本方法会被守护巡检、系统 START_STICKY 重建、划掉任务时的续命反复触发，
        // 每次都重建会掐断端口监听与在途连接（SSE 流尤其明显）。
        // 配置若真有变化，交给 applyConfig —— 它只在监听参数真变了才重建。
        val err = if (ProxyRuntime.isRunning) {
            if (ProxyRuntime.applyConfig(cfg)) {
                ProxyRuntime.log.info("配置已变化，监听已按新参数重建")
            }
            null
        } else {
            ProxyRuntime.start(cfg)
        }
        if (err != null) {
            ProxyRuntime.log.error("启动失败：$err")
        }

        // ZeroTier：节点设为自动运行时，趁这次服务启动把它带起来。
        // 只做"缺则补"，不重复启动 —— 否则服务每次被拉起都会让虚拟网络连接抖动一次。
        ZeroTierRuntime.prepare(this)
        if (ZeroTierStore.autoStart(this) && !ZeroTierRuntime.isNodeRunning) {
            val ztErr = ZeroTierRuntime.startNode(this)
            if (ztErr != null) {
                ProxyRuntime.log.warn("ZeroTier 节点未启动：$ztErr")
            }
        }

        if (cfg.holdWakeLock && err == null) {
            Wake.acquire(this, LOCK_RENEW, WAKE_TIMEOUT_MS)
        }
        refreshNotification()
        armTicker()
    }

    /**
     * 心跳：续期唤醒锁 + 刷新通知。
     * 唤醒锁本身让 CPU 保持唤醒，所以这个 Handler 定时器在息屏后依然会准时触发。
     */
    private fun armTicker() {
        if (ticker != null) return
        val r = object : Runnable {
            override fun run() {
                if (!ProxyRuntime.isRunning) {
                    ticker = null
                    return
                }
                if (ProxyRuntime.config.holdWakeLock) {
                    Wake.acquire(this@ProxyService, LOCK_RENEW, WAKE_TIMEOUT_MS)
                }
                refreshNotification()
                handler.postDelayed(this, TICK_MS)
            }
        }
        ticker = r
        handler.postDelayed(r, TICK_MS)
    }

    private fun shutdown(removeAlarm: Boolean) {
        ProxyRuntime.stop()
        if (removeAlarm) ProxyScheduler.cancelWatchdog(this)
        Wake.release(LOCK_RENEW)
        ticker = null
        handler.removeCallbacksAndMessages(null)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun refreshNotification() {
        val s = ProxyRuntime.snapshot()
        val text = when {
            ProxyRuntime.lastError != null -> "启动失败：${ProxyRuntime.lastError}"
            s.running -> "运行中 · 0.0.0.0:${ProxyRuntime.config.listenPort}" +
                " · 连接 ${s.active} · 请求 ${s.requests}"

            else -> "已停止"
        }
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_proxy)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundCompat(n: Notification) {
        // Android 14+ 会校验运行时类型，失败就退回不带类型的两参版本
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (t: Throwable) {
            ProxyRuntime.log.warn("startForeground(specialUse) 被拒，降级重试：${t.message}")
            runCatching { startForeground(NOTIF_ID, n) }
                .onFailure { ProxyRuntime.log.error("前台服务启动失败：${it.message}") }
        }
    }

    companion object {
        const val ACTION_START = "com.freeapi.proxy.action.START"
        const val ACTION_STOP = "com.freeapi.proxy.action.STOP"
        const val ACTION_RESTART = "com.freeapi.proxy.action.RESTART"

        private const val CHANNEL_ID = "freeapi_proxy"
        private const val NOTIF_ID = 0x5150
        private const val LOCK_RENEW = "proxy-renew"
        private const val WAKE_TIMEOUT_MS = 10 * 60_000L
        private const val TICK_MS = 15_000L

        @Volatile
        private var alive: ProxyService? = null

        val isAlive: Boolean get() = alive != null

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, ProxyService::class.java).setAction(ACTION_START),
            )
        }

        fun restart(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, ProxyService::class.java).setAction(ACTION_RESTART),
            )
        }

        /** 停止：Activity 可见时 startService 合法；通知上的「停止」走 PendingIntent。 */
        fun stop(ctx: Context) {
            if (isAlive) {
                runCatching { ctx.startService(Intent(ctx, ProxyService::class.java).setAction(ACTION_STOP)) }
            } else {
                ProxyRuntime.stop()
            }
        }
    }
}
