package com.freeapi.proxy.cable

import android.content.Context
import androidx.core.content.edit
import com.freeapi.proxy.BuildConfig

/**
 * 充电线控制的设置，存在独立的 SharedPreferences（`freeapi_cable`）里。
 *
 * **为什么不塞进 [com.freeapi.proxy.ProxyConfig]**：那份配置在「设置」页是**全量覆写**的，
 * 把充电线的东西混进去，用户改一次代理端口就会把它们一并覆写成旧值。
 * 两者生命周期也不同 —— 代理配置是"静态参数"，这里是"运行态 + 用户意图"。
 *
 * 读出的值全部过一遍 [CablePolicy] 的校验，所以外部拿到的一定是合法值：
 * 即使 SP 里是旧版本留下的脏数据，也不会出现「低阈值 ≥ 高阈值」导致来回开关充电。
 */
class CablePrefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("freeapi_cable", Context.MODE_PRIVATE)

    /**
     * 是否按电量自动开关充电线。
     *
     * **默认 false**，这点与 autoLine 不同：那个 App 的全部用途就是这条线，默认开是合理的；
     * 而本 App 的主业是代理，默认开着会在用户根本没接充电线时就拉起一个前台服务
     * 去反复扫描 BLE 设备 —— 既费电，也是一条不诚实的常驻通知。
     */
    var autoEnabled: Boolean
        get() = sp.getBoolean(KEY_AUTO, false)
        set(value) = sp.edit { putBoolean(KEY_AUTO, value) }

    /**
     * 已知的线缆状态（"ON" / "OFF"）；null = 还不知道。
     * 用来避免重复下发同一条指令 —— 18% 时发了 OFF，下一轮 17% 就不该再发一次。
     */
    var lastLineState: String?
        get() = sp.getString(KEY_LAST_STATE, null)
        set(value) = sp.edit { putString(KEY_LAST_STATE, value) }

    /** 低阈值（≤ 它开始充电）与高阈值（≥ 它停止充电），读写都过校验。 */
    var thresholds: CablePolicy.Thresholds
        get() = CablePolicy.sanitize(
            low = sp.getInt(KEY_LOW_PERCENT, CablePolicy.DEFAULT_LOW_PERCENT),
            high = sp.getInt(KEY_HIGH_PERCENT, CablePolicy.DEFAULT_HIGH_PERCENT),
        )
        set(value) {
            val fixed = CablePolicy.sanitize(value.low, value.high)
            sp.edit {
                putInt(KEY_LOW_PERCENT, fixed.low)
                putInt(KEY_HIGH_PERCENT, fixed.high)
            }
        }

    /** 多久检查一次电量（分钟），读取时过校验。 */
    var intervalMin: Int
        get() = CablePolicy.clampInterval(
            sp.getInt(KEY_INTERVAL_MIN, CablePolicy.DEFAULT_INTERVAL_MIN)
        )
        set(value) = sp.edit { putInt(KEY_INTERVAL_MIN, CablePolicy.clampInterval(value)) }

    /**
     * 每日充电时段（跨午夜自动处理，如 22:00 – 次日 06:00）。
     *
     * 与 [autoEnabled] 一样**默认关闭**：默认开着会把「只在夜里充」变成一条用户没要求过的
     * 约束，白天电量掉下去却不充 —— 现象上跟"功能坏了"一模一样，比不生效更难排查。
     *
     * 起止时刻按「从 00:00 起的分钟数」存，读出来一律过 [CablePolicy.sanitizeWindow] 归一化，
     * 所以即使 SP 里被写进脏数据（比如 1500 分钟），也不会变成越界的时刻。
     */
    var chargeWindow: CablePolicy.ChargeWindow
        get() = CablePolicy.sanitizeWindow(
            CablePolicy.ChargeWindow(
                enabled = sp.getBoolean(KEY_WINDOW_ENABLED, false),
                startMin = sp.getInt(KEY_WINDOW_START_MIN, CablePolicy.DEFAULT_WINDOW_START_MIN),
                endMin = sp.getInt(KEY_WINDOW_END_MIN, CablePolicy.DEFAULT_WINDOW_END_MIN),
            )
        )
        set(value) {
            val fixed = CablePolicy.sanitizeWindow(value)
            sp.edit {
                putBoolean(KEY_WINDOW_ENABLED, fixed.enabled)
                putInt(KEY_WINDOW_START_MIN, fixed.startMin)
                putInt(KEY_WINDOW_END_MIN, fixed.endMin)
            }
        }

    /**
     * 目标 BLE 设备名 —— 必须与固件里广播的名字一致。
     *
     * 做成可配置而不是写死在 [BuildConfig.BLE_NAME]：固件是自己烧的，
     * 换个名字（或者同时有两根线想分开控制）就完全连不上了，
     * 而现场既没有日志也没有提示，只有一个「扫描超时」。
     */
    var deviceName: String
        get() = sp.getString(KEY_DEVICE_NAME, null)
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.BLE_NAME
        set(value) {
            val v = value.trim()
            if (v.isEmpty()) sp.edit { remove(KEY_DEVICE_NAME) }
            else sp.edit { putString(KEY_DEVICE_NAME, v) }
        }

    private companion object {
        const val KEY_AUTO = "auto_enabled"
        const val KEY_LAST_STATE = "last_line_state"
        const val KEY_LOW_PERCENT = "low_percent"
        const val KEY_HIGH_PERCENT = "high_percent"
        const val KEY_INTERVAL_MIN = "interval_min"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_WINDOW_ENABLED = "window_enabled"
        const val KEY_WINDOW_START_MIN = "window_start_min"
        const val KEY_WINDOW_END_MIN = "window_end_min"
    }
}
