package com.freeapi.proxy.cable

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * 手机自己的电量。
 *
 * 读的是 `ACTION_BATTERY_CHANGED` 这个**粘性广播** ——
 * `registerReceiver(null, filter)` 会立刻返回最近一次广播的内容，
 * 既不需要真的注册接收器，也不会漏掉事件。
 */
object BatteryInfo {

    private fun sticky(context: Context): Intent? =
        context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    /** 当前电量百分比（0..100）；读不到返回 -1。 */
    fun level(context: Context): Int {
        val intent = sticky(context) ?: return -1
        val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (raw < 0 || scale <= 0) return -1
        return Math.round(raw * 100f / scale)
    }

    /** 系统是否认为正在充电（只用于日志与展示，不参与决策）。 */
    fun isPlugged(context: Context): Boolean {
        val intent = sticky(context) ?: return false
        return intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }
}
