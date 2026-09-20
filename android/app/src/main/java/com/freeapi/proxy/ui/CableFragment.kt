package com.freeapi.proxy.ui

import android.app.TimePickerDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.freeapi.proxy.R
import com.freeapi.proxy.cable.BlePermissions
import com.freeapi.proxy.cable.CablePolicy
import com.freeapi.proxy.cable.CablePrefs
import com.freeapi.proxy.cable.CableRuntime
import com.freeapi.proxy.cable.cableLabel
import com.freeapi.proxy.databinding.FragmentCableBinding
import com.freeapi.proxy.service.CableService

/** 充电线页需要 Activity 代劳的动作（申请蓝牙权限、跳到日志页）。 */
interface CableHost {
    fun requestBlePermissions()

    /** 切到「日志」页签，看包含 [充电线] 前缀的完整日志。 */
    fun openLogTab()
}

/**
 * 充电线页 —— 通过蓝牙控制外部充电开关（功能移植自同目录下的 autoLine）。
 *
 * ## 界面自己不做任何 BLE 操作
 *
 * 所有指令都交给 [CableService]，由它串行执行。设备同一时刻只允许一个客户端连接，
 * 界面与服务各连一次必然互相打架 —— 这是 autoLine 已经验证过的结论，照搬。
 *
 * ## 阈值滑块为什么要挡回环
 *
 * `SeekBar.setProgress()` 同样会触发 OnSeekBarChangeListener，而 [refresh] 每秒都会
 * 设置一次进度。没有 [syncingSeek] 挡着的话，界面刷新会被当成"用户拖动"，
 * 于是写盘、再发一次服务指令，形成每秒一次的蓝牙连接风暴。
 *
 * 「拖动中只改数字、抬手才落库」也是同一个道理：一路上会经过十几个中间值。
 */
class CableFragment : Fragment(), Tile {

    private var _b: FragmentCableBinding? = null
    private val b get() = _b!!

    private val host get() = activity as? CableHost

    /** 代码正在同步开关，别把被动刷新当成用户操作 */
    private var syncingSwitch = false

    /** 代码正在同步滑块进度 */
    private var syncingSeek = false

    /** 用户正在拖哪条滑块：true = 低阈值，false = 高阈值，null = 没在拖 */
    private var dragging: Boolean? = null

    /** 周期档位按钮，按 [CablePolicy.INTERVAL_PRESETS] 顺序，只建一次 */
    private val intervalChips = mutableListOf<TextView>()

    private var renderedLogVersion = -1L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _b = FragmentCableBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val prefs = CablePrefs(ctx)

        // 用存盘的值先铺一遍运行时。
        // 服务没在跑时（还没开自动控制）没人会替我们做这件事 ——
        // 不铺的话滑块会显示默认的 20/95，而用户存过的可能是 30/90。
        CableRuntime.update {
            it.copy(
                thresholds = prefs.thresholds,
                intervalMin = prefs.intervalMin,
                autoEnabled = prefs.autoEnabled,
                lineState = prefs.lastLineState,
                window = prefs.chargeWindow,
                inWindow = CablePolicy.inWindow(CableRuntime.nowMinuteOfDay(), prefs.chargeWindow),
                serviceAlive = CableService.isAlive,
                permissionsOk = BlePermissions.allGranted(ctx),
            )
        }

        setupThresholdSeekBars()
        setupIntervalChips()

        val th = prefs.thresholds
        pushThresholdsToSeekBars(th)
        renderThresholdText(th.low, th.high)

        b.etBleName.setText(prefs.deviceName)

        syncingSwitch = true
        b.swCableAuto.isChecked = prefs.autoEnabled
        syncingSwitch = false
        b.swCableAuto.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitch) return@setOnCheckedChangeListener
            onAutoToggled(checked)
        }

        syncingSwitch = true
        b.swSchedule.isChecked = prefs.chargeWindow.enabled
        syncingSwitch = false
        b.swSchedule.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitch) return@setOnCheckedChangeListener
            onScheduleToggled(checked)
        }
        b.tvScheduleStart.setOnClickListener { pickTime(isStart = true) }
        b.tvScheduleEnd.setOnClickListener { pickTime(isStart = false) }

        b.btnCableCharge.setOnClickListener { sendManual(CablePolicy.CMD_START_CHARGE) }
        b.btnCableStop.setOnClickListener { sendManual(CablePolicy.CMD_STOP_CHARGE) }
        b.btnCableQuery.setOnClickListener {
            if (!ensureBle()) return@setOnClickListener
            CableService.refresh(ctx)
            toast("正在查询线缆状态…")
        }

        b.btnBleSave.setOnClickListener { saveDeviceName() }
        b.btnCableLogClear.setOnClickListener {
            CableRuntime.clearLog()
            renderedLogVersion = -1L
            refresh()
        }
        b.btnCableLogFull.setOnClickListener { host?.openLogTab() }

        refresh()
    }

    override fun onDestroyView() {
        _b = null
        renderedLogVersion = -1L
        super.onDestroyView()
    }

    // ------------------------------------------------------------------ 自动控制

    private fun onAutoToggled(enabled: Boolean) {
        if (enabled && !ensureBle()) {
            // 权限不够就别开 —— 否则服务会每轮都失败，而界面上开关还是"开"，
            // 用户只会看到日志里一排失败，不知道该去授权。
            syncingSwitch = true
            b.swCableAuto.isChecked = false
            syncingSwitch = false
            return
        }
        CableService.setAuto(requireContext(), enabled)
        // 服务要进前台/退出前台，状态回填有延迟，稍后再刷一次
        b.root.postDelayed({ refresh() }, 600)
    }

    /** 蓝牙权限不够就地申请，返回 false 表示这轮做不了。 */
    private fun ensureBle(): Boolean {
        val ctx = requireContext()
        val missing = BlePermissions.missing(ctx)
        if (missing.isEmpty()) return true
        toast("需要「${missing.joinToString("、") { BlePermissions.label(it) }}」权限")
        host?.requestBlePermissions()
        return false
    }

    private fun sendManual(command: String) {
        if (!ensureBle()) return
        CableService.send(requireContext(), command)
        toast("已发送：${CablePolicy.actionLabel(command)}")
    }

    private fun saveDeviceName() {
        val ctx = requireContext()
        val name = b.etBleName.text.toString().trim()
        CablePrefs(ctx).deviceName = name
        val effective = CablePrefs(ctx).deviceName
        b.etBleName.setText(effective)
        toast("设备名已保存：$effective")
    }

    // ------------------------------------------------------------------ 定时时段

    /**
     * 开关定时时段。
     *
     * 时段是套在「自动充电控制」外面的一层闸门 —— 自动控制没开时它什么都不做。
     * 所以打开时段时顺手把自动控制也一起打开：否则用户看到开关是开的、却什么都没发生，
     * 那正是这个项目一直在清理的"状态不诚实"。
     *
     * 反过来不成立：关掉自动控制**不会**关掉时段，时段只是暂时不生效（状态行会写明）。
     */
    private fun onScheduleToggled(enabled: Boolean) {
        if (enabled && !ensureBle()) {
            // 与自动控制同理：没蓝牙权限时开了，服务每轮都会失败，而开关看着还是"开"的
            syncingSwitch = true
            b.swSchedule.isChecked = false
            syncingSwitch = false
            return
        }
        val ctx = requireContext()
        val prefs = CablePrefs(ctx)
        val w = prefs.chargeWindow.copy(enabled = enabled)
        val autoWasOn = prefs.autoEnabled

        if (enabled && !autoWasOn) {
            // 先把时段存下再开自动控制 —— 服务那一轮 tick 会直接读到新时段，
            // 不必再多发一条 SET_WINDOW 去触发第二次蓝牙连接。
            applyWindow(w)
            CableService.setAuto(ctx, true)
            CableRuntime.log("打开定时时段的同时开启了自动充电控制（时段只在自动控制下生效）")
            toast("已同时打开「自动充电控制」")
        } else {
            applyWindow(w)
        }
        // 服务要进/退前台，状态回填有延迟，稍后再刷一次
        b.root.postDelayed({ refresh() }, 600)
    }

    /** 起止时刻点一下弹系统时间选择器。 */
    private fun pickTime(isStart: Boolean) {
        val ctx = requireContext()
        val w = CablePrefs(ctx).chargeWindow
        val current = if (isStart) w.startMin else w.endMin
        // 用 framework 自带的 TimePickerDialog：本项目不引 Material 库，
        // 而它是系统控件，24 小时制与明暗主题都跟着系统走。
        TimePickerDialog(
            ctx,
            { _, hour, minute ->
                val picked = hour * 60 + minute
                val updated = if (isStart) w.copy(startMin = picked) else w.copy(endMin = picked)
                if (updated.startMin == updated.endMin) {
                    // start == end 在策略层被语义化成"全天"，跟用户点出来的意图不符，直接拦下
                    toast("开始与结束时刻不能相同")
                    return@TimePickerDialog
                }
                applyWindow(updated)
            },
            current / 60,
            current % 60,
            true,
        ).show()
    }

    /** 落盘 + 铺运行时 + 让服务按新时段立刻重跑一轮。 */
    private fun applyWindow(w: CablePolicy.ChargeWindow) {
        val ctx = requireContext()
        CablePrefs(ctx).chargeWindow = w
        CableRuntime.update {
            it.copy(
                window = w,
                inWindow = CablePolicy.inWindow(CableRuntime.nowMinuteOfDay(), w),
            )
        }
        // 自动控制没开时不必叫服务：它起来也会立刻自己退出
        if (CablePrefs(ctx).autoEnabled) CableService.setWindow(ctx, w)
        renderSchedule()
    }

    /** 渲染定时时段卡。每秒被 [refresh] 调一次，只做读取与赋值，不发指令。 */
    private fun renderSchedule() {
        val binding = _b ?: return
        val ctx = requireContext()
        val w = CablePrefs(ctx).chargeWindow
        val inside = CablePolicy.inWindow(CableRuntime.nowMinuteOfDay(), w)
        val autoOn = CablePrefs(ctx).autoEnabled

        syncingSwitch = true
        binding.swSchedule.isChecked = w.enabled
        syncingSwitch = false

        binding.tvScheduleStart.text = CablePolicy.timeText(w.startMin)
        binding.tvScheduleEnd.text = CablePolicy.timeText(w.endMin)

        // 未启用时把两个时刻压暗：它们此刻不参与决策，亮着会让人以为还在生效
        val alpha = if (w.enabled) 1f else 0.4f
        binding.tvScheduleStart.alpha = alpha
        binding.tvScheduleEnd.alpha = alpha

        val span = CablePolicy.durationText(CablePolicy.windowLengthMin(w))
        binding.tvScheduleState.text = when {
            !w.enabled -> "未启用：全天都按电量阈值控制。"
            !autoOn -> "已设为 ${CablePolicy.windowText(w)}（共 $span），但自动控制没开，暂不生效。"
            inside -> "当前在时段内（${CablePolicy.windowText(w)}，共 $span），按电量阈值控制。"
            else -> "当前在时段外（${CablePolicy.windowText(w)}），已断开充电线。"
        }
        binding.tvScheduleState.setTextColor(
            color(
                when {
                    !w.enabled || !autoOn -> R.color.muted
                    inside -> R.color.ok
                    else -> R.color.warn
                }
            )
        )

        binding.tvScheduleHint.text =
            "时段是加在电量控制外面的一层闸门：时段外一律断电，只有电量极低才破例充电。"
        binding.tvScheduleNote.text = buildString {
            append("时段外电量掉到 ")
            append(CablePolicy.KEEP_ALIVE_PERCENT)
            append("% 及以下会保命充电，避免过放到关机（日志里会写明这一轮是保命）。")
            append("时段内仍受上面的高阈值保护：充满了照样停，不会因为在时段内就一直充。")
            append("起止时刻跨午夜时按次日算。")
        }
    }

    // ------------------------------------------------------------------ 阈值滑块

    private fun setupThresholdSeekBars() {
        b.seekLow.setOnSeekBarChangeListener(seekListener(isLow = true))
        b.seekHigh.setOnSeekBarChangeListener(seekListener(isLow = false))
    }

    private fun seekListener(isLow: Boolean) = object : SeekBar.OnSeekBarChangeListener {

        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
            if (!fromUser || syncingSeek) return
            onDragged(isLow, progress)
        }

        override fun onStartTrackingTouch(bar: SeekBar) {
            dragging = isLow
        }

        override fun onStopTrackingTouch(bar: SeekBar) {
            dragging = null
            commitThresholds()
        }
    }

    /**
     * 拖动中：把滑块夹进合法区间（被另一个阈值顶住），只刷数字，不落库。
     * 用户拖过头时直接改写滑块位置 —— 手感上就是"被顶住了"，比拖完才弹回去好。
     */
    private fun onDragged(isLow: Boolean, raw: Int) {
        val cur = CableRuntime.snapshot.thresholds
        val fixed = if (isLow) {
            CablePolicy.Thresholds(CablePolicy.clampLow(raw, cur.high), cur.high)
        } else {
            CablePolicy.Thresholds(cur.low, CablePolicy.clampHigh(cur.low, raw))
        }
        if (fixed != cur) CableRuntime.update { it.copy(thresholds = fixed) }
        pushThresholdsToSeekBars(fixed)
        renderThresholdText(fixed.low, fixed.high)
    }

    /** 抬手：落库；自动控制开着的话立刻按新阈值跑一轮。 */
    private fun commitThresholds() {
        val ctx = requireContext()
        val fixed = CableRuntime.snapshot.thresholds
        CablePrefs(ctx).thresholds = fixed
        if (CableRuntime.snapshot.autoEnabled) CableService.checkNow(ctx)
    }

    private fun pushThresholdsToSeekBars(th: CablePolicy.Thresholds) {
        syncingSeek = true
        // 滑块进度是 0-based，而电量从 1% 起，所以统一偏移 1
        b.seekLow.progress = th.low - 1
        b.seekHigh.progress = th.high - 1
        syncingSeek = false
        b.tvLowValue.text = "${th.low}%"
        b.tvHighValue.text = "${th.high}%"
    }

    /** 说明文字全部由数值拼出来，不写死在 strings.xml，免得"代码按 30% 执行、界面还写 20%"。 */
    private fun renderThresholdText(low: Int, high: Int) {
        b.tvThreshold.text = "低于等于 $low% 开始充电 · 高于等于 $high% 停止充电"
        b.tvThresholdHint.text =
            "两个阈值至少相差 ${CablePolicy.MIN_GAP}%：挨太近时电量在临界点上下抖一次，" +
                "就会来回开关一次充电。当前 $low% / $high%，中间是安全区，" +
                "电量落在这个区间内不会下发任何指令。"
    }

    // ------------------------------------------------------------------ 检查周期

    private fun setupIntervalChips() {
        val row = b.intervalRow
        intervalChips.clear()
        for (minutes in CablePolicy.INTERVAL_PRESETS) {
            val chip = TextView(requireContext()).apply {
                text = CablePolicy.intervalText(minutes)
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(chipColors())
                setBackgroundResource(R.drawable.bg_chip)
                isClickable = true
                isFocusable = true
                tag = minutes
                setPadding(0, dp(9), 0, dp(9))
                setOnClickListener { onIntervalPicked(minutes) }
            }
            row.addView(
                chip,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(6) },
            )
            intervalChips += chip
        }
        // 去掉最后一个的右边距，让整行与卡片边缘对齐
        (row.getChildAt(row.childCount - 1)?.layoutParams as? LinearLayout.LayoutParams)
            ?.let { it.marginEnd = 0 }
    }

    private fun onIntervalPicked(minutes: Int) {
        val ctx = requireContext()
        if (CablePrefs(ctx).intervalMin == minutes) return
        CablePrefs(ctx).intervalMin = minutes
        CableRuntime.update { it.copy(intervalMin = CablePolicy.clampInterval(minutes)) }
        // 改周期必须重排闹钟，而闹钟只有服务拿着 —— 让它跑一轮，它自然会按新周期重排。
        // 自动控制没开时什么都不用做（没有闹钟）。
        if (CableRuntime.snapshot.autoEnabled) CableService.checkNow(ctx)
        renderInterval()
    }

    private fun renderInterval() {
        val current = CableRuntime.snapshot.intervalMin
        for (chip in intervalChips) {
            val minutes = chip.tag as? Int ?: continue
            chip.isSelected = minutes == current
            // 必须重设状态色列表：之前用 isSelected 改过状态，得让选择器重新求值
            chip.setTextColor(chipColors())
        }
        b.tvIntervalHint.text = buildString {
            append("每 ${CablePolicy.intervalText(current)}检查一次电量。")
            if (current < CablePolicy.DOZE_MIN_INTERVAL_MIN) {
                append("\n注意：系统对 Doze 下的定时有约 ")
                append(CablePolicy.DOZE_MIN_INTERVAL_MIN)
                append(" 分钟的最小间隔，设得比这更短不会报错，但息屏后也不会真的按点触发（亮屏时有效）。")
            }
        }
    }

    // ------------------------------------------------------------------ 刷新

    override fun refresh() {
        val binding = _b ?: return
        val ctx = requireContext()
        val snap = CableRuntime.snapshot
        val autoOn = CablePrefs(ctx).autoEnabled
        val bleOk = BlePermissions.allGranted(ctx)
        val serviceAlive = CableService.isAlive

        // ---- 状态 ----
        val (dotColor, statusText) = when {
            snap.busy -> R.color.warn to "通信中…"
            snap.lineState == CablePolicy.CMD_START_CHARGE -> R.color.ok to "充电中"
            snap.lineState == CablePolicy.CMD_STOP_CHARGE -> R.color.warn to "已停止充电"
            else -> R.color.muted to "未知"
        }
        binding.dotCable.backgroundTintList = ColorStateList.valueOf(color(dotColor))
        binding.tvCableStatus.text = statusText
        binding.tvCableStatus.setTextColor(color(if (dotColor == R.color.ok) R.color.ok else R.color.brand_dark))
        binding.tvCableAuto.text = if (autoOn) "自动开" else "自动关"
        binding.tvCableAuto.setTextColor(color(if (autoOn) R.color.ok else R.color.muted))

        binding.tvCableBattery.text = if (snap.battery >= 0) "${snap.battery}%" else "—"
        binding.tvCableLine.text = cableLabel(snap.lineState)
        binding.tvCableLastCheck.text = CableRuntime.clockTextSec(snap.lastCheckAt)
        binding.tvCableNextCheck.text =
            if (autoOn && serviceAlive) CableRuntime.clockText(snap.nextCheckAt) else "—"
        binding.tvCableLastAction.text = snap.lastAction.ifEmpty { "—" }

        // ---- 提示 ----
        binding.tvCableHint.text = when {
            !bleOk -> "缺少蓝牙权限：到「设置」页的「蓝牙权限」一项去授权，否则无法连接充电线。"
            autoOn && !serviceAlive ->
                "自动控制开着，但后台服务没在跑。点一下上面的按钮会把它拉起来；" +
                    "若反复出现，检查「设置」页的省电策略与电池白名单。"
            snap.busy -> "正在与充电线通信…"
            snap.lineState == CablePolicy.CMD_START_CHARGE ->
                "当前：已发送 OFF，充电线在供电（所以手机在充电）。"
            snap.lineState == CablePolicy.CMD_STOP_CHARGE ->
                "当前：已发送 ON，充电线已断电。"
            else -> "点上面的按钮直接控制充电线（每次要连一次蓝牙，约 5～10 秒）。"
        }

        // ---- 控件可用性 ----
        val enabled = !snap.busy
        binding.btnCableCharge.isEnabled = enabled
        binding.btnCableStop.isEnabled = enabled
        binding.btnCableQuery.isEnabled = enabled

        syncingSwitch = true
        binding.swCableAuto.isChecked = autoOn
        syncingSwitch = false
        binding.swCableAuto.isEnabled = bleOk || autoOn

        binding.tvCableAutoHint.text = if (autoOn) {
            buildString {
                append("自动控制已开启：每 ")
                append(CablePolicy.intervalText(snap.intervalMin))
                append("检查一次，电量 ≤ ")
                append(snap.thresholds.low)
                append("% 时让充电线供电，≥ ")
                append(snap.thresholds.high)
                append("% 时断电。")
                // 时段开着时，上面这套阈值只在时段内跑 —— 不点破的话，
                // 用户看到"电量已经掉到 10% 却没充"会以为阈值坏了。
                if (CablePrefs(ctx).chargeWindow.enabled) append("（受下面的定时时段约束）")
            }
        } else {
            "自动控制已关闭：只在你手动点按钮时才会连接充电线，不会有常驻通知。"
        }

        // ---- 阈值与周期 ----
        val th = snap.thresholds
        syncingSeek = true
        // 正在拖的那条别动 —— 用户的拇指位置优先于服务回报的旧值
        if (dragging != true) binding.seekLow.progress = th.low - 1
        if (dragging != false) binding.seekHigh.progress = th.high - 1
        syncingSeek = false
        if (dragging != true) binding.tvLowValue.text = "${th.low}%"
        if (dragging != false) binding.tvHighValue.text = "${th.high}%"
        if (dragging == null) renderThresholdText(th.low, th.high)
        renderInterval()

        // 设备名输入框只在自己不在编辑时回填：用户正在改名时被覆盖会很恼人
        if (!binding.etBleName.hasFocus()) {
            val stored = CablePrefs(ctx).deviceName
            if (binding.etBleName.text.toString() != stored) binding.etBleName.setText(stored)
        }

        renderSchedule()
        renderLog()
    }

    private fun renderLog() {
        val binding = _b ?: return
        val v = CableRuntime.logVersion
        if (v == renderedLogVersion) return
        renderedLogVersion = v
        binding.tvCableLog.text = CableRuntime.recentLog()
            .joinToString("\n")
            .ifEmpty { "（暂无记录）" }
    }

    // ------------------------------------------------------------------ 工具

    private fun chipColors(): ColorStateList =
        ContextCompat.getColorStateList(requireContext(), R.color.chip_text)
            ?: ColorStateList.valueOf(color(R.color.muted))

    private fun color(id: Int): Int = ContextCompat.getColor(requireContext(), id)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}
