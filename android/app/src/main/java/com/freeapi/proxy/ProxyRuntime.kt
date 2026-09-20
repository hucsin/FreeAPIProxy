package com.freeapi.proxy

import android.content.Context
import com.freeapi.proxy.core.ProxyEngine
import com.freeapi.proxy.core.ProxyLog
import com.freeapi.proxy.core.ProxyStats
import com.freeapi.proxy.zerotier.ZeroTierRuntime
import com.freeapi.proxy.zerotier.ZtListener

/**
 * 进程内运行时。UI 与前台服务共享同一个引擎实例与统计对象，
 * 因此不需要 Binder / IPC 就能让界面秒级反映真实状态。
 *
 * 同时扮演 ZeroTier 与代理引擎之间的接线板：`ZeroTierRuntime` 需要「挂载监听器 /
 * 摘除监听器 / 读当前端口」三个能力，由这里注入（见 init 块），
 * 这样 `zerotier` 包不必反向依赖引擎的具体形态。
 */
object ProxyRuntime {

    val log = ProxyLog(240)
    val stats = ProxyStats()

    @Volatile
    private var engine: ProxyEngine? = null

    @Volatile
    var config: ProxyConfig = ProxyConfig()
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** 前台服务（ProxyService）是否还活着，与"代理是否在跑"分开看 */
    @Volatile
    var serviceAlive: Boolean = false

    /** 当前挂着的 ZeroTier 监听器（没挂时为 null） */
    @Volatile
    private var ztListener: ZtListener? = null

    val isRunning: Boolean get() = engine?.isRunning == true

    init {
        // ---- ZeroTier ↔ 引擎 的接线 ----
        ZeroTierRuntime.log = log
        ZeroTierRuntime.currentPort = { config.listenPort }
        ZeroTierRuntime.listenerAttach = { attachZtListener() }
        ZeroTierRuntime.listenerDetach = { detachZtListener() }
    }

    fun loadConfig(ctx: Context) {
        config = ConfigStore.load(ctx)
    }

    /** UI 保存配置后先落到运行时，服务/引擎随即可用 */
    fun stageConfig(cfg: ProxyConfig) {
        config = cfg
    }

    /** @return 失败原因；null 表示启动成功 */
    @Synchronized
    fun start(cfg: ProxyConfig): String? {
        engine?.stop()
        engine = null
        // 引擎销毁会连带关掉 ZeroTier 监听器，这里同步告知，否则监督线程
        // 会以为它还挂着，永远不会重挂（改端口重启时必踩）
        ztListener = null
        ZeroTierRuntime.onHostReset()
        // 旧引擎已停，ZeroTier 监督线程别再去尝试挂载
        ZeroTierRuntime.hostReady = false

        config = cfg
        val e = ProxyEngine(cfg, stats, log)
        val err = e.start()
        engine = if (err == null) e else null
        lastError = err
        // 引擎起来了才允许挂 ZeroTier 入站监听（否则监督线程会一直
        // 拿"引擎为空"当绑定失败，白耗退避窗口）
        ZeroTierRuntime.hostReady = (err == null)
        return err
    }

    @Synchronized
    fun stop() {
        engine?.stop()
        engine = null
        ztListener = null
        ZeroTierRuntime.onHostReset()
        ZeroTierRuntime.hostReady = false
        lastError = null
    }

    /** @return 是否需要重启监听（端口 / 并发上限变了） */
    @Synchronized
    fun applyConfig(cfg: ProxyConfig): Boolean {
        config = cfg
        val e = engine ?: return false
        val restarted = e.applyConfig(cfg)
        if (restarted) {
            ztListener = null
            ZeroTierRuntime.onHostReset()
        }
        return restarted
    }

    fun snapshot(): ProxyStats.Snapshot = stats.snapshot(isRunning)

    fun resetStats() = stats.reset()

    // -----------------------------------------------------------------------
    // ZeroTier 监听器挂载
    // -----------------------------------------------------------------------

    /**
     * 在**同一个端口**上再起一个 ZeroTier 监听器。
     *
     * 同端口不冲突的原因：系统监听走内核协议栈，ZeroTier 监听走 libzt 自带的
     * lwIP 用户态协议栈，两者是完全独立的地址空间与连接表。
     *
     * 绑 `0.0.0.0` 而不是具体的虚拟 IP：绑定不依赖"网络是否就绪"，
     * 且能自动覆盖之后才分配的地址。细节见 [ZtListener] 类注释。
     *
     * @return 是否挂载成功
     */
    private fun attachZtListener(): Boolean {
        val e = engine ?: return false
        val port = config.listenPort

        val listener = try {
            ZtListener(port = port, bindAddr = null)
        } catch (t: Throwable) {
            log.warn("ZeroTier 监听器创建失败（$port）：${t.message ?: t.javaClass.simpleName}")
            return false
        }

        if (!e.addListener(listener)) {
            runCatching { listener.close() }
            return false
        }

        ztListener = listener
        ZeroTierRuntime.noteBoundAddress(listener.boundAddress)
        log.info("ZeroTier 入口已监听 ${listener.boundAddress}（lwIP 用户态协议栈）")
        return true
    }

    private fun detachZtListener() {
        val l = ztListener ?: return
        ztListener = null
        engine?.removeListener(l.label)
        ZeroTierRuntime.noteBoundAddress(null)
    }

    /** 当前生效的入站监听入口（UI 展示用） */
    fun activeListeners(): List<String> = engine?.activeListeners() ?: emptyList()
}
