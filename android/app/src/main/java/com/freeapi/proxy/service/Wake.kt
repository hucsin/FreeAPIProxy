package com.freeapi.proxy.service

import android.content.Context
import android.os.PowerManager
import java.util.concurrent.ConcurrentHashMap

/**
 * 按名字管理唤醒锁，多把可并存。
 *
 * 三处要点：
 *  - `setReferenceCounted(false)`：重复 acquire 只续期，不会堆计数；
 *  - **每把锁都必须带超时**：万一漏了 release（进程被杀）也不会永久耗电；
 *  - 代理要"一直能被访问"，所以主锁由服务定期续期，而不是拿一次就撒手。
 */
object Wake {

    private val locks = ConcurrentHashMap<String, PowerManager.WakeLock>()

    @Synchronized
    fun acquire(ctx: Context, name: String, timeoutMs: Long) {
        val pm = ctx.applicationContext
            .getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val lock = locks.getOrPut(name) {
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FreeAPIProxy:$name")
                .apply { setReferenceCounted(false) }
        }
        runCatching { lock.acquire(timeoutMs.coerceAtLeast(1_000L)) }
    }

    @Synchronized
    fun release(name: String) {
        val lock = locks[name] ?: return
        runCatching { if (lock.isHeld) lock.release() }
    }

    @Synchronized
    fun isHeld(name: String): Boolean = locks[name]?.isHeld == true
}
