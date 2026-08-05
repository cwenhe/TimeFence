package com.cwenhe.timefence.rules

import com.cwenhe.timefence.calendar.CalendarSnapshot
import com.cwenhe.timefence.data.local.CalendarDayEntity
import java.time.DayOfWeek
import java.time.ZoneOffset
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证规则生效区间和时间边界的设备本地时间语义。 */
class ScheduleEvaluatorTest {
    private val evaluator = ScheduleEvaluator()
    private val zone = ZoneId.of("Asia/Shanghai")

    /** 验证同日规则在开始时刻生效，并在结束时刻立即失效。 */
    @Test
    fun `同日规则使用左闭右开区间`() {
        val rule = rule(startMinute = 8 * 60, endMinute = 10 * 60)

        assertFalse(evaluator.evaluate(at(7, 59), listOf(rule)).blockedPackages.contains("demo.app"))
        assertTrue(evaluator.evaluate(at(8, 0), listOf(rule)).blockedPackages.contains("demo.app"))
        assertTrue(evaluator.evaluate(at(9, 59), listOf(rule)).blockedPackages.contains("demo.app"))
        assertFalse(evaluator.evaluate(at(10, 0), listOf(rule)).blockedPackages.contains("demo.app"))
    }

    /** 验证跨午夜规则在次日结束前仍归属于前一天的重复星期。 */
    @Test
    fun `跨午夜规则归属于开始日`() {
        val rule = rule(startMinute = 22 * 60, endMinute = 7 * 60)

        assertTrue(evaluator.evaluate(at(22, 0), listOf(rule)).blockedPackages.contains("demo.app"))
        assertTrue(evaluator.evaluate(at(6, 59, day = 6), listOf(rule)).blockedPackages.contains("demo.app"))
        assertFalse(evaluator.evaluate(at(7, 0, day = 6), listOf(rule)).blockedPackages.contains("demo.app"))
    }

    /** 验证星期日开始的跨午夜规则可以延续到下一周星期一。 */
    @Test
    fun `跨午夜规则可以跨越周边界`() {
        val rule = rule(
            startMinute = 23 * 60,
            endMinute = 60,
            days = setOf(DayOfWeek.SUNDAY),
        )

        assertTrue(evaluator.evaluate(at(0, 30, day = 5), listOf(rule)).blockedPackages.contains("demo.app"))
        assertFalse(evaluator.evaluate(at(1, 0, day = 5), listOf(rule)).blockedPackages.contains("demo.app"))
    }

    /** 验证多条同时生效的规则会汇总包名并保留各自规则信息。 */
    @Test
    fun `同时生效规则的受限包名取并集`() {
        val first = rule(8 * 60, 10 * 60, packages = setOf("demo.app", "shared.app"))
        val second = rule(9 * 60, 11 * 60, packages = setOf("other.app", "shared.app")).copy(id = 2)

        val result = evaluator.evaluate(at(9, 30), listOf(first, second))

        assertEquals(listOf(first, second), result.activeRules)
        assertEquals(setOf("demo.app", "shared.app", "other.app"), result.blockedPackages)
    }

    /** 验证禁用和缺少必要集合的规则不会产生拦截或未来闹钟。 */
    @Test
    fun `不完整或禁用规则不参与计算`() {
        val disabled = rule(8 * 60, 10 * 60).copy(enabled = false)
        val noDays = rule(8 * 60, 10 * 60).copy(days = emptySet())
        val noPackages = rule(8 * 60, 10 * 60).copy(packages = emptySet())
        val zeroLength = rule(8 * 60, 8 * 60)

        val result = evaluator.evaluate(at(9, 0), listOf(disabled, noDays, noPackages, zeroLength))

        assertTrue(result.activeRules.isEmpty())
        assertTrue(result.blockedPackages.isEmpty())
        assertNull(result.nextBoundary)
    }

    /** 验证计算结果返回严格晚于当前时刻的最近开始或结束边界。 */
    @Test
    fun `返回当前时刻之后最近的规则边界`() {
        val rule = rule(startMinute = 8 * 60, endMinute = 10 * 60)

        assertEquals(at(8, 0), evaluator.evaluate(at(7, 30), listOf(rule)).nextBoundary)
        assertEquals(at(10, 0), evaluator.evaluate(at(8, 0), listOf(rule)).nextBoundary)
    }

    /** 验证本周边界结束后会找到下一周同一天的开始边界。 */
    @Test
    fun `一周内没有其他边界时返回下周开始时间`() {
        val rule = rule(startMinute = 8 * 60, endMinute = 10 * 60)

        assertEquals(at(8, 0, day = 12), evaluator.evaluate(at(10, 0), listOf(rule)).nextBoundary)
    }

    /** 验证夏令时缺口中的计划时间会落在缺口后的第一个有效时刻。 */
    @Test
    fun `夏令时缺口选择跳变后的第一个有效时刻`() {
        val newYork = ZoneId.of("America/New_York")
        val rule = rule(
            startMinute = 2 * 60 + 30,
            endMinute = 4 * 60,
            days = setOf(DayOfWeek.SUNDAY),
        )
        val beforeGap = ZonedDateTime.of(2026, 3, 8, 1, 0, 0, 0, newYork)
        val firstValidTime = ZonedDateTime.of(2026, 3, 8, 3, 0, 0, 0, newYork)

        assertEquals(firstValidTime, evaluator.evaluate(beforeGap, listOf(rule)).nextBoundary)
        assertTrue(evaluator.evaluate(firstValidTime, listOf(rule)).blockedPackages.contains("demo.app"))
    }

    /** 验证夏令时重复的本地开始时间明确选用第一次出现的 offset。 */
    @Test
    fun `夏令时重复时间选择第一次出现`() {
        val newYork = ZoneId.of("America/New_York")
        val rule = rule(
            startMinute = 90,
            endMinute = 150,
            days = setOf(DayOfWeek.SUNDAY),
        )
        val beforeOverlap = ZonedDateTime.of(2026, 11, 1, 0, 30, 0, 0, newYork)

        val nextBoundary = evaluator.evaluate(beforeOverlap, listOf(rule)).nextBoundary

        assertEquals(ZoneOffset.ofHours(-4), nextBoundary?.offset)
        assertEquals(1, nextBoundary?.hour)
        assertEquals(30, nextBoundary?.minute)
    }

    /** 验证重复小时的第二次本地时间仍属于第一次 offset 开始的真实区间。 */
    @Test
    fun `夏令时重复小时第二次出现时规则仍生效`() {
        val newYork = ZoneId.of("America/New_York")
        val rule = rule(
            startMinute = 90,
            endMinute = 150,
            days = setOf(DayOfWeek.SUNDAY),
        )
        val secondOccurrence = ZonedDateTime.ofLocal(
            java.time.LocalDateTime.of(2026, 11, 1, 1, 45),
            newYork,
            ZoneOffset.ofHours(-5),
        )

        val result = evaluator.evaluate(secondOccurrence, listOf(rule))

        assertTrue("demo.app" in result.blockedPackages)
        assertEquals(2, result.nextBoundary?.hour)
        assertEquals(30, result.nextBoundary?.minute)
    }

    /** 验证结束时间落入夏令时缺口时，会在缺口后的首个有效时刻结束。 */
    @Test
    fun `夏令时缺口中的结束时间向后解析`() {
        val newYork = ZoneId.of("America/New_York")
        val rule = rule(
            startMinute = 90,
            endMinute = 150,
            days = setOf(DayOfWeek.SUNDAY),
        )
        val beforeGap = ZonedDateTime.of(2026, 3, 8, 1, 45, 0, 0, newYork)
        val firstValidTime = ZonedDateTime.of(2026, 3, 8, 3, 0, 0, 0, newYork)

        assertEquals(firstValidTime, evaluator.evaluate(beforeGap, listOf(rule)).nextBoundary)
        assertFalse("demo.app" in evaluator.evaluate(firstValidTime, listOf(rule)).blockedPackages)
    }

    /** 验证跨午夜规则在次日生效期间仍返回本次结束边界。 */
    @Test
    fun `跨午夜生效期间返回次日结束边界`() {
        val rule = rule(startMinute = 22 * 60, endMinute = 7 * 60)
        val tuesdayMorning = at(6, 30, day = 6)

        assertEquals(at(7, 0, day = 6), evaluator.evaluate(tuesdayMorning, listOf(rule)).nextBoundary)
    }

    /** 验证同一瞬间切换设备时区后会按新的本地星期和时间重新求值。 */
    @Test
    fun `时区变化后按新的本地时间重新计算`() {
        val rule = rule(startMinute = 8 * 60, endMinute = 10 * 60)
        val shanghaiTime = at(9, 0)
        val newYorkTime = shanghaiTime.withZoneSameInstant(ZoneId.of("America/New_York"))

        assertTrue("demo.app" in evaluator.evaluate(shanghaiTime, listOf(rule)).blockedPackages)
        assertFalse("demo.app" in evaluator.evaluate(newYorkTime, listOf(rule)).blockedPackages)
    }

    /** 验证调休周末可以触发工作日规则，但不触发 A 股交易日规则。 */
    @Test
    fun `工作日和交易日使用独立日历状态`() {
        val calendar = calendarSnapshot()
        val workdayRule = rule(
            startMinute = 8 * 60,
            endMinute = 10 * 60,
            days = emptySet(),
            calendarMode = CalendarMode.CN_STATUTORY_WORKDAY,
        )
        val tradingDayRule = workdayRule.copy(
            id = 2,
            calendarMode = CalendarMode.CN_A_SHARE_TRADING_DAY,
        )

        assertTrue("demo.app" in evaluator.evaluate(at(8, 0, day = 4), listOf(workdayRule), calendar).blockedPackages)
        assertFalse("demo.app" in evaluator.evaluate(at(8, 0, day = 4), listOf(tradingDayRule), calendar).blockedPackages)
        assertTrue("demo.app" in evaluator.evaluate(at(8, 0, day = 5), listOf(tradingDayRule), calendar).blockedPackages)
    }

    /** 验证交易日跨午夜时仍按开始日判断，并在次日结束边界结束。 */
    @Test
    fun `交易日跨午夜规则归属于开始日期`() {
        val calendar = calendarSnapshot()
        val rule = rule(
            startMinute = 22 * 60,
            endMinute = 7 * 60,
            days = emptySet(),
            calendarMode = CalendarMode.CN_A_SHARE_TRADING_DAY,
        )

        assertTrue("demo.app" in evaluator.evaluate(at(23, 0, day = 5), listOf(rule), calendar).blockedPackages)
        assertTrue("demo.app" in evaluator.evaluate(at(6, 59, day = 6), listOf(rule), calendar).blockedPackages)
        assertFalse("demo.app" in evaluator.evaluate(at(7, 0, day = 6), listOf(rule), calendar).blockedPackages)
    }

    /** 验证日历覆盖范围外不会把未知日期猜成工作日或交易日。 */
    @Test
    fun `未知日历日期不激活规则`() {
        val rule = rule(
            startMinute = 8 * 60,
            endMinute = 10 * 60,
            days = emptySet(),
            calendarMode = CalendarMode.CN_STATUTORY_WORKDAY,
        )

        assertTrue(evaluator.evaluate(at(8, 0, day = 6), listOf(rule), CalendarSnapshot.empty()).blockedPackages.isEmpty())
    }

    /** 创建仅在测试日期所属星期生效的规则。 */
    private fun rule(
        startMinute: Int,
        endMinute: Int,
        days: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY),
        packages: Set<String> = setOf("demo.app"),
        calendarMode: CalendarMode = CalendarMode.WEEKLY,
    ): ScheduleRule = ScheduleRule(
        id = 1,
        name = "测试规则",
        startMinute = startMinute,
        endMinute = endMinute,
        days = days,
        packages = packages,
        enabled = true,
        lockWhileActive = false,
        calendarMode = calendarMode,
    )

    /** 构造覆盖 2026-01-01 至 2026-01-05 的最小日历快照供求值测试使用。 */
    private fun calendarSnapshot(): CalendarSnapshot = CalendarSnapshot.fromEntities(
        listOf(
            CalendarDayEntity("2026-01-01", false, false, 1, 0),
            CalendarDayEntity("2026-01-04", true, false, 1, 0),
            CalendarDayEntity("2026-01-05", true, true, 1, 0),
            CalendarDayEntity("2026-01-06", true, true, 1, 0),
        ),
    )

    /** 创建 2026 年首个星期一的指定本地时刻。 */
    private fun at(hour: Int, minute: Int, day: Int = 5): ZonedDateTime =
        ZonedDateTime.of(2026, 1, day, hour, minute, 0, 0, zone)
}
