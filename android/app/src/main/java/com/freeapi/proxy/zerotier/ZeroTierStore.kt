package com.freeapi.proxy.zerotier

import android.content.Context
import android.content.SharedPreferences

/**
 * ZeroTier 相关设置的持久化。
 *
 * 与 `ConfigStore`（代理配置）分开存：ZeroTier 是"本机身份/网络成员关系"，
 * 跟代理的端口、Token 这些运行参数不属于同一类东西，混在一起以后改起来会互相牵连。
 *
 * 注意：**节点身份**（node identity）不在这里——它由 libzt 自己写在
 * `filesDir/zerotier/` 下；这里只记「用户想加入哪些网络」。
 */
object ZeroTierStore {

    private const val PREF = "freeapi_zerotier"
    private const val KEY_NETWORKS = "networks"
    private const val KEY_AUTO_START = "autoStart"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 已加入（期望加入）的网络 ID 列表，均为 16 位小写十六进制 */
    fun networks(ctx: Context): List<String> =
        sp(ctx).getString(KEY_NETWORKS, "")
            .orEmpty()
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun saveNetworks(ctx: Context, ids: List<String>) {
        sp(ctx).edit()
            .putString(KEY_NETWORKS, ids.map { it.trim().lowercase() }.distinct().joinToString(","))
            .apply()
    }

    /**
     * 是否让节点随代理启动 —— 本质是「用户希望节点运行」这一意图。
     *
     * 默认 **true**：已经加入过网络的用户，意图显然是让它常驻。
     *
     * 早先默认 false，代价很具体：App 被 ROM 清理后重开，代理恢复了、节点却没起来，
     * 表现为「代理明明是开着的，虚拟 IP 却不通」。而这个开关在页面下方不显眼，
     * 用户几乎不可能联想到是它 —— 是典型的"默认值把人坑了"。
     *
     * 写入时机：点「启动节点」→ true；点「停止节点」→ false。
     */
    fun autoStart(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_AUTO_START, true)

    fun setAutoStart(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_AUTO_START, on).apply()
    }
}
