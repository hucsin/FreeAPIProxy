package com.freeapi.proxy

import android.content.Context
import android.content.SharedPreferences

/**
 * 「运行意图」持久化 —— 用户**希望**代理处于运行状态吗？
 *
 * 刻意与 [ConfigStore] 分开存，原因很具体：
 * 配置页保存时是**全量覆写**（`ConfigStore.save` 把每个字段都写一遍）。
 * 如果把运行意图塞进 [ProxyConfig]，用户在配置页改个端口就会连带把它覆写成
 * 载入时的旧值 —— 意图被静默抹掉，而问题要到下次 App 被杀后才暴露。
 * 配置是"静态参数"，意图是"动态状态"，两者生命周期不同，不该同住。
 *
 * **为什么需要它**：MIUI / HyperOS 的「划掉任务」「一键清理」等价于 `force-stop` ——
 * 杀进程、撤销全部 AlarmManager 闹钟、清通知、标记 stopped。此后 App 收不到任何广播，
 * 守护闹钟与 `START_STICKY` 全部失效。**除"用户主动再次打开 App"外，
 * 没有任何第二条自愈路径**。所以打开 App 时必须知道自己该不该把服务拉起来。
 */
object RunState {

    private const val PREF = "freeapi_run_state"
    private const val KEY_PROXY_DESIRED = "proxyDesired"

    /**
     * 用户是否希望代理运行。
     *
     * 写入时机只认「用户的明确动作」：
     * - 点「启动代理」/「重启」 → true
     * - 点「停止代理」/ 通知栏「停止」 → false
     *
     * **不**在服务销毁时改写：进程被系统杀死时 [android.app.Service.onDestroy]
     * 未必执行，就算执行了也分不清"用户主动停"还是"系统回收"，改写只会丢意图。
     */
    fun proxyDesired(ctx: Context): Boolean =
        sp(ctx).getBoolean(KEY_PROXY_DESIRED, false)

    fun setProxyDesired(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_PROXY_DESIRED, on).apply()
    }

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
