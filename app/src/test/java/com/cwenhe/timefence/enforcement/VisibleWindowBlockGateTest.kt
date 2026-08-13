package com.cwenhe.timefence.enforcement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleWindowBlockGateTest {
    /** 验证时界自身作为受限包时也能被窗口门禁选中并去重。 */
    @Test
    fun `时界自身窗口可以按规则处理`() {
        val gate = VisibleWindowBlockGate()
        val window = VisibleAppWindow("com.cwenhe.timefence")

        assertEquals(window, gate.next(listOf(window), setOf("com.cwenhe.timefence")))
        assertTrue(gate.markHomeSucceeded(window))
        assertNull(gate.next(listOf(window), setOf("com.cwenhe.timefence")))
    }

    /** 验证 HOME 成功后同一小窗持续可见时不会再次返回候选。 */
    @Test
    fun `同一窗口只处理一次`() {
        val gate = VisibleWindowBlockGate()
        val window = VisibleAppWindow("video.app")

        assertEquals(window, gate.next(listOf(window), setOf("video.app")))
        gate.markHomeSucceeded(window)
        assertNull(gate.next(listOf(window), setOf("video.app")))
    }

    /** 验证窗口离开后重新出现可以再次被拦截。 */
    @Test
    fun `窗口消失后允许再次处理`() {
        val gate = VisibleWindowBlockGate()
        val window = VisibleAppWindow("video.app")

        gate.markHomeSucceeded(window)
        assertNull(gate.next(emptyList(), setOf("video.app")))
        assertEquals(window, gate.next(listOf(window), setOf("video.app")))
    }

    /** 验证 HOME 失败时不写入处理记录，下次检查仍能重试。 */
    @Test
    fun `失败不阻断重试`() {
        val gate = VisibleWindowBlockGate()
        val window = VisibleAppWindow("video.app")

        assertEquals(window, gate.next(listOf(window), setOf("video.app")))
        assertEquals(window, gate.next(listOf(window), setOf("video.app")))
    }

    /** 验证同一包名的不同窗口按窗口 ID 独立处理，避免新窗口被旧状态误放行。 */
    @Test
    fun `同包名不同窗口独立处理`() {
        val gate = VisibleWindowBlockGate()
        val first = VisibleAppWindow("video.app", windowId = 1)
        val second = VisibleAppWindow("video.app", windowId = 2)

        gate.markHomeSucceeded(first)
        assertEquals(second, gate.next(listOf(first, second), setOf("video.app")))
    }

    /** 验证未受限小窗不会遮住后方受限主窗口的拦截候选。 */
    @Test
    fun `小窗存在时仍选择受限主窗口`() {
        val gate = VisibleWindowBlockGate()
        val floating = VisibleAppWindow("chat.app")
        val blocked = VisibleAppWindow("video.app")

        assertEquals(blocked, gate.next(listOf(floating, blocked), setOf("video.app")))
    }

    /** 验证规则结束后清空状态，下一次开始时同一可见窗口可重新拦截。 */
    @Test
    fun `规则重新生效后可以再次处理`() {
        val gate = VisibleWindowBlockGate()
        val window = VisibleAppWindow("video.app")

        gate.markHomeSucceeded(window)
        assertNull(gate.next(listOf(window), emptySet()))
        assertEquals(window, gate.next(listOf(window), setOf("video.app")))
    }

    /** 验证 HOME 被系统接收但窗口仍活动时会有界重试，而不是永久放行或无限反馈。 */
    @Test
    fun `活动窗口在退避后最多重试三次`() {
        var nowMillis = 1_000L
        val gate = VisibleWindowBlockGate(clockMillis = { nowMillis })
        val window = VisibleAppWindow("video.app", windowId = 7, isActive = true)

        assertEquals(window, gate.next(listOf(window), setOf("video.app")))
        assertTrue(gate.markHomeSucceeded(window))
        assertNull(gate.next(listOf(window), setOf("video.app")))

        nowMillis += 250L
        assertEquals(window, gate.next(listOf(window), setOf("video.app")))
        assertFalse(gate.markHomeSucceeded(window))
        nowMillis += 250L
        assertEquals(window, gate.next(listOf(window), setOf("video.app")))
        assertFalse(gate.markHomeSucceeded(window))
        nowMillis += 250L
        assertNull(gate.next(listOf(window), setOf("video.app")))
    }

    /** 验证同一窗口退到后台后再次获得焦点，会开始新一轮拦截和反馈。 */
    @Test
    fun `持续可见窗口重新激活后再次处理`() {
        val gate = VisibleWindowBlockGate()
        val active = VisibleAppWindow("video.app", windowId = 9, isActive = true)
        val inactive = active.copy(isActive = false)

        gate.markHomeSucceeded(active)
        assertNull(gate.next(listOf(inactive), setOf("video.app")))
        assertEquals(active, gate.next(listOf(active), setOf("video.app")))
        assertTrue(gate.markHomeSucceeded(active))
    }

    /** 验证厂商在 fallback 与真实窗口 ID 间切换时不会重置三次重试预算。 */
    @Test
    fun `窗口身份切换仍共用有界重试状态`() {
        var nowMillis = 1_000L
        val gate = VisibleWindowBlockGate(clockMillis = { nowMillis })
        val fallback = VisibleAppWindow("video.app", windowId = null, isActive = true)
        val identified = fallback.copy(windowId = 12)

        assertEquals(fallback, gate.next(listOf(fallback), setOf("video.app")))
        assertTrue(gate.markHomeSucceeded(fallback))
        nowMillis += 250L
        assertEquals(identified, gate.next(listOf(identified), setOf("video.app")))
        assertFalse(gate.markHomeSucceeded(identified))
        nowMillis += 250L
        assertEquals(fallback, gate.next(listOf(fallback), setOf("video.app")))
        assertFalse(gate.markHomeSucceeded(fallback))
        nowMillis += 250L
        assertNull(gate.next(listOf(identified), setOf("video.app")))
    }
}
