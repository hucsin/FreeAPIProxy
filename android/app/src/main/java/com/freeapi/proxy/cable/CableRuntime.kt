package com.freeapi.proxy.cable

import com.freeapi.proxy.ProxyRuntime
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 充电线控制的进程内运行时状态。
 *
 * ## 为什么用「快照对象 + 每秒拉」而不是 Flow
 *
 * 界面侧只需要 1 秒一次的刷新（`MainActivity` 的 ticker 已经就位，页面只要实现 `Tile`），
 * 所以这里暴露一个 `@Volatile` 的不可变快照就够了 —— 不必为此引入 StateFlow 与协程订阅。
 * 服务侧写完 `update { }` 立刻对新对象赋值，读侧永远看到自洽的一份。
 *
 * ## 日志为什么要留两份
 *
 * - **本页的「最近记录」**：只关心充电线自己的最近 N 条，翻起来短、聚焦；
 * - **全局日志页 / logcat**：`ProxyRuntime.log` 里也镜像一份，
 *   这样出问题时一条时间线能同时看到代理与充电线发生了什么，
 *   `adb logcat -s FreeAPI:V` 也能直接抓到。
 */
object CableRuntime {

    data class Snapshot(
        /** 手机电量（0..100），-1 = 未知 */
        val battery: Int = -1,
        /** 线缆回报的状态："ON" / "OFF"；null = 还不知道 */
        val lineState: String? = null,
        /** 正在跟蓝牙通信（界面据此禁用按钮，避免重复点） */
        val busy: Boolean = false,
        val autoEnabled: Boolean = false,
        val thresholds: CablePolicy.Thresholds = CablePolicy.Thresholds(
            CablePolicy.DEFAULT_LOW_PERCENT,
            CablePolicy.DEFAULT_HIGH_PERCENT,
        ),
        val intervalMin: Int = CablePolicy.DEFAULT_INTERVAL_MIN,
        /** 每日充电时段；默认 [CablePolicy.ChargeWindow.ALWAYS]，即不启用。 */
        val window: CablePolicy.ChargeWindow = CablePolicy.ChargeWindow.ALWAYS,
        /** 当前时刻是否落在时段内。未启用时段时恒为 true（界面据此不必显示"时段外"）。 */
        val inWindow: Boolean = true,
        val lastCheckAt: Long = 0L,
        val nextCheckAt: Long = 0L,
        val lastActionAt: Long = 0L,
        val lastAction: String = "",
        /** 前台服务是否活着。与 autoEnabled 分开：关了自动控制时服务会退出 */
        val serviceAlive: Boolean = false,
        /** 蓝牙权限是否齐 */
        val permissionsOk: Boolean = true,
    )

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    private const val LOG_LIMIT = 60
    private val lines = ArrayDeque<String>(LOG_LIMIT + 1)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** 记录数变化时自增，界面据此决定要不要重绘列表（同 ProxyLog 的思路）。 */
    @Volatile
    var logVersion: Long = 0L
        private set

    fun update(block: (Snapshot) -> Snapshot) {
        snapshot = block(snapshot)
    }

    /** 记一行：进本页缓冲，同时镜像到全局日志。 */
    fun log(line: String) {
        synchronized(this) {
            lines.addFirst("${timeFormat.format(Date())}  $line")
            while (lines.size > LOG_LIMIT) lines.removeLast()
            logVersion++
        }
        ProxyRuntime.log.info("[充电线] $line")
    }

    fun recentLog(): List<String> = synchronized(this) { ArrayList(lines) }

    fun clearLog() {
        synchronized(this) {
            lines.clear()
            logVersion++
        }
    }

    /** 格式化成 "HH:mm"，时间未知时给个占位符。 */
    fun clockText(at: Long): String =
        if (at <= 0L) "—" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))

    /** 带秒的时刻，给「上次检查」这种需要精确到秒的地方用。 */
    fun clockTextSec(at: Long): String =
        if (at <= 0L) "—" else SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))

    /**
     * 当前时刻「从 00:00 起的分钟数」。[CablePolicy] 的时段判断全靠它。
     *
     * 用 [Calendar] 而不是 `System.currentTimeMillis() / 60000`：后者算出来的是 UTC，
     * 时区一偏，「22:00」就变成了别的时间。这里只看时分，秒级误差对充电控制没有意义。
     *
     * 放在这里而不是塞进 [CablePolicy]：那个类要能脱开时间源做单测，
     * 所以它只接收"现在是几点"这个参数，不自己去读钟。
     */
    fun nowMinuteOfDay(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }
}
