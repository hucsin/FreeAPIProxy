package com.freeapi.proxy.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.freeapi.proxy.ConfigStore
import com.freeapi.proxy.ProxyConfig
import com.freeapi.proxy.ProxyRuntime
import com.freeapi.proxy.R
import com.freeapi.proxy.cable.BlePermissions
import com.freeapi.proxy.cable.CableRuntime
import com.freeapi.proxy.databinding.FragmentSettingsBinding
import com.freeapi.proxy.service.ProxyScheduler
import com.freeapi.proxy.service.VendorSettings

/** 权限页需要 Activity 代劳的动作（涉及 startActivity / 权限回调，只能由 Activity 发起）。 */
interface PermissionHost {
    fun requestNotifPermission()
    fun requestBatteryWhitelist()
    fun openAutoStartSettings()

    /**
     * 厂商私有的「省电策略 / 后台运行限制」设置页。
     *
     * 与 [requestBatteryWhitelist] 是**两件事**：后者是 AOSP 的 Doze 白名单，
     * 前者是 ROM 自己的后台管控（MIUI 默认「智能限制后台」）。国产 ROM 上
     * 只申请前者照样会在锁屏后被掐掉网络 —— MIUI 上这一项才是「划掉任务服务就停」的根因。
     */
    fun openPowerSaveSettings()

    /** 申请蓝牙权限（充电线控制用）。 */
    fun requestBlePermissions()
}

/**
 * 设置页 —— 原来的「配置」与「权限」两个页签合并而来。
 *
 * 合并的理由：两者都是"把事情配好"的一次性动作，而且强相关 ——
 * 代理监听能不能持久，前提就是权限那几项都放行了；分成两页反而逼用户来回对照。
 *
 * ## 为什么实现了 [Tile] 却只刷新一半
 *
 * 上半张卡全是输入框。若每秒重绑一次，用户正在敲端口号时会被覆盖 ——
 * 所以 [refresh] **只碰权限那一张卡**，输入框与开关只在首次创建和「保存并应用」后绑定。
 * 这个页面上"会变的东西"本来也只有权限状态（用户可能刚去系统设置里改完再切回来）。
 */
class SettingsFragment : Fragment(), Tile {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    private val host get() = activity as? PermissionHost

    private val modeLabels = listOf(
        "auto（智能判断，默认）",
        "socket（强制直连）",
        "fetch（强制请求库）",
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _b = FragmentSettingsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()

        b.spMode.adapter = ArrayAdapter(
            ctx, android.R.layout.simple_spinner_item, modeLabels
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        bindConfigFields(ProxyRuntime.config)

        b.btnApply.setOnClickListener {
            val cfg = collectConfig()
            ConfigStore.save(ctx, cfg)
            val restarted = ProxyRuntime.applyConfig(cfg)
            if (cfg.watchdogEnabled) {
                ProxyScheduler.armWatchdog(ctx)
            } else {
                ProxyScheduler.cancelWatchdog(ctx)
            }
            // 回填一次，让夹取后的实际值（如端口越界被纠正）正确显示
            bindConfigFields(cfg)
            Toast.makeText(
                ctx,
                if (restarted) "已保存，监听已按新端口重启" else "已保存",
                Toast.LENGTH_SHORT,
            ).show()
        }

        b.btnPermNotif.setOnClickListener { host?.requestNotifPermission() }
        b.btnPermBattery.setOnClickListener { host?.requestBatteryWhitelist() }
        b.btnPermPower.setOnClickListener { host?.openPowerSaveSettings() }
        b.btnPermAuto.setOnClickListener { host?.openAutoStartSettings() }
        b.btnPermBle.setOnClickListener { host?.requestBlePermissions() }

        refresh()
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    // ------------------------------------------------------------------ 配置

    private fun bindConfigFields(cfg: ProxyConfig) {
        val binding = _b ?: return
        binding.etPort.setText(cfg.port.toString())
        binding.etToken.setText(cfg.token)
        binding.spMode.setSelection(ProxyConfig.MODES.indexOf(cfg.mode).coerceAtLeast(0))
        binding.etConcurrency.setText(cfg.maxConnections.toString())
        binding.etIdle.setText(cfg.idleTimeoutSec.toString())
        binding.etConnect.setText(cfg.connectTimeoutSec.toString())
        binding.etUa.setText(cfg.userAgentOverride)
        binding.swAllowOpen.isChecked = cfg.allowOpen
        binding.swAutoStart.isChecked = cfg.autoStartOnBoot
        binding.swWatchdog.isChecked = cfg.watchdogEnabled
        binding.swWakeLock.isChecked = cfg.holdWakeLock
    }

    private fun collectConfig(): ProxyConfig {
        val base = ProxyRuntime.config
        return base.copy(
            port = b.etPort.text.toString().trim().toIntOrNull() ?: base.port,
            token = b.etToken.text.toString().trim(),
            mode = ProxyConfig.MODES.getOrElse(b.spMode.selectedItemPosition) { ProxyConfig.MODE_AUTO },
            maxConnections = b.etConcurrency.text.toString().trim().toIntOrNull()
                ?: base.maxConnections,
            idleTimeoutSec = b.etIdle.text.toString().trim().toIntOrNull() ?: base.idleTimeoutSec,
            connectTimeoutSec = b.etConnect.text.toString().trim().toIntOrNull()
                ?: base.connectTimeoutSec,
            userAgentOverride = b.etUa.text.toString().trim(),
            allowOpen = b.swAllowOpen.isChecked,
            autoStartOnBoot = b.swAutoStart.isChecked,
            watchdogEnabled = b.swWatchdog.isChecked,
            holdWakeLock = b.swWakeLock.isChecked,
        )
    }

    // ------------------------------------------------------------------ 权限

    override fun refresh() {
        val binding = _b ?: return
        val ctx = requireContext()

        val notifOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        binding.tvPermNotif.text =
            if (notifOk) "已授权 · 常驻通知可见" else "未授权 · 通知栏看不到服务状态"
        binding.tvPermNotif.setTextColor(color(if (notifOk) R.color.ok else R.color.warn))
        binding.btnPermNotif.isEnabled = !notifOk

        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val battOk = pm.isIgnoringBatteryOptimizations(ctx.packageName)
        binding.tvPermBattery.text =
            if (battOk) "已加入白名单 · 后台不会被冻结" else "未加入 · 守护巡检可能被系统拒绝"
        binding.tvPermBattery.setTextColor(color(if (battOk) R.color.ok else R.color.warn))
        binding.btnPermBattery.isEnabled = !battOk

        // 省电策略是 ROM 私有的后台管控，**没有公开 API 可读**。
        // 所以这里不假装知道当前状态 —— 只如实说明本厂商需要怎么设，
        // 免得又出现「界面写着已生效、实际没生效」那类不诚实的标签。
        val vendor = VendorSettings.current()
        val advice = VendorSettings.powerSaveAdvice(vendor)
        binding.tvPermPower.text = advice ?: "当前 ROM 无已知入口，若后台被限制请查系统设置"
        binding.tvPermPower.setTextColor(color(if (advice != null) R.color.warn else R.color.muted))

        // 自启/巡检这两项是"开关状态的投影"。故意读**开关**而不是已存盘的配置：
        // 用户刚拨完开关还没点保存时，这里若显示旧值，他会以为开关没生效。
        // 于是再加一句「有改动未保存」，把两件事说清。
        val cfg = ProxyRuntime.config
        val autoNow = binding.swAutoStart.isChecked
        val wdNow = binding.swWatchdog.isChecked
        val dirty = autoNow != cfg.autoStartOnBoot || wdNow != cfg.watchdogEnabled
        binding.tvPermAuto.text = buildString {
            append(if (autoNow) "开机自启已开" else "开机自启已关")
            append(" · ")
            append(if (wdNow) "守护巡检已开" else "守护巡检已关")
            if (dirty) append("（有改动未保存）") else append("（仍需 ROM 放行自启动）")
        }
        binding.tvPermAuto.setTextColor(color(if (dirty) R.color.warn else R.color.muted))

        // 蓝牙权限：只在用充电线页时才需要，但状态必须如实 ——
        // 「未授权」时去充电线页点按钮会直接失败，事先在这里说清楚能省一轮排查。
        val bleOk = BlePermissions.allGranted(ctx)
        binding.tvPermBle.text =
            if (bleOk) "已授权 · 充电线页可以扫描设备" else "未授权 · 充电线页无法连接设备"
        binding.tvPermBle.setTextColor(color(if (bleOk) R.color.ok else R.color.warn))
        binding.btnPermBle.isEnabled = !bleOk

        val running = ProxyRuntime.snapshot().running

        val hints = ArrayList<String>(4)
        if (!notifOk) {
            hints.add("通知未授权：前台服务照常运行，但通知栏看不到状态与「停止」按钮。")
        }
        if (!battOk) {
            hints.add("未加入电池优化白名单：Android 12+ 从后台拉起前台服务会被系统拒绝，守护巡检可能失效。")
        }
        if (running && !cfg.holdWakeLock) {
            hints.add("未持有唤醒锁：息屏且长时间无请求时，连接可能被系统挂起。")
        }
        if (!bleOk && CableRuntime.snapshot.autoEnabled) {
            hints.add("自动充电控制开着但蓝牙权限缺失：每轮检查都会失败，请先授予蓝牙权限。")
        }
        if (hints.isEmpty()) {
            binding.tvPermHint.visibility = View.GONE
        } else {
            binding.tvPermHint.visibility = View.VISIBLE
            binding.tvPermHint.text = hints.joinToString("\n")
        }
    }

    private fun color(id: Int): Int = ContextCompat.getColor(requireContext(), id)
}
