package com.freeapi.proxy.cable

/**
 * 电量决策 —— 纯 Kotlin，不依赖任何 Android API，可以直接在主机上跑单元测试。
 *
 * ## 指令语义（移植自 autoLine，**别搞混**）
 *
 * 固件（USB-Switch）里：
 * ```
 * ON  = 通电  -> USB 5V 输出 -> 充电线得电
 * OFF = 断电  -> USB 5V 断开
 * ```
 * 但「线得电」才等于「手机在充电」，所以本 App 的用法是：
 * ```
 * 电量低 -> 发 OFF 让线供电 -> 开始充电
 * 电量高 -> 发 ON  让线断电 -> 停止充电
 * ```
 * 万一哪天实测发现开关反了，只改下面两个常量，别处一行都不用动。
 *
 * ## 为什么不用状态机而用「想要什么」对比「已知什么」
 *
 * 每轮只在「该做的动作」与「已知线缆状态」不一致时才下发。这样：
 *  - 电量 18% 发了 OFF，下一轮 17% 不会再发一次（省一次蓝牙握手）；
 *  - 进程重启后 `lastLineState` 从磁盘恢复，也不会因为"刚起来不知道状态"而滥用指令。
 */
object CablePolicy {

    /** 出厂默认阈值：低到 20% 开始充电，高到 95% 停止。 */
    const val DEFAULT_LOW_PERCENT = 20
    const val DEFAULT_HIGH_PERCENT = 95

    const val MIN_PERCENT = 1
    const val MAX_PERCENT = 100

    /**
     * 两个阈值至少要差这么多。
     *
     * 挨太近（20% / 21%）时，电量在临界点上下抖一次就会来回开关一次充电 ——
     * 所以当成硬约束：界面滑块互相顶住做不出这种设置，存盘的与决策用的值也都过一遍校验。
     */
    const val MIN_GAP = 5

    /** 让充电线供电。低电量时发它。 */
    const val CMD_START_CHARGE = "OFF"

    /** 让充电线断电。高电量时发它。 */
    const val CMD_STOP_CHARGE = "ON"

    enum class Reason { LOW, HIGH, NONE, NO_BATTERY, OUT_OF_WINDOW, KEEP_ALIVE }

    data class Decision(val command: String?, val reason: Reason, val text: String)

    data class Thresholds(val low: Int, val high: Int)

    // ── 阈值校验 ────────────────────────────────────────────────────────

    /** 低阈值上限：要给高阈值留出 [MIN_GAP] 间隔，自身也不能顶到 MAX。 */
    fun lowUpperBound(high: Int): Int =
        (high - MIN_GAP).coerceIn(MIN_PERCENT, MAX_PERCENT - MIN_GAP)

    fun highLowerBound(low: Int): Int = (low + MIN_GAP).coerceAtMost(MAX_PERCENT)

    fun clampLow(low: Int, high: Int): Int = low.coerceIn(MIN_PERCENT, lowUpperBound(high))

    fun clampHigh(low: Int, high: Int): Int = high.coerceIn(highLowerBound(low), MAX_PERCENT)

    fun sanitize(low: Int, high: Int): Thresholds {
        val l = clampLow(low, high)
        return Thresholds(l, clampHigh(l, high))
    }

    // ── 检查周期 ────────────────────────────────────────────────────────

    const val DEFAULT_INTERVAL_MIN = 10
    const val MIN_INTERVAL_MIN = 1
    const val MAX_INTERVAL_MIN = 120

    /**
     * `setAndAllowWhileIdle` 在 Doze 下约 9 分钟才会被放行一次。
     * 设得比这更短**不会报错，但也不会按点触发** —— 界面上照实提示，不阻止用户设
     * （亮屏时短周期仍然有效）。
     */
    const val DOZE_MIN_INTERVAL_MIN = 9

    val INTERVAL_PRESETS = intArrayOf(5, 10, 15, 30, 60)

    fun clampInterval(minutes: Int): Int =
        minutes.coerceIn(MIN_INTERVAL_MIN, MAX_INTERVAL_MIN)

    /** 周期的人话写法：60 的整数倍说「小时」。 */
    fun intervalText(minutes: Int): String {
        val m = clampInterval(minutes)
        return if (m >= 60 && m % 60 == 0) "${m / 60} 小时" else "$m 分钟"
    }

    // ── 每日充电时段 ────────────────────────────────────────────────────

    /**
     * 一天的分钟数。时段一律用「从 00:00 起的分钟数」表示 ——
     * 这样「跨午夜」不需要任何特判，只是一个不等式方向的问题。
     */
    const val MINUTES_PER_DAY = 24 * 60

    /** 出厂默认时段：22:00 开始充电、次日 06:00 断电 —— 最常见的夜间充电。 */
    const val DEFAULT_WINDOW_START_MIN = 22 * 60
    const val DEFAULT_WINDOW_END_MIN = 6 * 60

    /**
     * 时段外，电量掉到这个值及以下就「保命充电」。
     *
     * 时段的本意是"这段时间之外别充电"，但把规则一路执行到 0% 会过放到自动关机 ——
     * 用户要的是省着充，不是让手机死掉。所以留一个不可协商的底限；
     * 真触发时日志会写明这一轮是保命，不让人以为是时段判断坏了。
     */
    const val KEEP_ALIVE_PERCENT = 5

    data class ChargeWindow(
        val enabled: Boolean = false,
        val startMin: Int = DEFAULT_WINDOW_START_MIN,
        val endMin: Int = DEFAULT_WINDOW_END_MIN,
    ) {
        companion object {
            /** 不启用时段。[inWindow] 对它是恒真的，等价于「没有时段限制」。 */
            val ALWAYS = ChargeWindow()
        }
    }

    /** 把任意分钟数折进 `[0, 1440)`，负数也吃。 */
    fun normalizeMinute(minuteOfDay: Int): Int {
        val m = minuteOfDay % MINUTES_PER_DAY
        return if (m < 0) m + MINUTES_PER_DAY else m
    }

    fun sanitizeWindow(window: ChargeWindow): ChargeWindow =
        window.copy(
            startMin = normalizeMinute(window.startMin),
            endMin = normalizeMinute(window.endMin),
        )

    /**
     * 给定时刻（从 00:00 起的分钟数）是否落在时段内。
     *
     * 三种情形：
     *  - 未启用 → 恒 true（等于没有时段限制）
     *  - `start < end` → 同日窗口，如 08:00–18:00，即 `start <= m < end`
     *  - `start > end` → **跨午夜**，如 22:00–06:00，即 `m >= start || m < end`
     *
     * 上界取开区间（`< end`）：设到 06:00 就该在 06:00 整断电，而不是拖到 06:01。
     *
     * `start == end` 视作**全天**而不是空窗口 —— 空窗口意味着永远不充电，
     * 这种「看起来正常、实际致命」的配置不该由一次误操作产生。
     */
    fun inWindow(minuteOfDay: Int, window: ChargeWindow): Boolean {
        if (!window.enabled) return true
        val s = normalizeMinute(window.startMin)
        val e = normalizeMinute(window.endMin)
        if (s == e) return true
        val m = normalizeMinute(minuteOfDay)
        return if (s < e) m in s until e else (m >= s || m < e)
    }

    /**
     * 距下一次「进/出时段」还有多少分钟；未启用时段时返回 null。
     *
     * 用来把闹钟排到边界上：只按固定检查周期排的话，22:00 这种整点会被拖到下一个
     * 周期才生效 —— 周期设成 60 分钟时最晚能晚一小时，那就不是"定时"了。
     * 返回值至少为 1，保证排出来的闹钟一定落在未来（否则 AlarmManager 会立即触发）。
     */
    fun minutesUntilWindowChange(minuteOfDay: Int, window: ChargeWindow): Int? {
        if (!window.enabled) return null
        val s = normalizeMinute(window.startMin)
        val e = normalizeMinute(window.endMin)
        if (s == e) return null
        val now = normalizeMinute(minuteOfDay)
        val next = if (inWindow(now, window)) e else s
        var d = next - now
        if (d <= 0) d += MINUTES_PER_DAY
        return d
    }

    /** `"22:00"` 这种写法。 */
    fun timeText(minuteOfDay: Int): String {
        val m = normalizeMinute(minuteOfDay)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** 时段的人话描述，跨午夜时明确写出「次日」。 */
    fun windowText(window: ChargeWindow): String {
        if (!window.enabled) return "全天允许充电"
        val s = normalizeMinute(window.startMin)
        val e = normalizeMinute(window.endMin)
        if (s == e) return "全天允许充电（起止相同）"
        // 跨午夜时把「次日」贴住结束时刻，别写成「至 次日」（中文里那个空格很别扭）
        return if (s > e) "${timeText(s)} 至次日 ${timeText(e)}"
        else "${timeText(s)} 至 ${timeText(e)}"
    }

    /** 时段有多长（分钟）。`start == end` 按全天算。 */
    fun windowLengthMin(window: ChargeWindow): Int {
        if (!window.enabled) return MINUTES_PER_DAY
        val s = normalizeMinute(window.startMin)
        val e = normalizeMinute(window.endMin)
        if (s == e) return MINUTES_PER_DAY
        var d = e - s
        if (d <= 0) d += MINUTES_PER_DAY
        return d
    }

    /**
     * 时长的口语写法。
     *
     * 不能复用 [intervalText]：那个会先过 [clampInterval]（上限 120 分钟），
     * 而一个夜间时段就是 480 分钟，会被夹成「2 小时」—— 数字直接是错的。
     */
    fun durationText(minutes: Int): String {
        val m = minutes.coerceAtLeast(0)
        return when {
            m < 60 -> "$m 分钟"
            m % 60 == 0 -> "${m / 60} 小时"
            else -> "${m / 60} 小时 ${m % 60} 分钟"
        }
    }

    // ── 决策 ────────────────────────────────────────────────────────────

    /**
     * 一轮决策：该下发哪条指令（或什么都不做）。
     *
     * ## 时段与阈值的优先级
     *
     * 顺序是固定的，不要调换：
     *
     * 1. **时段外** → 一律断电；只有电量掉到 [KEEP_ALIVE_PERCENT] 及以下才保命充电。
     *    时段是"闸门"，它说不充就不充。
     * 2. **时段内** → 这才轮到电量阈值说话：`≤ low` 充、`≥ high` 断、中间保持。
     *    也就是说时段**不能**让人无视高阈值一直充 —— 充满了照样停，避免过充。
     *
     * @param level          当前电量（0..100）；-1 表示读不到
     * @param lastLineState  已知的线缆状态（"ON" / "OFF"）；null = 还不知道
     * @param window         每日充电时段；默认 [ChargeWindow.ALWAYS]（不启用）
     * @param nowMinuteOfDay 当前时刻（从 00:00 起的分钟数），只在启用时段时有意义
     */
    fun decide(
        level: Int,
        lastLineState: String?,
        lowPercent: Int = DEFAULT_LOW_PERCENT,
        highPercent: Int = DEFAULT_HIGH_PERCENT,
        window: ChargeWindow = ChargeWindow.ALWAYS,
        nowMinuteOfDay: Int = 0,
    ): Decision {
        val (low, high) = sanitize(lowPercent, highPercent)

        if (level < 0) {
            return Decision(null, Reason.NO_BATTERY, "读不到电量，本轮跳过")
        }

        // null = 本轮没有要下发的动作（要么该保持现状，要么已经是对的状态）
        val plan: Decision? = if (!inWindow(nowMinuteOfDay, window)) {
            if (level <= KEEP_ALIVE_PERCENT) {
                Decision(
                    CMD_START_CHARGE,
                    Reason.KEEP_ALIVE,
                    "时段外，但电量只剩 $level%（≤ $KEEP_ALIVE_PERCENT%），保命充电",
                )
            } else {
                Decision(
                    CMD_STOP_CHARGE,
                    Reason.OUT_OF_WINDOW,
                    "不在充电时段（${windowText(window)}），断开充电线",
                )
            }
        } else {
            when {
                level <= low -> Decision(
                    CMD_START_CHARGE, Reason.LOW, "电量已降到 $low% 及以下，开始充电",
                )
                level >= high -> Decision(
                    CMD_STOP_CHARGE, Reason.HIGH, "电量已达到 $high%，停止充电",
                )
                else -> null
            }
        }

        if (plan == null) {
            return Decision(null, Reason.NONE, "电量在 ${low + 1}%~${high - 1}% 之间，保持现状")
        }
        if (plan.command == lastLineState) {
            return Decision(
                null, Reason.NONE, "${plan.text} —— 已经是「${actionLabel(plan.command)}」，不重复下发",
            )
        }
        return plan
    }

    /** 指令对应的动作名，用于界面与日志。 */
    fun actionLabel(command: String?): String = when (command) {
        CMD_START_CHARGE -> "开始充电"
        CMD_STOP_CHARGE -> "停止充电"
        else -> "无动作"
    }
}

/**
 * 把固件回报的 ON / OFF 翻译成人话。
 *
 * 注意这里和 [CablePolicy.CMD_*] 是同一对值：**线缆上报的 "OFF" 表示它正在供电**，
 * 也就是手机在充电。UI 上用这个函数，别自己写 when。
 */
fun cableLabel(wire: String?): String = when (wire) {
    CablePolicy.CMD_START_CHARGE -> "充电中"
    CablePolicy.CMD_STOP_CHARGE -> "已停止充电"
    else -> "未知"
}
