package com.freeapi.proxy.zerotier

import android.content.Context
import com.freeapi.proxy.core.ProxyLog
import com.zerotier.sockets.ZeroTierNative
import com.zerotier.sockets.ZeroTierNode
import java.io.File

/**
 * ZeroTier 运行时（进程内单例）。
 *
 * 设计对齐 `ProxyRuntime`：UI 直接读进程内单例，不引入 Binder / IPC。
 *
 * ## 职责
 *
 * - 节点生命周期：`initFromStorage` → `start` → `join/leave`
 * - 状态快照：[state]，由内部监督线程周期刷新，UI 直接读
 * - 入站监听的挂/摘：网络就绪后通知宿主挂上 [ZtListener]，掉线后摘掉
 *
 * ## 与代理引擎的解耦
 *
 * 本对象**不直接持有** `ProxyEngine`。挂载/摘除监听器、读取当前端口这三件事
 * 通过 [listenerAttach] / [listenerDetach] / [currentPort] 三个注入点完成，
 * 由 `ProxyRuntime` 在初始化时填上。这样 `zerotier` 包不去反向依赖引擎的具体形态，
 * 也便于以后单独替换。
 *
 * ## 线程模型
 *
 * libzt 的 `zts_node_start()` 是非阻塞的（内部起线程后立即返回），但
 * `zts_node_is_online()` / `zts_addr_*` 这些查询会取 libzt 的全局锁，
 * 因此**所有原生调用都在监督线程上做**，绝不放在主线程或 UI 刷新路径里。
 */
object ZeroTierRuntime {

    // -----------------------------------------------------------------------
    // 可用性探测
    // -----------------------------------------------------------------------

    /**
     * libzt 原生库是否可用。
     *
     * 探测方式：触发 `ZeroTierNative` 的类初始化。它的静态块里做了两件事——
     * `System.loadLibrary("zt")` 和 `zts_init()`，任一失败都会抛错：
     * 前者抛 `UnsatisfiedLinkError`，后者抛 `ExceptionInInitializerError`。
     * **两者都是 `Error` 而不是 `Exception`**，所以这里必须 catch `Throwable`。
     */
    val available: Boolean by lazy { probe() }

    @Volatile
    var loadError: String? = null
        private set

    private fun probe(): Boolean = try {
        Class.forName(NATIVE_CLASS)
        true
    } catch (t: Throwable) {
        loadError = t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
        false
    }

    // -----------------------------------------------------------------------
    // 注入点（由 ProxyRuntime 填）
    // -----------------------------------------------------------------------

    @Volatile
    var log: ProxyLog? = null

    /** 把 ZeroTier 监听器挂到当前引擎上；返回是否挂载成功 */
    @Volatile
    var listenerAttach: (() -> Boolean)? = null

    /** 摘掉 ZeroTier 监听器 */
    @Volatile
    var listenerDetach: (() -> Unit)? = null

    /** 当前代理端口（监听器要用同一个端口） */
    @Volatile
    var currentPort: (() -> Int)? = null

    // -----------------------------------------------------------------------
    // 状态
    // -----------------------------------------------------------------------

    @Volatile
    var state: State = State.Unavailable
        private set

    /** 节点与网络状态快照 */
    sealed interface State {
        /** 原生库未接入 */
        data object Unavailable : State
        /** 库在，节点没启动 */
        data object Stopped : State
        /** 正在启动 / 上线中 */
        data object Starting : State
        /** 运行中 */
        data class Running(
            val nodeId: String,
            val online: Boolean,
            /** 代理监听端口（系统 socket 与 ZeroTier 监听器共用同一个端口） */
            val servicePort: Int,
            /**
             * 节点自身绑定的主 UDP 端口（即其它设备要发 HELLO 的目的端口）。
             * 0 表示尚未绑定 / 查询不可用。
             *
             * 单独暴露它的理由：这个端口**是否稳定**直接决定对端要不要花几分钟
             * 重新学习路径。UI 上把它显示出来，用户就能一眼分辨"我固定成功了"
             * 还是"还在用随机端口"。
             */
            val nodeUdpPort: Int = 0,
            /** 该 UDP 端口是否是我们主动固定的（false = libzt 随机挑的） */
            val nodeUdpPortPinned: Boolean = false,
            val networks: List<Net>,
        ) : State
        data class Failed(val message: String) : State
    }

    /** 单个已加入的网络 */
    data class Net(
        val id: String,
        /** 首次分配的 IPv4（未就绪时为 null） */
        val ipv4: String? = null,
        val mac: String? = null,
        /** 传输是否就绪——即该网络能否真正收发流量 */
        val transportReady: Boolean = false,
        /** 已记录，但节点尚未接受（服务未就绪 / 重试中） */
        val joinPending: Boolean = false,
    ) {
        /** 16 位十六进制格式化，如 8056c2e21c000001 */
        val displayId: String get() = id
    }

    /** 10 位节点 ID 的十六进制展示（ZeroTierNode.getId() 返回的是数字形式） */
    fun formatNodeId(raw: Long): String = java.lang.String.format("%010x", raw)

    // -----------------------------------------------------------------------
    // 内部状态
    // -----------------------------------------------------------------------

    private val lock = Any()

    @Volatile
    private var node: ZeroTierNode? = null

    @Volatile
    private var running = false

    @Volatile
    private var supervisor: Thread? = null

    /** 已加入的网络（用户期望加入的），保证顺序稳定 */
    private val joined = ArrayList<String>()

    /**
     * 已记录但**尚未被原生节点接受**的网络。
     *
     * 为什么需要它：`zts_net_join()` 内部走 `ACQUIRE_SERVICE(ZTS_ERR_SERVICE)`，
     * 而 `zts_node_start()` 是非阻塞的（起线程即返回）——启动瞬间服务还不算
     * `isRunning()`，此时 join 必然拿到 `-2`（ZTS_ERR_SERVICE）。
     * libzt 没有暴露「服务已就绪」的查询接口，所以只能排队 + 由监督线程重试。
     */
    private val pendingJoins = LinkedHashSet<String>()

    /** 已就该网络的 join 失败说过一次警告，避免每 4 秒刷屏 */
    private val joinWarned = HashSet<String>()

    /** "节点对象还没构造好"这件事只说一次，同样为了避免刷屏 */
    @Volatile
    private var gateWarned = false

    /** "代理引擎还没起来"这件事只说一次 */
    @Volatile
    private var hostWaitWarned = false

    /**
     * 代理引擎是否已就绪、可以承载 ZeroTier 入站监听。
     *
     * 由宿主 [com.freeapi.proxy.ProxyRuntime] 在引擎起停时维护。
     * 存在的意义是把「没地方可挂」与「挂载失败」区分开 —— 少了它，
     * 用户重启 App 后会看到「绑定失败」，而日志里一片空白，
     * 真实原因只是代理压根没启动。
     */
    @Volatile
    var hostReady: Boolean = false

    /** ZeroTier 侧监听器当前是否已挂上 */
    @Volatile
    private var listenerMounted = false

    /** 上次尝试挂载监听器的时刻，用于失败退避 */
    @Volatile
    private var lastAttachAttemptMs = 0L

    /** ZeroTier 监听器实际绑定到的地址，仅用于展示 */
    @Volatile
    var boundAddress: String? = null
        private set

    /**
     * 本次启动**请求**固定的节点 UDP 端口；0 表示交给 libzt 随机挑（候选端口全被占时）。
     *
     * 记录下来是为了在界面上如实区分「固定成功」与「回退成随机」——
     * 只读 `zts_node_get_port()` 的话两者都只是一个数字，用户看不出区别，
     * 也就无从判断"端口漂移"这个坑是否还埋着。
     */
    @Volatile
    private var requestedNodePort: Int = 0

    // -----------------------------------------------------------------------
    // 对外操作
    // -----------------------------------------------------------------------

    /** 读取持久化的网络列表并刷新状态。可在 UI 或服务启动时调用，幂等。 */
    @Synchronized
    fun prepare(ctx: Context) {
        val stored = ZeroTierStore.networks(ctx)
        synchronized(lock) {
            joined.clear()
            joined.addAll(stored)
        }
        if (!running) {
            state = if (available) State.Stopped else State.Unavailable
        }
    }

    /** 已加入的网络 ID 列表 */
    fun networks(): List<String> = synchronized(lock) { ArrayList(joined) }

    val isNodeRunning: Boolean get() = running

    /**
     * 启动节点。
     * @return 失败原因；null 表示已发出启动请求（真正上线还需数秒）
     */
    @Synchronized
    fun startNode(ctx: Context): String? {
        if (!available) {
            return "libzt 原生库不可用${loadError?.let { "：$it" } ?: ""}"
        }
        if (running) return null

        val appCtx = ctx.applicationContext
        val storage = File(appCtx.filesDir, "zerotier").apply { mkdirs() }

        val n = try {
            ZeroTierNode()
        } catch (t: Throwable) {
            val m = "创建节点失败：${t.javaClass.simpleName}: ${t.message}"
            state = State.Failed(m)
            return m
        }

        // initFromStorage 必须在 start 之前，见 ZeroTierNode 的文档注释
        val initRs = try {
            n.initFromStorage(storage.absolutePath)
        } catch (t: Throwable) {
            val m = "initFromStorage 异常：${t.javaClass.simpleName}: ${t.message}"
            state = State.Failed(m)
            return m
        }
        if (initRs != ZTS_OK) {
            val m = "initFromStorage 失败：返回 $initRs"
            state = State.Failed(m)
            return m
        }

        // 缓存开关：允许把对端/网络/身份信息写盘，重启后能更快恢复连通
        runCatching {
            n.initAllowPeerCache(true)
            n.initAllowNetworkCache(true)
            n.initAllowIdCache(true)
            n.initAllowRootsCache(true)
        }

        // ---- 固定节点自身的主 UDP 端口 ----
        //
        // 默认（端口 0）时 libzt 在 `NodeService::run()` 里从 [20000,45500] **随机**
        // 挑一个端口绑定，于是每次 App / 节点重启，本节点的源端口都会变
        // （实测漂移链：40208 → 27159 → 29267）。而对端（电脑、另一部手机）的
        // ZeroTier 路径缓存还指着旧端口，发来的 HELLO 全打在空端口上，
        // 只能等它自己重新学习路径 —— 实测这次重学的代价是 **244 秒**，
        // 期间的症状就是"重开 App 后 ZeroTier 地址怎么都不通"。
        //
        // 固定端口后对端路径持续有效，恢复从分钟级降到秒级。
        //
        // ⚠️ 端口被占时 libzt **不会退让**，而是直接致命退出
        // （`NodeService`: "cannot bind to local control interface port"），
        // 所以这里先探测可用性；候选端口全被占就退回 0（= 原来的随机行为，不会更糟）。
        val nodePort = pickNodeUdpPort()
        requestedNodePort = nodePort
        if (nodePort > 0) {
            val portRs = runCatching { n.initSetPort(nodePort.toShort()) }.getOrDefault(-1)
            if (portRs == ZTS_OK) {
                log?.info("节点 UDP 端口固定为 $nodePort（重启不变 · 对端无需重新学习路径）")
            } else {
                requestedNodePort = 0
                log?.warn("固定 UDP 端口 $nodePort 失败：返回 $portRs，本次退回随机端口")
            }
        } else {
            log?.warn(
                "候选 UDP 端口 ${PREFERRED_NODE_PORTS.joinToString("/")} 均被占用，" +
                    "本次使用随机端口 —— 重启后对端可能需要一两分钟重新学习路径"
            )
        }

        val startRs = try {
            n.start()
        } catch (t: Throwable) {
            val m = "节点启动异常：${t.javaClass.simpleName}: ${t.message}"
            state = State.Failed(m)
            return m
        }
        if (startRs != ZTS_OK) {
            val m = "节点启动失败：返回 $startRs"
            state = State.Failed(m)
            return m
        }

        node = n
        running = true
        state = State.Starting
        log?.info("ZeroTier 节点已启动 · 存储目录 ${storage.absolutePath}")

        // 重新加入已持久化的网络。
        //
        // 这里**不能**直接 join：`zts_node_start()` 起完线程就返回，此刻
        // `zts_service->isRunning()` 还是 false，join 会稳定拿到 -2（服务未就绪）。
        // 统一排进 pendingJoins，交给监督线程重试到成功为止。
        gateWarned = false
        val queued = synchronized(lock) {
            pendingJoins.clear()
            joinWarned.clear()
            for (id in joined) {
                if (parseId(id) == null) log?.warn("跳过非法 Network ID：$id")
                else pendingJoins.add(id)
            }
            pendingJoins.size
        }
        if (queued > 0) log?.info("已记录 $queued 个网络，等待节点就绪后加入")

        startSupervisor()
        return null
    }

    /** 停止节点。注意：libzt 只有一个进程级节点实例，停止后重启可能不被支持。 */
    @Synchronized
    fun stopNode() {
        if (!running) return
        running = false
        detachListener()
        runCatching { node?.stop() }
        node = null
        // 节点没了，排队记录也就失去意义；下次 startNode 会重新按持久化列表排队
        gateWarned = false
        synchronized(lock) {
            pendingJoins.clear()
            joinWarned.clear()
        }
        state = if (available) State.Stopped else State.Unavailable
        log?.info("ZeroTier 节点已停止")
    }

    /**
     * 加入网络：校验 → 持久化 → 若节点在跑则立即 join。
     * @return 错误信息；null 表示成功
     */
    fun join(ctx: Context, rawId: String): String? {
        val id = rawId.trim().lowercase()
        if (!isValidNetworkId(id)) return "Network ID 应为 16 位十六进制（当前 ${id.length} 位）"
        val l = parseId(id) ?: return "Network ID 无法解析为 64 位整数"
        if (networks().contains(id)) return "该网络已在列表中"

        synchronized(lock) { joined.add(id) }
        ZeroTierStore.saveNetworks(ctx, networks())

        val n = node
        if (n != null && running) {
            // 节点对象还没构造好就直接调 join 会段错误（见 nodeObjectReady），
            // 排队即可 —— 用户刚点完「启动节点」就点「加入」正是这个时序。
            if (!nodeObjectReady(n)) {
                synchronized(lock) { pendingJoins.add(id) }
                log?.info("节点尚在初始化，$id 已排队，就绪后自动加入")
                refresh()
                return null
            }
            val rs = runCatching { n.join(l) }.getOrElse {
                log?.warn("加入网络 $id 异常：${it.message}")
                refresh()
                return "join 异常：${it.message ?: it.javaClass.simpleName}"
            }
            if (rs == ZTS_OK) {
                log?.info("已请求加入网络 $id")
            } else if (rs == ZTS_ERR_SERVICE) {
                // 节点刚起来、服务还没就绪。用户无从修复，排队等监督线程重试。
                synchronized(lock) { pendingJoins.add(id) }
                log?.info("节点尚未就绪，$id 已排队，稍后自动加入")
            } else {
                log?.warn("加入网络 $id 失败：返回 $rs")
                refresh()
                return "join 返回错误码 $rs"
            }
        } else {
            log?.info("已记录网络 $id（节点未运行，启动后自动加入）")
        }
        refresh()
        return null
    }

    /**
     * 离开网络：从列表移除（即持久化删除），节点在跑时顺带 leave。
     * @return 错误信息；null 表示成功
     */
    fun leave(ctx: Context, rawId: String): String? {
        val id = rawId.trim().lowercase()
        if (!networks().contains(id)) return "该网络不在列表中"

        synchronized(lock) {
            joined.remove(id)
            pendingJoins.remove(id)
            joinWarned.remove(id)
        }
        ZeroTierStore.saveNetworks(ctx, networks())

        parseId(id)?.let { l ->
            // leave 同理会解引用 _node，也要过安全闸；没过就算了 ——
            // 反正已从列表和持久化里摘掉，startNode 不会再把它排进来。
            val n = node
            if (running && n != null && nodeObjectReady(n)) runCatching { n.leave(l) }
        }
        log?.info("已离开网络 $id")
        refresh()
        return null
    }

    /** 主动刷新一次状态快照 */
    fun refresh() {
        val n = node
        if (n == null || !running) {
            state = if (available) State.Stopped else State.Unavailable
            return
        }
        try {
            // getId() 在服务尚未运行时返回 ZTS_ERR_SERVICE(-2)（被提升成 uint64），
            // 直接格式化会显示 "fffffffffe" 这种假 ID，所以先过一遍合法性判断 ——
            // 顺便这也让「节点已启动但对象未就绪」这个中间态如实显示为 "—"
            val nodeId = if (nodeObjectReady(n)) formatNodeId(n.getId()) else "—"
            val online = runCatching { n.isOnline() }.getOrDefault(false)
            val nets = networks().map { netOf(n, it) }
            // 节点自己绑定的 UDP 端口。服务未运行时该接口返回 -2（ZTS_ERR_SERVICE），
            // 会被提升成 int 的负数，因此必须按值域过滤后再展示。
            val udpPort = runCatching { ZeroTierNative.zts_node_get_port() }
                .getOrDefault(0)
                .takeIf { it in 1..65535 } ?: 0
            state = State.Running(
                nodeId = nodeId,
                online = online,
                servicePort = currentPort?.invoke() ?: 0,
                nodeUdpPort = udpPort,
                nodeUdpPortPinned = requestedNodePort > 0 && udpPort == requestedNodePort,
                networks = nets,
            )
        } catch (t: Throwable) {
            // 原生层偶发异常不应该让整个页面崩掉，保留上一次快照
            log?.warn("刷新 ZeroTier 状态失败：${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // -----------------------------------------------------------------------
    // 内部实现
    // -----------------------------------------------------------------------

    private fun netOf(n: ZeroTierNode, id: String): Net {
        val l = parseId(id) ?: return Net(id)
        val assigned = runCatching {
            ZeroTierNative.zts_addr_is_assigned(l, ZeroTierNative.ZTS_AF_INET) == 1
        }.getOrDefault(false)

        // 只有确认分配了才去取地址——未分配时 zts_addr_get_str 会给出 0.0.0.0，
        // 直接展示会让人误以为已经拿到虚拟 IP
        val ip = if (assigned) {
            runCatching { n.getIPv4Address(l)?.hostAddress }.getOrNull()?.takeIf { it != ZERO_ADDR }
        } else {
            null
        }

        val mac = runCatching { n.getMACAddress(l) }.getOrNull()
            ?.takeIf { it.isNotBlank() && it != ZERO_MAC }

        val ready = runCatching { n.isNetworkTransportReady(l) }.getOrDefault(false)
        val pending = synchronized(lock) { id in pendingJoins }
        return Net(id = id, ipv4 = ip, mac = mac, transportReady = ready, joinPending = pending)
    }

    private fun startSupervisor() {
        if (supervisor?.isAlive == true) return
        supervisor = Thread({ superviseLoop() }, "zt-supervisor").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 监督线程：周期刷新状态，并按「网络是否就绪」挂/摘入站监听。
     *
     * 为什么要单独一个线程而不是搭 UI 的便车：UI 可能根本不在前台
     * （这正是常驻代理的常态），而监听器必须在网络就绪后立刻挂上。
     */
    private fun superviseLoop() {
        while (running) {
            runCatching { syncJoins() }
            runCatching { refresh() }
            runCatching { syncListener() }
            try {
                Thread.sleep(SUPERVISE_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
        // 退出前收尾
        runCatching { detachListener() }
    }

    /**
     * ⚠️ **安全闸**：判断原生节点对象是否已经构造好，可以安全地调 `join()` / `leave()`。
     *
     * 为什么必须有它 —— libzt 上游存在一个竞态窗口：
     *
     * ```
     * NodeService::run() {
     *     _run = true;                        // 第 215 行：先置运行标志
     *     ...                                  // 建目录、读身份文件（磁盘 IO）
     *     _node = new Node(...);              // 第 253 行：节点对象才被构造
     * }
     * ```
     *
     * 而 `ACQUIRE_SERVICE` 的守卫只看 `isRunning()`（就是读 `_run`）。于是在这两行
     * 之间调用 `zts_net_join()`，守卫会**放行**，然后 `NodeService::join()` 直接
     * `_node->join(...)` 解引用一个尚未构造的对象 → `pthread_mutex_lock` 拿到野地址
     * → **SIGSEGV**（实测崩溃点：Mutex.hpp:43 ← NodeService.cpp:1217）。
     *
     * 反观启动瞬间 `_run` 还是 false 时，守卫会挡住并干净返回 `-2` —— 这就是
     * "有时候返回 -2、有时候直接崩" 的原因。
     *
     * 可靠的探针是 `zts_node_get_id()`：`NodeService::getNodeId()` 内部为
     * `return _node ? _node->address() : 0x0;` —— `_node` 为空时干净返回 0。
     * 注意服务未运行时该接口返回 `ZTS_ERR_SERVICE(-2)`，被提升成 uint64 是个
     * 巨大值，所以判据是「落在 40 位节点地址区间内」，而不是简单的非零。
     *
     * 其余查询接口（`networkIsReady` / `addrIsAssigned` / `getMACAddress` 等）
     * 只锁 `_nets_m` 读 `_nets`，不碰 `_node`，因此对它们的调用无此风险。
     */
    private fun nodeObjectReady(n: ZeroTierNode): Boolean {
        val id = runCatching { n.getId() }.getOrDefault(0L)
        return id > 0L && id < NODE_ID_LIMIT
    }

    /**
     * 把排队中的网络交给原生节点，直到成功为止。
     *
     * 存在的理由：`zts_node_start()` 是**非阻塞**的（起完线程就返回），
     * 刚启动那一下 join 要么被守卫挡回 `-2`，要么撞上「`_run` 已为 true
     * 但 `_node` 还是 nullptr」的窗口而段错误（详见 [nodeObjectReady]）。
     * 因此统一排队 + 按监督节奏重试，且**每次动手前先过安全闸**。
     *
     * 这是「用户加入过的网络重启节点后依然有效」的唯一保障 ——
     * 早期版本在 start() 之后一次性 join，日志里就是两条固定的
     * `加入网络 xxx 失败：返回 -2`，且永不补做。
     */
    /**
     * 选一个可用的节点 UDP 端口。
     *
     * 探测方式是 Java 侧的 `DatagramSocket` 试绑：libzt 绑的是同一个内核 UDP
     * 端口空间，结论一致。探测完立即 close，到 libzt 真正 bind 之间只有毫秒级
     * 窗口，实际撞车概率可忽略。
     *
     * @return 可用端口号；**0 表示"交给 libzt 自己随机挑"**。候选端口全被占用时
     *   必须如实退回 0 —— 硬塞一个绑不上的端口会让 libzt 直接致命退出
     *   （"cannot bind to local control interface port"），连节点都起不来。
     */
    private fun pickNodeUdpPort(): Int {
        for (p in PREFERRED_NODE_PORTS) {
            val free = try {
                java.net.DatagramSocket(p).use { true }
            } catch (_: Throwable) {
                false
            }
            if (free) return p
        }
        return 0
    }

    private fun syncJoins() {
        val n = node
        if (!running || n == null) return

        val batch = synchronized(lock) { ArrayList(pendingJoins) }
        if (batch.isEmpty()) return

        // 安全闸：节点对象没构造好之前绝不能碰 join，否则原生层直接段错误
        if (!nodeObjectReady(n)) {
            if (!gateWarned) {
                gateWarned = true
                log?.info("节点尚在初始化，${batch.size} 个网络排队等待就绪后加入")
            }
            return
        }
        gateWarned = false

        for (id in batch) {
            val l = parseId(id)
            if (l == null) {
                synchronized(lock) { pendingJoins.remove(id) }
                log?.warn("跳过非法 Network ID：$id")
                continue
            }

            val rs = runCatching { n.join(l) }.getOrDefault(-1)
            when {
                rs == ZTS_OK -> {
                    synchronized(lock) {
                        pendingJoins.remove(id)
                        joinWarned.remove(id)
                    }
                    log?.info("已请求加入网络 $id")
                }
                rs == ZTS_ERR_SERVICE -> {
                    // 服务还没起来，下一轮继续。只留一次痕，避免每 4 秒刷屏。
                    val first = synchronized(lock) { joinWarned.add(id) }
                    if (first) log?.info("网络 $id 等待节点就绪，就绪后自动加入")
                }
                else -> {
                    val first = synchronized(lock) { joinWarned.add(id) }
                    if (first) log?.warn("加入网络 $id 失败：返回 $rs（仍会重试）")
                }
            }
        }
    }

    /**
     * 挂/摘入站监听。
     *
     * **判据是「节点是否在跑」，不是「网络是否就绪」** —— 因为监听器绑的是
     * lwIP 上的通配地址 `0.0.0.0`（见 [ZtListener] 类注释第 1 条），
     * 绑定本身不依赖任何 ZeroTier 网络被加入或拿到虚拟 IP。
     *
     * 这样做的好处：
     *  - 少一次"等就绪"的时序依赖，没有 4 秒延迟
     *  - 网络后加入、虚拟 IP 重新分配都自动被覆盖，监听器不用重建
     *  - 端口被占之类的绑定失败会立刻暴露在界面上，而不是等到网络就绪才失败
     */
    private fun syncListener() {
        if (!running) {
            if (listenerMounted) detachListener()
            return
        }
        if (listenerMounted) return

        // 宿主引擎还没起来。这时"挂不上"不是失败，而是**没有地方可挂** ——
        // 关键是不要消耗退避窗口：否则引擎稍后起来了，还得白等一整分钟才能挂上，
        // 而日志里一片空白、界面上却显示"绑定失败"，谁也看不出真相。
        if (!hostReady) {
            if (!hostWaitWarned) {
                hostWaitWarned = true
                log?.info("代理引擎未运行，ZeroTier 入站监听待命")
            }
            return
        }
        hostWaitWarned = false

        val attach = listenerAttach ?: return

        // 节点对象还没构造好时，lwIP 协议栈同样没准备好，此时 bind 必然失败 ——
        // 实测报「ZeroTier 监听器绑定 0.0.0.0:8788 失败：Error while connecting to
        // remote host (-2)」。这和 join 撞的是**同一个初始化窗口**，属于瞬态，
        // 不是「绑定失败」：若在这里就消耗 60 秒退避窗口，节点起来后监听要白等
        // 一分钟才挂得上，期间从 ZeroTier 进来的流量全丢，日志里还留一条误导性的
        // 「创建失败」。所以先过和 join 同一道安全闸，没就绪就直接返回、不记账。
        val n = node
        if (n == null || !nodeObjectReady(n)) return

        // 真正的绑定失败（例如端口被占）不要每 4 秒重试刷日志，隔一会儿再试
        val now = System.currentTimeMillis()
        if (now - lastAttachAttemptMs < ATTACH_RETRY_INTERVAL_MS) return
        lastAttachAttemptMs = now

        val ok = attach.invoke()
        if (ok) {
            listenerMounted = true
            log?.info("ZeroTier 入站监听已就绪${boundAddress?.let { " · $it" } ?: ""}")
        }
    }

    private fun detachListener() {
        if (!listenerMounted) return
        listenerMounted = false
        boundAddress = null
        runCatching { listenerDetach?.invoke() }
    }

    /** 供宿主反查：ZeroTier 监听器当前是否挂着 */
    val isListenerMounted: Boolean get() = listenerMounted

    /**
     * 节点对象是否已构造完成 —— 即 lwIP 协议栈能不能用的前提。
     *
     * 供界面区分「还在初始化」与「真的绑不上」：两者在 [isListenerMounted] 上都是
     * false，含义却完全不同。混为一谈就会出现「界面写绑定失败、日志里一行都没有」
     * 那种不诚实的显示。
     */
    val isNodeReady: Boolean
        get() {
            val n = node ?: return false
            return running && nodeObjectReady(n)
        }

    /**
     * 宿主引擎被销毁（停止 / 因端口变化重启）时调用。
     *
     * 必须调用，否则这里的 [listenerMounted] 会与真实状态脱节——监听器其实
     * 已经随引擎关了，但监督线程还以为是挂着的，于是永远不会重挂。
     */
    fun onHostReset() {
        listenerMounted = false
        boundAddress = null
    }

    /** 宿主挂载成功后回报实际绑定地址，仅用于展示 */
    fun noteBoundAddress(addr: String?) {
        boundAddress = addr
    }

    // -----------------------------------------------------------------------
    // 常量与工具
    // 注意：这里是 standalone object，不能再套 companion object（Kotlin 不允许），
    // 所以常量和 isValidNetworkId / parseId 直接作为对象成员。
    // -----------------------------------------------------------------------

    private const val NATIVE_CLASS = "com.zerotier.sockets.ZeroTierNative"
    private const val ZTS_OK = 0

    /**
     * `ZTS_ERR_SERVICE = -2`（见 libzt `Events.hpp` / C# 绑定常量表）。
     * 由 `ACQUIRE_SERVICE` 在「服务存在但尚未 isRunning()」时返回——典型的
     * 触发场景就是 `zts_node_start()` 之后的几秒内。属于可重试的瞬时错误，
     * 不该当成用户可修复的失败暴露出去。
     */
    private const val ZTS_ERR_SERVICE = -2

    /**
     * ZeroTier 节点地址是 40 位（10 位十六进制），所以合法的 `zts_node_get_id()`
     * 结果必然落在 `(0, 2^40)` 内。用于把 `ZTS_ERR_SERVICE` 之类被提升成
     * uint64 的错误码（`0xFFFFFFFFFFFFFFFE`）筛掉。
     */
    private const val NODE_ID_LIMIT = 1L shl 40
    private const val ZERO_ADDR = "0.0.0.0"
    private const val ZERO_MAC = "00:00:00:00:00:00"
    private const val SUPERVISE_INTERVAL_MS = 4_000L
    private const val ATTACH_RETRY_INTERVAL_MS = 60_000L

    /**
     * 节点主 UDP 端口的候选序列。
     *
     * 首选 **9993** —— ZeroTier 官方客户端的历史默认端口，处在非特权区间
     * （>1024，无需 root），且绝大多数环境不会被别的程序占用；万一被占，
     * 依次退到 9994 / 9995，最后才放弃去随机。
     *
     * 注意：这个端口是**本节点对外的源端口**，与代理监听端口（默认 8788）
     * 毫无关系 —— 后者是 TCP 上的业务端口，前者是 UDP 上的 P2P 端口。
     */
    private val PREFERRED_NODE_PORTS = intArrayOf(9993, 9994, 9995)

    /** ZeroTier Network ID 固定 16 位十六进制 */
    fun isValidNetworkId(id: String): Boolean =
        id.length == 16 && id.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }

    /**
     * 16 位十六进制 → 64 位整数。
     *
     * **必须用 `parseUnsignedLong`**：ZeroTier 的 Network ID 是 64 位无符号语义，
     * 像 `8056c2e21c000001` 这种首位 ≥ 8 的值会超出 `Long.MAX_VALUE`，
     * 用 `parseLong(s, 16)` 会直接抛 NumberFormatException。
     */
    fun parseId(id: String): Long? = try {
        java.lang.Long.parseUnsignedLong(id.trim(), 16)
    } catch (_: Throwable) {
        null
    }
}
