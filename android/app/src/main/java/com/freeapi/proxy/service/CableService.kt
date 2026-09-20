package com.freeapi.proxy.service

import android.app.AlarmManager
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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.freeapi.proxy.MainActivity
import com.freeapi.proxy.R
import com.freeapi.proxy.cable.BatteryInfo
import com.freeapi.proxy.cable.BleClient
import com.freeapi.proxy.cable.CablePolicy
import com.freeapi.proxy.cable.CablePrefs
import com.freeapi.proxy.cable.CableRuntime
import com.freeapi.proxy.cable.cableLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * 充电线控制的前台服务（移植自 autoLine 的 AutoLineService）。
 *
 * ## 与「代理服务」的关系：**刻意分成两个服务**
 *
 * 两者的生命周期是独立的 —— 用户可能只想跑代理（不需要充电线），
 * 也可能只想控充电线（代理没开）。合并成一个服务会强行绑死：
 * 停代理就停掉充电控制，反之亦然。代价是多一条常驻通知，但状态是诚实的。
 *
 * ## 保活三板斧（缺一不可）
 *
 * 1. 前台服务（本文件）：带常驻通知，系统几乎不会杀有前台服务的进程
 * 2. 电池优化白名单 + ROM 省电策略放行：「设置」页有直达入口
 * 3. 开机自启 + `START_STICKY`：重启或进程被回收后能自己回来
 *
 * ## 定时为什么不用 Handler
 *
 * `Handler.postDelayed` 基于 uptimeMillis，手机一进深睡就停走，醒来时机不可控。
 * 这里用 `AlarmManager.setAndAllowWhileIdle`（Doze 白名单，不需要 SCHEDULE_EXACT_ALARM
 * 特殊权限），与 autoLine 一致。
 *
 * ## 「关掉自动控制后服务会自己退出」是有意的
 *
 * 只在自动控制开着时才常驻。手动指令走的是**一次性**路径：服务被拉起 → 进前台 →
 * 执行 → 退出前台并 stopSelf。否则用户只是想按一下"开始充电"，
 * 却换来一条永远撤不掉的常驻通知 —— 那正是这个项目一直在清理的"状态不诚实"。
 */
class CableService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 串行化 BLE 操作：设备同一时刻只接受一个客户端。 */
    private val bleMutex = Mutex()

    /** 在跑的活计数。为 0 且自动控制关闭时，服务才可以退出。 */
    private val pending = AtomicInteger(0)

    private val idleCheck = Handler(Looper.getMainLooper())

    private lateinit var prefs: CablePrefs
    private lateinit var ble: BleClient

    private var isForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = CablePrefs(this)
        ble = BleClient(this) { prefs.deviceName }
        alive = this
        createChannel()
        publishFromPrefs()
        // startForegroundService 之后必须尽快 startForeground，否则系统判定 ANR/崩溃。
        // 放在 onCreate 是抢时间：onStartCommand 里还可能被别的事耽误。
        isForeground = promoteToForeground(buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 一次性手动指令跑完会摘掉前台，此后再被拉起时必须重新进前台
        if (!isForeground) isForeground = promoteToForeground(buildNotification())

        if (!isForeground) {
            // Android 12+ 在后台启动前台服务有额外限制，极端省电模式下会被拒。
            // 安静放弃这一轮，但把闹钟排上，否则整条定时链就断在这里。
            CableRuntime.log("无法进入前台，本轮跳过（系统拒绝了前台服务）")
            if (prefs.autoEnabled) scheduleNextTick()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_SET_AUTO -> {
                val on = intent.getBooleanExtra(EXTRA_ENABLED, false)
                prefs.autoEnabled = on
                CableRuntime.update { it.copy(autoEnabled = on, serviceAlive = true) }
                CableRuntime.log(if (on) "自动充电控制已开启" else "自动充电控制已关闭")
                // 开启后立刻跑一轮：用户把阈值调高之后不该还要等一个周期才生效
                if (on) work("按电量检查一次") { tick(auto = true) }
            }

            ACTION_SET_THRESHOLDS -> {
                // 一般不会走到这里（设置页直接写 Prefs，见 CableFragment），
                // 留着是为了让服务仍然能在"阈值被外部改动"时立刻重跑一轮。
                val fixed = CablePolicy.sanitize(
                    intent.getIntExtra(EXTRA_LOW, CablePolicy.DEFAULT_LOW_PERCENT),
                    intent.getIntExtra(EXTRA_HIGH, CablePolicy.DEFAULT_HIGH_PERCENT),
                )
                prefs.thresholds = fixed
                CableRuntime.update { it.copy(thresholds = fixed) }
                work("按新阈值检查一次") { tick(auto = null) }
            }

            ACTION_SET_WINDOW -> {
                val w = CablePolicy.sanitizeWindow(
                    CablePolicy.ChargeWindow(
                        enabled = intent.getBooleanExtra(EXTRA_WINDOW_ENABLED, false),
                        startMin = intent.getIntExtra(
                            EXTRA_WINDOW_START, CablePolicy.DEFAULT_WINDOW_START_MIN
                        ),
                        endMin = intent.getIntExtra(
                            EXTRA_WINDOW_END, CablePolicy.DEFAULT_WINDOW_END_MIN
                        ),
                    )
                )
                prefs.chargeWindow = w
                CableRuntime.update {
                    it.copy(window = w, inWindow = CablePolicy.inWindow(CableRuntime.nowMinuteOfDay(), w))
                }
                CableRuntime.log(
                    if (w.enabled) "充电时段已设为 ${CablePolicy.windowText(w)}"
                    else "充电时段已关闭，全天允许充电"
                )
                // 改完必须立刻跑一轮：否则"此刻已经跨到时段外了"要等到下个周期才生效，
                // 这期间充电线还通着电 —— 用户会觉得定时没起作用。
                work("按新时段检查一次") { tick(auto = null) }
            }

            ACTION_SEND -> {
                val cmd = intent.getStringExtra(EXTRA_COMMAND)
                if (cmd != null) work("手动下发") { sendManual(cmd) }
            }

            ACTION_CHECK_NOW -> work("立即检查") { tick(auto = null) }
            ACTION_REFRESH -> work("查询线缆状态") { refreshLineState() }

            ACTION_STOP -> {
                prefs.autoEnabled = false
                CableRuntime.update { it.copy(autoEnabled = false) }
                CableRuntime.log("已停止充电线后台控制")
            }

            // action 为空 = 系统按 START_STICKY 重建，不是用户动作
            else -> if (prefs.autoEnabled) work("定时检查") { tick(auto = null) }
        }

        if (prefs.autoEnabled) scheduleNextTick() else cancelNextTick()
        finishIfIdle()
        return if (prefs.autoEnabled) START_STICKY else START_NOT_STICKY
    }

    /** 划掉最近任务时很多 ROM 会顺手杀进程：重新排一次闹钟并抢一次启动请求。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!prefs.autoEnabled) return
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, CableService::class.java).setAction(ACTION_CHECK_NOW),
            )
        }
        scheduleNextTick()
    }

    override fun onDestroy() {
        alive = null
        CableRuntime.update { it.copy(serviceAlive = false) }
        idleCheck.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    // ── 活儿 ────────────────────────────────────────────────────────────

    /**
     * 包一层：登记在跑的活、异常变日志、跑完看看服务该不该退出。
     *
     * 单独记 [pending] 而不是只看 `autoEnabled`：手动指令执行到一半时用户正好关掉了
     * 自动控制，这时候直接 stopSelf 会把蓝牙连接掐断，指令发没发出去就说不清了。
     */
    private fun work(what: String, block: suspend () -> Unit) {
        pending.incrementAndGet()
        if (what.isNotEmpty()) CableRuntime.update { it.copy(busy = true) }
        scope.launch {
            try {
                block()
            } catch (t: Throwable) {
                CableRuntime.log("${what}失败：${t.message ?: t.javaClass.simpleName}")
            } finally {
                if (pending.decrementAndGet() == 0) {
                    CableRuntime.update { it.copy(busy = false) }
                    idleCheck.post { finishIfIdle() }
                }
            }
        }
    }

    /**
     * 一次完整的检查：读电量 → 按「时段 + 阈值」决策 → 需要的话下发指令。
     *
     * 时段与阈值的先后顺序写在 [CablePolicy.decide] 里，这里只负责把当前时刻喂进去。
     *
     * @param auto true = 强制按电量自动决策；null = 跟随用户的自动控制开关
     */
    private suspend fun tick(auto: Boolean?) {
        val level = BatteryInfo.level(this)
        val autoOn = auto ?: prefs.autoEnabled
        val th = prefs.thresholds
        val window = prefs.chargeWindow
        val nowMin = CableRuntime.nowMinuteOfDay()
        val inside = CablePolicy.inWindow(nowMin, window)

        CableRuntime.update {
            it.copy(
                battery = level,
                lastCheckAt = System.currentTimeMillis(),
                thresholds = th,
                intervalMin = prefs.intervalMin,
                autoEnabled = prefs.autoEnabled,
                window = window,
                inWindow = inside,
                serviceAlive = true,
            )
        }

        if (!autoOn) {
            CableRuntime.log("电量 $level%，自动控制已关闭，本轮只读不发")
            refreshNotification()
            return
        }

        val decision = CablePolicy.decide(
            level = level,
            lastLineState = prefs.lastLineState,
            lowPercent = th.low,
            highPercent = th.high,
            window = window,
            nowMinuteOfDay = nowMin,
        )
        if (decision.command == null) {
            CableRuntime.log("电量 $level%，${decision.text}")
            refreshNotification()
            return
        }

        val command = decision.command
        val state = runBle(decision.text) { ble.sendCommand(command, expected = command) }
        if (state != null) {
            prefs.lastLineState = state
            CableRuntime.update {
                it.copy(
                    lineState = state,
                    lastActionAt = System.currentTimeMillis(),
                    lastAction = decision.text,
                )
            }
            CableRuntime.log("已下发 $command（${CablePolicy.actionLabel(command)}）→ 设备回报 $state")
        }
        refreshNotification()
    }

    /** 手动下发：界面上点「开始充电 / 停止充电」走这里。 */
    private suspend fun sendManual(command: String) {
        val label = CablePolicy.actionLabel(command)
        val state = runBle("手动$label") { ble.sendCommand(command, expected = command) }
        if (state != null) {
            prefs.lastLineState = state
            CableRuntime.update {
                it.copy(
                    lineState = state,
                    lastActionAt = System.currentTimeMillis(),
                    lastAction = "手动$label",
                )
            }
            CableRuntime.log("手动下发 $command（$label）→ 设备回报 $state")
        }
        refreshNotification()
    }

    /** 只查当前线缆状态，不改动它。 */
    private suspend fun refreshLineState() {
        val state = runBle("") { ble.readState() }
        if (state != null) {
            prefs.lastLineState = state
            CableRuntime.update { it.copy(lineState = state) }
            CableRuntime.log("查询到线缆状态：$state（${cableLabel(state)}）")
        }
        refreshNotification()
    }

    /**
     * 统一包一层：置忙、拿唤醒锁、把异常变成一行日志。
     *
     * 唤醒锁只在真正通信的这几秒里持有 —— 常驻持有会明显费电，
     * 而蓝牙握手期间 CPU 睡下去会直接导致连接超时。复用 [Wake]（它带超时且不计数）。
     *
     * @return 成功返回结果；失败返回 null（原因已进日志）
     */
    private suspend fun <T> runBle(what: String, block: suspend () -> T): T? = bleMutex.withLock {
        Wake.acquire(this, LOCK_BLE, WAKE_LOCK_TIMEOUT_MS)
        if (what.isNotEmpty()) CableRuntime.log("$what … 正在连接蓝牙")
        try {
            block()
        } catch (e: BleClient.BleException) {
            CableRuntime.log("失败：${e.message}")
            null
        } catch (e: SecurityException) {
            CableRuntime.log("失败：缺少蓝牙权限，请在「设置」页重新授权")
            null
        } catch (e: Exception) {
            CableRuntime.log("失败：${e.message ?: e.javaClass.simpleName}")
            null
        } finally {
            Wake.release(LOCK_BLE)
        }
    }

    // ── 定时 ────────────────────────────────────────────────────────────

    /**
     * 用 `getForegroundService` 而不是 `getService`：服务的第一件事就是进前台，
     * 而且进程万一真被系统杀掉、只剩这个闹钟时，闹钟派发的就是一次**后台启动服务** ——
     * 走 `getService` 会撞上 Android 8+ 的后台启动限制直接抛异常，整条定时链断掉。
     *
     * ## 为什么还要看时段边界
     *
     * 只按固定检查周期排的话，22:00 这个整点会被拖到下一个周期才生效 ——
     * 周期设成 60 分钟时最晚能晚一小时，那就不叫"定时"了。
     * 所以取「检查周期」和「距下一个时段边界」里更近的那个：
     * 边界那天会多跑一轮，一天两次，代价可以忽略。
     */
    private fun scheduleNextTick() {
        val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = tickIntent(create = true) ?: return
        val intervalMs = prefs.intervalMin * 60_000L

        val untilBoundaryMin =
            CablePolicy.minutesUntilWindowChange(CableRuntime.nowMinuteOfDay(), prefs.chargeWindow)
        val effectiveMs = if (untilBoundaryMin != null) {
            minOf(intervalMs, untilBoundaryMin * 60_000L)
        } else {
            intervalMs
        }

        val triggerAt = SystemClock.elapsedRealtime() + effectiveMs
        runCatching {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        }.onFailure { CableRuntime.log("排定下次检查失败：${it.message}") }
        CableRuntime.update { it.copy(nextCheckAt = System.currentTimeMillis() + effectiveMs) }
    }

    private fun cancelNextTick() {
        val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val pi = tickIntent(create = false)
        if (am != null && pi != null) runCatching { am.cancel(pi) }
        CableRuntime.update { it.copy(nextCheckAt = 0L) }
    }

    private fun tickIntent(create: Boolean): PendingIntent? {
        val intent = Intent(this, CableService::class.java).setAction(ACTION_TICK)
        val flags = PendingIntent.FLAG_IMMUTABLE or
            if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getForegroundService(this, REQ_TICK, intent, flags)
    }

    /** 没活了、且用户没开自动控制 → 摘掉前台并退出。 */
    private fun finishIfIdle() {
        if (pending.get() > 0) return
        if (prefs.autoEnabled) return
        CableRuntime.update { it.copy(serviceAlive = false) }
        stopForegroundCompat()
        stopSelf()
    }

    // ── 通知 ────────────────────────────────────────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.cable_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.cable_notif_channel_desc)
                setShowBadge(false)
            }
        )
    }

    private fun promoteToForeground(n: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            ServiceCompat.startForeground(this, NOTIF_ID, n, 0)
        }
        true
    } catch (t: Throwable) {
        // 通知权限被关、或系统拒绝前台服务类型，都会落这里
        CableRuntime.log("无法进入前台：${t.message ?: t.javaClass.simpleName}")
        false
    }

    private fun stopForegroundCompat() {
        isForeground = false
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
    }

    private fun refreshNotification() {
        if (!isForeground) return
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val snap = CableRuntime.snapshot
        val level = if (snap.battery >= 0) "${snap.battery}%" else "未知"
        val th = snap.thresholds
        val text = buildString {
            append("电量 $level · ${cableLabel(snap.lineState)}")
            append(" · ").append(if (prefs.autoEnabled) "自动开" else "自动关")
            if (prefs.autoEnabled) {
                // 阈值和周期一起放进通知：调过之后不用点进 App 就能确认现在按什么在跑
                append(" · ≤").append(th.low).append("% 充 / ≥").append(th.high).append("% 断")
                val w = prefs.chargeWindow
                if (w.enabled) {
                    // 时段开着时，「此刻在不在时段内」是最该一眼看到的信息
                    append(" · ").append(CablePolicy.windowText(w))
                    append(if (snap.inWindow) "（时段内）" else "（时段外）")
                }
                append(" · 每 ").append(CablePolicy.intervalText(prefs.intervalMin))
            } else {
                // 自动控制关着还出现通知 = 正在跑一次性手动指令，说清楚它马上就消失
                append(" · 一次性手动指令，执行后自动退出")
            }
        }

        val open = PendingIntent.getActivity(
            this, REQ_OPEN,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PENDING_FLAGS,
        )
        val stop = PendingIntent.getService(
            this, REQ_STOP,
            Intent(this, CableService::class.java).setAction(ACTION_STOP),
            PENDING_FLAGS,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_proxy)
            .setContentTitle(getString(R.string.cable_notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** 把持久化的设置铺进运行时，让页面首帧就有正确数值（服务没跑时也不会显示默认值）。 */
    private fun publishFromPrefs() {
        val th = prefs.thresholds
        val w = prefs.chargeWindow
        CableRuntime.update {
            it.copy(
                autoEnabled = prefs.autoEnabled,
                thresholds = th,
                intervalMin = prefs.intervalMin,
                lineState = prefs.lastLineState,
                window = w,
                inWindow = CablePolicy.inWindow(CableRuntime.nowMinuteOfDay(), w),
                serviceAlive = true,
            )
        }
    }

    companion object {
        const val ACTION_TICK = "com.freeapi.proxy.cable.TICK"
        const val ACTION_SEND = "com.freeapi.proxy.cable.SEND"
        const val ACTION_SET_AUTO = "com.freeapi.proxy.cable.SET_AUTO"
        const val ACTION_SET_THRESHOLDS = "com.freeapi.proxy.cable.SET_THRESHOLDS"
        const val ACTION_SET_WINDOW = "com.freeapi.proxy.cable.SET_WINDOW"
        const val ACTION_CHECK_NOW = "com.freeapi.proxy.cable.CHECK_NOW"
        const val ACTION_REFRESH = "com.freeapi.proxy.cable.REFRESH"
        const val ACTION_STOP = "com.freeapi.proxy.cable.STOP"

        const val EXTRA_COMMAND = "command"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_LOW = "low_percent"
        const val EXTRA_HIGH = "high_percent"
        const val EXTRA_WINDOW_ENABLED = "window_enabled"
        const val EXTRA_WINDOW_START = "window_start_min"
        const val EXTRA_WINDOW_END = "window_end_min"

        private const val CHANNEL_ID = "freeapi_cable"
        private const val NOTIF_ID = 0x5151
        private const val REQ_TICK = 0x5152
        private const val REQ_OPEN = 0x5153
        private const val REQ_STOP = 0x5154
        private const val LOCK_BLE = "cable-ble"
        private const val WAKE_LOCK_TIMEOUT_MS = 60_000L

        private const val PENDING_FLAGS =
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        @Volatile
        private var alive: CableService? = null

        val isAlive: Boolean get() = alive != null

        /** 开/关自动控制。关掉后服务会在手头的活干完后自行退出。 */
        fun setAuto(ctx: Context, enabled: Boolean) =
            fire(ctx, Intent(ctx, CableService::class.java)
                .setAction(ACTION_SET_AUTO)
                .putExtra(EXTRA_ENABLED, enabled))

        /** 手动下发一条指令，走服务以串行化 BLE 连接。 */
        fun send(ctx: Context, command: String) =
            fire(ctx, Intent(ctx, CableService::class.java)
                .setAction(ACTION_SEND)
                .putExtra(EXTRA_COMMAND, command))

        /** 只查询当前线缆状态。 */
        fun refresh(ctx: Context) =
            fire(ctx, Intent(ctx, CableService::class.java).setAction(ACTION_REFRESH))

        /**
         * 立即跑一轮检查。
         *
         * 顺带承担「改完设置让新值马上生效」：服务在处理完这个 action 后一定会
         * 重排闹钟，而周期是从 Prefs 现读的，所以改了周期、阈值之后调它一次就够了。
         */
        fun checkNow(ctx: Context) =
            fire(ctx, Intent(ctx, CableService::class.java).setAction(ACTION_CHECK_NOW))

        /**
         * 修改每日充电时段。
         *
         * 与阈值同理：改完要让服务立刻重跑一轮并重排闹钟。时段边界是动态的，
         * 只写盘不通知的话，"此刻已经在时段外了"得等一个周期才生效，这段空窗期充电线还通着电。
         */
        fun setWindow(ctx: Context, window: CablePolicy.ChargeWindow) =
            fire(ctx, Intent(ctx, CableService::class.java)
                .setAction(ACTION_SET_WINDOW)
                .putExtra(EXTRA_WINDOW_ENABLED, window.enabled)
                .putExtra(EXTRA_WINDOW_START, window.startMin)
                .putExtra(EXTRA_WINDOW_END, window.endMin))

        /**
         * 起服务。
         *
         * 一律走 `startForegroundService`：Android 8+ 从后台（例如开机广播、
         * 闹钟回来时）用 `startService` 会直接抛异常。
         */
        private fun fire(ctx: Context, intent: Intent) {
            runCatching { ContextCompat.startForegroundService(ctx.applicationContext, intent) }
                // 别静默吞掉：起不来时界面上的开关会"看起来打开了但没生效"，
                // 这正是本项目反复踩过的「状态不诚实」，至少要在日志里留一条。
                .onFailure { CableRuntime.log("启动后台服务失败：${it.message ?: it.javaClass.simpleName}") }
        }
    }
}
