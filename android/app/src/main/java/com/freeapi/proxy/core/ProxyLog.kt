package com.freeapi.proxy.core

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 环形日志缓冲：只保留最近 limit 条，version 变化供 UI 判断是否需要重绘。
 *
 * ## 双通道
 *
 * 每一条日志都同时走两路：
 *
 *  - **内存环形缓冲**（[snapshot] / [asText]）——给「日志」页展示，容量有限、随进程消亡
 *  - **logcat**（tag `FreeAPI`）——给真机排查用
 *
 * 第二路是必需的，不是锦上添花：内存缓冲对 `adb` **完全不可见**
 * （既不落盘也不写 logcat），只能靠截图读界面。真机出问题时如果只看 logcat，
 * 会看到一片空白，从而误判成「App 根本没记日志」；反之只看界面则无法回溯。
 *
 * 读取方式：
 * ```
 * adb logcat -s FreeAPI:V          # 全部
 * adb logcat -s FreeAPI:W          # 只看警告与错误
 * adb logcat -d -s FreeAPI | tail  # 抓一次快照
 * ```
 * 各级别映射：INFO→`I`、TRAFFIC→`D`（量大，降一级避免淹没）、WARN→`W`、ERROR→`E`。
 */
class ProxyLog(private val limit: Int = 200) {

    enum class Level(val tag: String, val logcatPriority: Int) {
        INFO("INF", Log.INFO),
        TRAFFIC("REQ", Log.DEBUG),
        WARN("WRN", Log.WARN),
        ERROR("ERR", Log.ERROR)
    }

    data class Entry(val ts: Long, val level: Level, val msg: String)

    private val items = ArrayDeque<Entry>(limit + 1)

    @Volatile
    var version: Long = 0L
        private set

    @Synchronized
    fun add(level: Level, msg: String) {
        items.addLast(Entry(System.currentTimeMillis(), level, msg))
        while (items.size > limit) items.removeFirst()
        version++
        mirrorToLogcat(level, msg)
    }

    fun info(msg: String) = add(Level.INFO, msg)
    fun traffic(msg: String) = add(Level.TRAFFIC, msg)
    fun warn(msg: String) = add(Level.WARN, msg)
    fun error(msg: String) = add(Level.ERROR, msg)

    @Synchronized
    fun snapshot(): List<Entry> = ArrayList(items)

    @Synchronized
    fun asText(): String {
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        return items.joinToString("\n") { "${fmt.format(Date(it.ts))} [${it.level.tag}] ${it.msg}" }
    }

    @Synchronized
    fun clear() {
        items.clear()
        version++
    }

    /** 把一条日志镜像到 logcat。 */
    private fun mirrorToLogcat(level: Level, msg: String) {
        if (!logcatEnabled) return
        try {
            Log.println(level.logcatPriority, LOG_TAG, msg)
        } catch (_: Throwable) {
            // 脱离 Android 运行时的环境（纯 JVM 单测）里 Log 是空实现会抛错，
            // 日志本身不该成为故障源，直接忽略。
        }
    }

    companion object {
        /** logcat 过滤标签：`adb logcat -s FreeAPI:V` */
        const val LOG_TAG = "FreeAPI"

        /** 需要静音 logcat 时（例如批量压测）置 false，内存缓冲不受影响。 */
        @Volatile
        var logcatEnabled: Boolean = true
    }
}
