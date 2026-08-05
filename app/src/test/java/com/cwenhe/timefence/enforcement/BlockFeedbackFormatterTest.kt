package com.cwenhe.timefence.enforcement

import com.cwenhe.timefence.rules.ScheduleRule
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockFeedbackFormatterTest {
    private val now = ZonedDateTime.of(2026, 1, 5, 9, 0, 0, 0, ZoneId.of("Asia/Shanghai"))

    /** 验证空模板使用默认文本并正确替换应用和结束时间。 */
    @Test
    fun `空模板使用默认提示`() {
        val feedback = BlockFeedbackFormatter.create(listOf(rule()), "video.app", "视频", now)

        assertEquals("已限制视频，10:00前不可使用", feedback?.text)
    }

    /** 验证自定义模板的三个占位符使用同一次拦截上下文替换。 */
    @Test
    fun `自定义模板替换全部占位符`() {
        val feedback = BlockFeedbackFormatter.create(
            listOf(rule().copy(notificationMessage = "{rule}：{app} 已暂停到 {until}")),
            "video.app",
            "视频",
            now,
        )

        assertEquals("专注：视频 已暂停到 10:00", feedback?.text)
    }

    /** 验证未声明的占位符保持原样，避免静默删除用户文本。 */
    @Test
    fun `未知占位符保持原样`() {
        val feedback = BlockFeedbackFormatter.create(
            listOf(rule().copy(notificationMessage = "{unknown}-{app}")),
            "video.app",
            "视频",
            now,
        )

        assertEquals("{unknown}-视频", feedback?.text)
    }

    /** 验证渲染结果按 Unicode 码点限制为 120 个字符。 */
    @Test
    fun `渲染文本不会超过长度上限`() {
        val feedback = BlockFeedbackFormatter.create(
            listOf(rule().copy(notificationMessage = "测".repeat(130))),
            "video.app",
            "视频",
            now,
        )
        val text = requireNotNull(feedback).text

        assertEquals(120, text.codePointCount(0, text.length))
    }

    /** 验证同一反馈在冷却期内只允许播报一次，换文本可立即播报。 */
    @Test
    fun `语音节流按反馈键和时间工作`() {
        var nowMillis = 1_000L
        val throttle = SpeechThrottle(clockMillis = { nowMillis }, cooldownMillis = 10_000L)

        assertTrue(throttle.shouldSpeak("a"))
        assertFalse(throttle.shouldSpeak("a"))
        assertTrue(throttle.shouldSpeak("b"))
        assertFalse(throttle.shouldSpeak("a"))
        nowMillis += 10_000L
        assertTrue(throttle.shouldSpeak("b"))
    }

    /** 验证用户从关闭语言重新打开总开关时会恢复可用的系统语言。 */
    @Test
    fun `重新启用语音会退出关闭语言`() {
        val settings = SpeechSettings(enabled = false, language = SpeechLanguage.OFF)

        assertEquals(
            SpeechSettings(enabled = true, language = SpeechLanguage.SYSTEM),
            settings.withEnabled(true),
        )
    }

    /** 构造一条覆盖当前测试时刻的标准规则。 */
    private fun rule(): ScheduleRule = ScheduleRule(
        id = 1,
        name = "专注",
        startMinute = 8 * 60,
        endMinute = 10 * 60,
        days = setOf(DayOfWeek.MONDAY),
        packages = setOf("video.app"),
        enabled = true,
        lockWhileActive = false,
    )
}
