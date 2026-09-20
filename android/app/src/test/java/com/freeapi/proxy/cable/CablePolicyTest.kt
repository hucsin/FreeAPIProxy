package com.freeapi.proxy.cable

import com.freeapi.proxy.cable.CablePolicy.ChargeWindow
import com.freeapi.proxy.cable.CablePolicy.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CablePolicy] 的单测 —— 重点在**每日充电时段**这块。
 *
 * 时段判断有三种形态（未启用 / 同日 / 跨午夜），其中跨午夜最容易写错：
 * 「22:00–06:00」既不是 `m in start..end`，也不是简单的取反，
 * 而且在 06:00 整这个点上必须**立刻**算作窗口外（否则会多充一分钟）。
 * 这些边界光靠真机点几下是试不出来的，所以钉在测试里。
 *
 * 纯 Kotlin，不依赖任何 Android API，`./gradlew test` 直接在主机 JVM 上跑。
 */
class CablePolicyTest {

    /** 同日窗口：08:00 – 18:00 */
    private val day = ChargeWindow(true, 8 * 60, 18 * 60)

    /** 跨午夜窗口：22:00 – 次日 06:00（默认值） */
    private val night = ChargeWindow(true, 22 * 60, 6 * 60)

    // ── inWindow ────────────────────────────────────────────────────────

    @Test
    fun `未启用时段时恒为窗口内`() {
        assertTrue(CablePolicy.inWindow(0, ChargeWindow.ALWAYS))
        assertTrue(CablePolicy.inWindow(13 * 60, ChargeWindow.ALWAYS))
        assertTrue(CablePolicy.inWindow(23 * 60 + 59, ChargeWindow.ALWAYS))
    }

    @Test
    fun `同日窗口含起点不含终点`() {
        assertFalse("07:59 应在窗口外", CablePolicy.inWindow(7 * 60 + 59, day))
        assertTrue("08:00 应含起点", CablePolicy.inWindow(8 * 60, day))
        assertTrue("17:59 应在窗口内", CablePolicy.inWindow(17 * 60 + 59, day))
        assertFalse("18:00 不该含终点", CablePolicy.inWindow(18 * 60, day))
        assertFalse("23:00 应在窗口外", CablePolicy.inWindow(23 * 60, day))
    }

    @Test
    fun `跨午夜窗口两侧都算窗口内`() {
        assertFalse("21:59 在窗口外", CablePolicy.inWindow(21 * 60 + 59, night))
        assertTrue("22:00 进入窗口", CablePolicy.inWindow(22 * 60, night))
        assertTrue("23:59 仍在窗口内", CablePolicy.inWindow(23 * 60 + 59, night))
        assertTrue("00:00 跨过午夜仍在窗口内", CablePolicy.inWindow(0, night))
        assertTrue("05:59 仍在窗口内", CablePolicy.inWindow(5 * 60 + 59, night))
        assertFalse("06:00 整必须已出窗口", CablePolicy.inWindow(6 * 60, night))
        assertFalse("12:00 在窗口外", CablePolicy.inWindow(12 * 60, night))
    }

    @Test
    fun `起止相同视作全天而不是空窗口`() {
        // 空窗口意味着"永远不充电"，这种看起来正常实际致命的配置不该由一次误操作产生
        val same = ChargeWindow(true, 10 * 60, 10 * 60)
        assertTrue(CablePolicy.inWindow(3 * 60, same))
        assertTrue(CablePolicy.inWindow(10 * 60, same))
        assertTrue(CablePolicy.inWindow(22 * 60, same))
    }

    // ── minutesUntilWindowChange ────────────────────────────────────────

    @Test
    fun `边界间隔在跨午夜两侧都算对`() {
        assertEquals(60, CablePolicy.minutesUntilWindowChange(21 * 60, night) ?: -1)
        assertEquals(60, CablePolicy.minutesUntilWindowChange(5 * 60, night) ?: -1)
        // 23:00 -> 次日 06:00 还有 7 小时
        assertEquals(7 * 60, CablePolicy.minutesUntilWindowChange(23 * 60, night) ?: -1)
        // 06:00 刚出窗口 -> 距下一次进入（22:00）还有 16 小时
        assertEquals(16 * 60, CablePolicy.minutesUntilWindowChange(6 * 60, night) ?: -1)
    }

    @Test
    fun `边界间隔在同日窗口算对`() {
        assertEquals(60, CablePolicy.minutesUntilWindowChange(7 * 60, day) ?: -1)
        assertEquals(6 * 60, CablePolicy.minutesUntilWindowChange(12 * 60, day) ?: -1)
        // 23:00 距次日 08:00 还有 9 小时
        assertEquals(9 * 60, CablePolicy.minutesUntilWindowChange(23 * 60, day) ?: -1)
    }

    @Test
    fun `未启用时段时没有边界可等`() {
        assertNull(CablePolicy.minutesUntilWindowChange(12 * 60, ChargeWindow.ALWAYS))
    }

    @Test
    fun `任何时刻的边界间隔都至少一分钟`() {
        // 返回 0 或负数会让 AlarmManager 立刻触发，闹钟链会变成忙循环
        for (m in 0 until CablePolicy.MINUTES_PER_DAY) {
            val d = CablePolicy.minutesUntilWindowChange(m, night)
            assertTrue("第 $m 分钟算出 $d", d != null && d >= 1)
        }
    }

    // ── 文本 ────────────────────────────────────────────────────────────

    @Test
    fun `时段文案`() {
        assertEquals("22:00", CablePolicy.timeText(22 * 60))
        assertEquals("06:05", CablePolicy.timeText(6 * 60 + 5))
        assertEquals("00:00", CablePolicy.timeText(0))
        assertEquals("22:00 至次日 06:00", CablePolicy.windowText(night))
        assertEquals("08:00 至 18:00", CablePolicy.windowText(day))
        assertEquals("全天允许充电", CablePolicy.windowText(ChargeWindow.ALWAYS))
    }

    @Test
    fun `时长文案不夹取上限`() {
        assertEquals(8 * 60, CablePolicy.windowLengthMin(night))
        assertEquals(10 * 60, CablePolicy.windowLengthMin(day))
        // 关键：480 分钟不能被夹成 120 —— intervalText 会那么干，所以另写了一个
        assertEquals("8 小时", CablePolicy.durationText(8 * 60))
        assertEquals("10 小时", CablePolicy.durationText(10 * 60))
        assertEquals("1 小时 30 分钟", CablePolicy.durationText(90))
        assertEquals("45 分钟", CablePolicy.durationText(45))
    }

    // ── decide：时段外 ──────────────────────────────────────────────────

    @Test
    fun `时段外一律断电`() {
        val d = CablePolicy.decide(50, null, 20, 95, night, 12 * 60)
        assertEquals(CablePolicy.CMD_STOP_CHARGE, d.command)
        assertEquals(Reason.OUT_OF_WINDOW, d.reason)
    }

    @Test
    fun `时段外电量极低时保命充电`() {
        val d = CablePolicy.decide(3, null, 20, 95, night, 12 * 60)
        assertEquals(CablePolicy.CMD_START_CHARGE, d.command)
        assertEquals(Reason.KEEP_ALIVE, d.reason)
    }

    @Test
    fun `保命阈值含等号且刚过线就不保`() {
        assertEquals(
            CablePolicy.CMD_START_CHARGE,
            CablePolicy.decide(CablePolicy.KEEP_ALIVE_PERCENT, null, 20, 95, night, 12 * 60).command,
        )
        assertEquals(
            CablePolicy.CMD_STOP_CHARGE,
            CablePolicy.decide(CablePolicy.KEEP_ALIVE_PERCENT + 1, null, 20, 95, night, 12 * 60).command,
        )
    }

    @Test
    fun `时段外的决策不重复下发`() {
        assertNull(CablePolicy.decide(50, CablePolicy.CMD_STOP_CHARGE, 20, 95, night, 12 * 60).command)
        assertNull(CablePolicy.decide(3, CablePolicy.CMD_START_CHARGE, 20, 95, night, 12 * 60).command)
    }

    // ── decide：时段内 ──────────────────────────────────────────────────

    @Test
    fun `时段内沿用原来的阈值行为`() {
        val low = CablePolicy.decide(10, null, 20, 95, night, 23 * 60)
        assertEquals(CablePolicy.CMD_START_CHARGE, low.command)
        assertEquals(Reason.LOW, low.reason)

        val high = CablePolicy.decide(96, null, 20, 95, night, 23 * 60)
        assertEquals(CablePolicy.CMD_STOP_CHARGE, high.command)
        assertEquals(Reason.HIGH, high.reason)

        val mid = CablePolicy.decide(50, null, 20, 95, night, 23 * 60)
        assertNull(mid.command)
        assertEquals(Reason.NONE, mid.reason)
    }

    @Test
    fun `时段内仍受高阈值保护不会过充`() {
        // 这是选定的语义：时段只决定"能不能充"，充满了照样停
        assertEquals(
            CablePolicy.CMD_STOP_CHARGE,
            CablePolicy.decide(100, CablePolicy.CMD_START_CHARGE, 20, 95, night, 23 * 60).command,
        )
    }

    @Test
    fun `时段边界跨过去之后决策立刻翻转`() {
        // 21:59 还在窗口外 -> 断电；22:00 进入窗口且电量低 -> 充电
        assertEquals(
            CablePolicy.CMD_STOP_CHARGE,
            CablePolicy.decide(10, null, 20, 95, night, 21 * 60 + 59).command,
        )
        assertEquals(
            CablePolicy.CMD_START_CHARGE,
            CablePolicy.decide(10, null, 20, 95, night, 22 * 60).command,
        )
        // 05:59 还在窗口内；06:00 整必须已经断电
        assertNull(CablePolicy.decide(50, null, 20, 95, night, 5 * 60 + 59).command)
        assertEquals(
            CablePolicy.CMD_STOP_CHARGE,
            CablePolicy.decide(50, CablePolicy.CMD_START_CHARGE, 20, 95, night, 6 * 60).command,
        )
    }

    // ── 回归：不启用时段时与改造前完全一致 ──────────────────────────────

    @Test
    fun `未启用时段时行为与改造前一致`() {
        assertEquals(
            CablePolicy.CMD_START_CHARGE,
            CablePolicy.decide(10, null, 20, 95, ChargeWindow.ALWAYS, 3 * 60).command,
        )
        assertEquals(
            CablePolicy.CMD_STOP_CHARGE,
            CablePolicy.decide(96, null, 20, 95, ChargeWindow.ALWAYS, 3 * 60).command,
        )
        assertNull(CablePolicy.decide(50, null, 20, 95, ChargeWindow.ALWAYS, 3 * 60).command)
    }

    @Test
    fun `读不到电量时任何时段都跳过`() {
        assertEquals(
            Reason.NO_BATTERY,
            CablePolicy.decide(-1, null, 20, 95, night, 12 * 60).reason,
        )
        assertEquals(
            Reason.NO_BATTERY,
            CablePolicy.decide(-1, null, 20, 95, night, 23 * 60).reason,
        )
    }
}
