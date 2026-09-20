package com.freeapi.proxy.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.freeapi.proxy.ConfigStore
import com.freeapi.proxy.ProxyRuntime
import com.freeapi.proxy.R
import com.freeapi.proxy.RunState
import com.freeapi.proxy.core.ProxyStats
import com.freeapi.proxy.databinding.FragmentDashboardBinding
import com.freeapi.proxy.service.ProxyService
import com.freeapi.proxy.zerotier.ZeroTierRuntime
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * 仪表盘：一眼看到「在不在跑」，并且能立刻启停。
 *
 * 把「状态」与「控制」合并到同一页是有意的——这是最高频的路径，不该埋进二级页面。
 */
class DashboardFragment : Fragment(), Tile {

    private var _b: FragmentDashboardBinding? = null
    private val b get() = _b!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _b = FragmentDashboardBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.btnToggle.setOnClickListener {
            val ctx = requireContext()
            if (ProxyRuntime.isRunning) {
                // 记下"用户主动停止"的意图 —— App 被杀后重开时据此判断不该自动恢复
                RunState.setProxyDesired(ctx, false)
                ProxyService.stop(ctx)
                toast("正在停止…")
            } else {
                RunState.setProxyDesired(ctx, true)
                val cfg = ConfigStore.load(ctx)
                ProxyRuntime.stageConfig(cfg)
                ProxyService.start(ctx)
                toast("正在启动…")
            }
            b.root.postDelayed({ refresh() }, 500)
        }

        b.btnRestart.setOnClickListener {
            val ctx = requireContext()
            // 重启意味着"要它跑"，意图记为真
            RunState.setProxyDesired(ctx, true)
            val cfg = ConfigStore.load(ctx)
            ProxyRuntime.stageConfig(cfg)
            if (ProxyRuntime.isRunning) {
                ProxyService.restart(ctx)
                toast("正在重启…")
            } else {
                ProxyService.start(ctx)
                toast("正在启动…")
            }
            b.root.postDelayed({ refresh() }, 800)
        }

        b.btnResetStats.setOnClickListener {
            ProxyRuntime.resetStats()
            refresh()
            toast("统计已清零")
        }

        refresh()
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    override fun refresh() {
        val binding = _b ?: return
        val s = ProxyRuntime.snapshot()
        val cfg = ProxyRuntime.config
        val running = s.running
        val failed = ProxyRuntime.lastError != null

        val dotColor = when {
            running -> R.color.ok
            failed -> R.color.err
            else -> R.color.muted
        }
        binding.dotStatus.backgroundTintList = ColorStateList.valueOf(color(dotColor))

        binding.tvStatus.text = when {
            running -> "运行中"
            failed -> "启动失败"
            ProxyService.isAlive -> "服务在 · 代理已停"
            else -> "已停止"
        }
        binding.tvStatus.setTextColor(color(if (running) R.color.ok else R.color.brand_dark))
        binding.tvUptime.text = if (running) "已运行 ${ProxyStats.humanUptime(s.uptimeMs)}" else "—"

        binding.tvPort.text = cfg.listenPort.toString()
        binding.tvMode.text = cfg.mode
        // 「全拒」不是中性状态，而是"任何请求都会被 401 挡回去"的故障态：
        // 未设 Token 且没允许无鉴权时，`Http.authOk()` 直接返回 false。
        // 所以它必须用错误色 + 一句成因，否则用户只会看到对端"访问不了"，
        // 从而跑去查网络 / ZeroTier，方向全错。
        val authBlocked = cfg.token.isEmpty() && !cfg.allowOpen
        binding.tvAuth.text = when {
            cfg.token.isNotEmpty() -> "Token 已设"
            cfg.allowOpen -> "无鉴权"
            else -> "全拒"
        }
        binding.tvAuth.setTextColor(color(if (authBlocked) R.color.err else R.color.brand_dark))
        binding.tvAuthWarn.visibility = if (authBlocked) View.VISIBLE else View.GONE
        if (authBlocked) {
            binding.tvAuthWarn.text =
                "鉴权为「全拒」：未设置 Token，也未开启「允许无鉴权访问」，" +
                    "因此所有请求（WiFi 与 ZeroTier 都一样）都会返回 401。" +
                    "到「设置」页设一个 Token，或临时打开「允许无鉴权访问」即可放行。"
        }

        binding.tvActive.text = "${s.active} / ${s.peakActive}"
        binding.tvRequests.text = "${s.totalConnections} / ${s.requests}"
        binding.tvFailed.text = "${s.failed} / ${s.authRejected}"
        binding.tvRoute.text = "${s.socketHits} / ${s.fetchHits} / ${s.failovers}"
        binding.tvBytes.text = ProxyStats.humanBytes(s.bytesUp) + " / " + ProxyStats.humanBytes(s.bytesDown)
        binding.tvTunnels.text = s.tunnels.toString()
        binding.tvEndpoint.text = endpointText(cfg.listenPort)

        binding.btnToggle.text = getString(if (running) R.string.btn_stop else R.string.btn_start)

        val err = ProxyRuntime.lastError ?: s.lastError
        if (err.isNullOrEmpty()) {
            binding.tvLastError.visibility = View.GONE
        } else {
            binding.tvLastError.visibility = View.VISIBLE
            binding.tvLastError.text = "最近错误：$err"
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun color(id: Int): Int = ContextCompat.getColor(requireContext(), id)

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    private data class Addr(val label: String, val ip: String)

    /**
     * 列出**每块网卡**的 IPv4，而不是只挑第一个。
     *
     * 为什么必须分开列：手机同时连着 WiFi、蜂窝，可能还有 VPN / USB；
     * `getNetworkInterfaces()` 的返回顺序没有任何保证。只显示一个的话，
     * 用户拿到的很可能是对方根本访问不到的地址——WiFi 的 `192.168.x.x` 看起来
     * 完全正常，但同 ZeroTier 网络的设备要用的是虚拟 IP。
     */
    private fun localAddresses(): List<Addr> {
        val out = ArrayList<Addr>()
        try {
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                val ip = Collections.list(ni.inetAddresses).firstOrNull {
                    it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress
                }?.hostAddress ?: continue
                out.add(Addr(labelOf(ni.name), ip))
            }
        } catch (_: Throwable) {
        }
        return out
    }

    private fun labelOf(name: String): String = when {
        name.startsWith("wlan") -> "WiFi"
        name.contains("swlan") || name.startsWith("ap") -> "热点"
        name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") -> "蜂窝"
        name.startsWith("zt") -> "ZeroTier"
        name.startsWith("tun") || name.startsWith("ppp") -> "VPN"
        name.startsWith("eth") || name.startsWith("usb") -> "USB/有线"
        else -> name
    }

    /**
     * 可访问地址清单。
     *
     * 两类来源必须分开看：
     *  - **内核网卡**：`NetworkInterface` 能枚举到的（WiFi / 蜂窝 / VPN / USB）
     *  - **应用内 ZeroTier 虚拟网**：libzt 是**用户态协议栈**，它分配的虚拟 IP
     *    **不会**出现在内核网卡列表里，只能从 [ZeroTierRuntime] 取。
     *    少了这一条，用户会以为地址没生效。
     */
    private fun endpointText(port: Int): String {
        val lines = ArrayList<String>()
        for (a in localAddresses()) {
            lines.add("http://${a.ip}:$port    （${a.label}）")
        }
        val ztIp = (ZeroTierRuntime.state as? ZeroTierRuntime.State.Running)
            ?.networks
            ?.firstOrNull { it.transportReady && !it.ipv4.isNullOrEmpty() }
            ?.ipv4
        if (ztIp != null) {
            lines.add("http://$ztIp:$port    （ZeroTier 虚拟网 · 应用内）")
        }
        return if (lines.isEmpty()) "http://127.0.0.1:$port" else lines.joinToString("\n")
    }
}
