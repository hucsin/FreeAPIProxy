package com.freeapi.proxy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.freeapi.proxy.ProxyRuntime
import com.freeapi.proxy.R
import com.freeapi.proxy.databinding.FragmentZerotierBinding
import com.freeapi.proxy.zerotier.ZeroTierRuntime
import com.freeapi.proxy.zerotier.ZeroTierStore

/**
 * ZeroTier 页：启动内置节点、加入虚拟网络，让同网络内的其它设备访问本机代理端口。
 *
 * 数据来自 [ZeroTierRuntime]（进程内单例），由它的监督线程每 4 秒刷新一次；
 * 本页的 [refresh] 只是把快照渲染出来，**不触发任何原生调用**——
 * 因为这个函数是每秒被 `MainActivity` 的 ticker 调一次的，
 * 而 libzt 的状态查询要取全局锁，放在刷新路径里会拖慢 UI。
 */
class ZeroTierFragment : Fragment(), Tile {

    private var _b: FragmentZerotierBinding? = null
    private val b get() = _b!!

    /** 开关状态回填时抑制监听器，避免把程序化设置误当成用户操作 */
    private var suppressSwitchCallback = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _b = FragmentZerotierBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        // 载入持久化的网络列表（幂等）
        ZeroTierRuntime.prepare(ctx)

        b.btnZtToggle.setOnClickListener { onToggleNode() }

        b.btnJoin.setOnClickListener {
            val id = b.etNetId.text.toString().trim()
            when {
                id.isEmpty() -> toast("请先填写 Network ID")
                !ZeroTierRuntime.isValidNetworkId(id) ->
                    toast("Network ID 应为 16 位十六进制（当前 ${id.length} 位）")
                else -> {
                    val err = ZeroTierRuntime.join(ctx, id)
                    if (err == null) {
                        b.etNetId.setText("")
                        toast("已加入 $id")
                    } else {
                        toast(err)
                    }
                }
            }
        }

        b.btnLeave.setOnClickListener {
            val typed = b.etNetId.text.toString().trim().lowercase()
            val nets = ZeroTierRuntime.networks()
            val target = when {
                typed.isNotEmpty() && nets.contains(typed) -> typed
                nets.size == 1 -> nets.first()
                nets.isEmpty() -> {
                    toast("还没有加入任何网络")
                    return@setOnClickListener
                }
                else -> {
                    toast("有多个网络，请在输入框填写要离开的 Network ID")
                    return@setOnClickListener
                }
            }
            val err = ZeroTierRuntime.leave(ctx, target)
            if (err == null) toast("已离开 $target") else toast(err)
        }

        b.btnCopyZtEndpoint.setOnClickListener {
            val text = b.tvZtEndpoint.text.toString()
            if (text.isBlank() || text.startsWith("（")) {
                toast("还没有可复制的地址")
                return@setOnClickListener
            }
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("zerotier-endpoint", text))
            toast("地址已复制")
        }

        b.swZtAutoStart.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchCallback) return@setOnCheckedChangeListener
            ZeroTierStore.setAutoStart(ctx, checked)
        }

        refresh()
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    // -----------------------------------------------------------------------

    private fun onToggleNode() {
        val ctx = requireContext()
        if (ZeroTierRuntime.isNodeRunning) {
            // 记下「用户主动停节点」的意图：App 被系统清理后重开时不要自动拉起
            ZeroTierStore.setAutoStart(ctx, false)
            ZeroTierRuntime.stopNode()
            toast("节点已停止")
            refresh()
            return
        }
        if (!ZeroTierRuntime.available) {
            toast("libzt 原生库不可用${ZeroTierRuntime.loadError?.let { "：$it" } ?: ""}")
            return
        }
        // 记下「要它跑」的意图。这一步是必需的：不记的话，App 被 ROM 清理后重开，
        // 代理恢复了而节点没起来，症状是"代理明明开着、虚拟 IP 却不通"，
        // 而原因藏在这个不显眼的开关里，用户几乎不可能联想到。
        ZeroTierStore.setAutoStart(ctx, true)
        // 原生调用不阻塞（zts_node_start 内部起线程即返回），失败会立刻返回原因
        val err = ZeroTierRuntime.startNode(ctx)
        if (err == null) {
            toast("节点启动中，上线约需数秒")
        } else {
            toast(err)
        }
        refresh()
    }

    override fun refresh() {
        val binding = _b ?: return

        val state = ZeroTierRuntime.state
        val available = ZeroTierRuntime.available

        val dotColor: Int
        val statusText: String
        var nodeId = "—"
        var port = "—"
        var nodeUdpPort = "—"
        var endpoint = "（加入网络后显示）"
        var toggleLabel = "启动节点"

        // 「已加入网络」的真相来源是**持久化列表**，不是原生快照。
        // 节点没跑时原生快照必然是空的，但用户加入过的网络依旧有效；
        // 若此时按快照显示 0，就会让人误以为记录丢了。
        val joinedIds = ZeroTierRuntime.networks()
        var netCount = joinedIds.size.toString()
        var netsText =
            if (joinedIds.isEmpty()) "（尚未加入任何网络）"
            else joinedIds.joinToString("\n")

        when (state) {
            is ZeroTierRuntime.State.Unavailable -> {
                dotColor = R.color.err
                statusText = "未接入"
            }
            is ZeroTierRuntime.State.Stopped -> {
                dotColor = R.color.muted
                statusText = "已停止"
                toggleLabel = "启动节点"
            }
            is ZeroTierRuntime.State.Starting -> {
                dotColor = R.color.warn
                statusText = "启动中…"
                toggleLabel = "停止节点"
            }
            is ZeroTierRuntime.State.Failed -> {
                dotColor = R.color.err
                statusText = "启动失败"
                toggleLabel = "重试"
            }
            is ZeroTierRuntime.State.Running -> {
                dotColor = if (state.online) R.color.ok else R.color.warn
                statusText = if (state.online) "在线" else "离线（未触达根节点）"
                nodeId = state.nodeId
                port = state.servicePort.toString()
                // UDP 端口后面缀上「固定 / 随机」——这个区别对用户是有实际后果的：
                // 随机端口意味着每次重启节点，对端都要重新学习路径（实测可达 4 分钟）。
                nodeUdpPort = when {
                    state.nodeUdpPort <= 0 -> "—"
                    state.nodeUdpPortPinned -> "${state.nodeUdpPort}（固定）"
                    else -> "${state.nodeUdpPort}（随机）"
                }
                toggleLabel = "停止节点"

                // 节点在跑：用原生快照把每个网络补上 IP / MAC / 传输就绪
                if (state.networks.isNotEmpty()) {
                    netsText = state.networks.joinToString("\n") { net ->
                        val ip = net.ipv4 ?: "等待分配"
                        val ready = when {
                            net.transportReady -> "就绪"
                            net.joinPending -> "等待节点就绪"
                            else -> "未就绪"
                        }
                        buildString {
                            append(net.displayId)
                            append("\n  IP ").append(ip)
                            net.mac?.let { append("  MAC ").append(it) }
                            append("  · ").append(ready)
                        }
                    }
                    // 优先用「传输就绪并已分配地址」的那个网络
                    val usable = state.networks.firstOrNull {
                        it.transportReady && !it.ipv4.isNullOrEmpty()
                    } ?: state.networks.firstOrNull { !it.ipv4.isNullOrEmpty() }
                    if (usable != null) {
                        endpoint = "http://${usable.ipv4}:${ProxyRuntime.config.listenPort}"
                    }
                }
            }
        }

        // 入站监听状态：这是"外界到底能不能连进来"的直接答案。
        // 监听器绑的是 lwIP 上的通配地址，所以节点一起来就该挂上；
        // 若节点在跑却仍未挂载，多半是绑定失败（端口被占等），看日志页。
        val listenText = when {
            ZeroTierRuntime.isListenerMounted ->
                ZeroTierRuntime.boundAddress ?: "已挂载"
            !ZeroTierRuntime.isNodeRunning -> "未挂载（节点未运行）"
            // 引擎没跑 ≠ 绑不上。混为一谈会让人去查端口占用，白费功夫。
            !ZeroTierRuntime.hostReady -> "未挂载（代理未启动）"
            // 节点刚启动那 1~2 秒 lwIP 还没就绪，此时绑不上是**正常过渡态**，
            // 不是失败 —— 若这里写「绑定失败」，用户会去查日志，而日志里什么都没有。
            !ZeroTierRuntime.isNodeReady -> "未挂载（节点初始化中）"
            else -> "未挂载（绑定失败，详见日志）"
        }

        // ZeroTier 入站事实：这条比任何"状态推断"都可靠 ——
        // 收到过连接就说明虚拟网这条路是通的，没收到就说明问题在网络层
        // （未授权 / 路径未建立），而不是代理或鉴权。两者排查方向完全不同。
        val ztIn = ProxyRuntime.snapshot()
        val inboundText = if (ztIn.ztConnections <= 0L) {
            "尚未收到"
        } else {
            "${ztIn.ztConnections} 次 · 最近 ${clock(ztIn.ztLastAtMs)}"
        }

        binding.dotZt.backgroundTintList = ColorStateList.valueOf(color(dotColor))
        binding.tvZtStatus.text = statusText
        binding.tvZtStatus.setTextColor(
            color(if (dotColor == R.color.ok) R.color.ok else R.color.brand_dark)
        )
        binding.tvZtNodeId.text = nodeId
        binding.tvZtPort.text = port
        binding.tvZtNodePort.text = nodeUdpPort
        // 固定 = 绿（对端路径长期有效）；随机 = 橙（重启后对端要重学路径）
        binding.tvZtNodePort.setTextColor(
            color(
                when {
                    state !is ZeroTierRuntime.State.Running -> R.color.brand_dark
                    state.nodeUdpPortPinned -> R.color.ok
                    else -> R.color.warn
                }
            )
        )
        binding.tvZtInbound.text = inboundText
        binding.tvZtInbound.setTextColor(color(if (ztIn.ztConnections > 0L) R.color.ok else R.color.muted))
        binding.tvZtNetCount.text = netCount
        binding.tvZtNets.text = netsText
        binding.tvZtEndpoint.text = endpoint
        binding.tvZtListen.text = listenText
        binding.btnZtToggle.text = toggleLabel

        // 原生库不可用时，输入与动作全部禁用（灰态），避免"点了没反应"
        binding.btnZtToggle.isEnabled = available
        binding.btnJoin.isEnabled = available
        binding.btnLeave.isEnabled = available
        binding.etNetId.isEnabled = available
        binding.swZtAutoStart.isEnabled = available

        suppressSwitchCallback = true
        binding.swZtAutoStart.isChecked = ZeroTierStore.autoStart(requireContext())
        suppressSwitchCallback = false

        binding.tvZtNote.text = when {
            !available ->
                "libzt 原生库加载失败：${ZeroTierRuntime.loadError ?: "未知原因"}。" +
                    "（该 APK 只打包了 arm64-v8a，x86 模拟器或 32 位设备会加载不到）"
            state is ZeroTierRuntime.State.Failed ->
                "错误：${state.message}"
            !ZeroTierRuntime.isNodeRunning && joinedIds.isEmpty() ->
                "节点未运行，也还没有加入任何网络。先在 my.zerotier.com 创建网络，" +
                    "把 16 位 Network ID 填进来加入。"
            !ZeroTierRuntime.isNodeRunning && ZeroTierStore.autoStart(requireContext()) ->
                "已记录 ${joinedIds.size} 个网络，节点已设为自动运行 —— " +
                    "到「仪表盘」把代理启动起来，节点会随之一并恢复" +
                    "（App 被系统清理后重新打开也一样）。也可直接点右上角「启动节点」。"
            !ZeroTierRuntime.isNodeRunning ->
                "已记录 ${joinedIds.size} 个网络，节点启动后会按此列表自动加入。" +
                    "要拿到虚拟 IP，先点右上角「启动节点」。"
            ZeroTierRuntime.isNodeRunning && !ZeroTierRuntime.hostReady ->
                "提示：节点在跑，但代理没启动 —— 入站监听得挂在代理引擎上。" +
                    "到「仪表盘」把代理开起来，监听会自动挂载（无需再动这里）。"
            ZeroTierRuntime.isNodeRunning && !ZeroTierRuntime.isListenerMounted ->
                "提示：节点在跑，但入站监听没能挂上——通常是代理端口被占用。" +
                    "到「日志」页看具体原因。"
            state is ZeroTierRuntime.State.Running && state.networks.any { it.joinPending } ->
                "提示：${state.networks.count { it.joinPending }} 个网络正在等待节点就绪后加入，" +
                    "通常几秒内完成。libzt 的服务启动是非阻塞的，刚启动那一下还接受不了加入请求。"
            // 端口回退成随机 —— 这正是"重启后要等几分钟才通"的根因，必须显式说出来，
            // 否则用户只会看到"端口改变"这个中性的数字，联想不到它和连不上有什么关系。
            state is ZeroTierRuntime.State.Running &&
                state.nodeUdpPort > 0 && !state.nodeUdpPortPinned ->
                "注意：节点当前使用随机 UDP 端口（${state.nodeUdpPort}）。候选端口 " +
                    "9993 / 9994 / 9995 全被占用时会出现这种情况，代价是每次重启节点后，" +
                    "其它设备要花一两分钟重新学习路径才能连上。腾出其中一个端口即可恢复固定。"
            ZeroTierRuntime.isNodeRunning && ZeroTierRuntime.isListenerMounted &&
                ztIn.ztConnections <= 0L ->
                "入站监听已就绪，但还没有收到过 ZeroTier 连接。若对端连不上，按顺序排查：" +
                    "① my.zerotier.com 里本节点是否已勾选 Auth 授权；② 对端是否也在同一网络；" +
                    "③ 仪表盘「鉴权」是否为「全拒」—— 未设 Token 且未允许无鉴权时会拒绝一切请求。"
            ZeroTierRuntime.isNodeRunning && ZeroTierRuntime.networks().isEmpty() ->
                "提示：节点已在线。请在 my.zerotier.com 创建网络并填入 16 位 Network ID 加入，" +
                    "并在控制台勾选 Auth 授权本节点，之后这里会显示分配到的虚拟 IP。"
            else ->
                "提示：其它设备需加入同一网络并访问上方地址。" +
                    "ZeroTier 侧改动后状态有延迟，稍等几秒。"
        }
    }

    /** 时刻格式化：界面只展示"最近一次入站发生在几点"，不需要日期 */
    private fun clock(ts: Long): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(ts))

    private fun color(id: Int): Int = ContextCompat.getColor(requireContext(), id)

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}
