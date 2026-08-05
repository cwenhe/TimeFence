package com.cwenhe.timefence.ui

import com.cwenhe.timefence.calendar.CalendarMatch
import com.cwenhe.timefence.calendar.CalendarSnapshot
import com.cwenhe.timefence.rules.CalendarMode
import com.cwenhe.timefence.rules.ScheduleRule
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.util.Locale

/** 将一天内分钟数格式化为本地 24 小时时间。 */
fun formatMinuteOfDay(minute: Int): String = String.format(
    Locale.getDefault(),
    "%02d:%02d",
    minute / MINUTES_PER_HOUR,
    minute % MINUTES_PER_HOUR,
)

/** 将规则星期集合压缩成便于扫描的中文标签。 */
fun formatWeekdays(days: Set<DayOfWeek>): String {
    if (days.size == DAYS_PER_WEEK) return "每天"
    if (days == WORKDAYS) return "工作日"
    if (days == WEEKEND) return "周末"
    return DayOfWeek.entries
        .filter(days::contains)
        .joinToString(" ") { day -> WEEKDAY_LABELS.getValue(day) }
}

/** 按规则模式格式化每周、法定工作日或 A 股交易日摘要。 */
fun formatSchedule(rule: ScheduleRule): String = when (rule.calendarMode) {
    CalendarMode.WEEKLY -> formatWeekdays(rule.days)
    CalendarMode.CN_STATUTORY_WORKDAY -> "法定工作日"
    CalendarMode.CN_A_SHARE_TRADING_DAY -> "A 股交易日"
}

/** 判断规则是否属于给定自然日，未知日历日期不显示为今日规则。 */
fun isRuleScheduledOn(
    rule: ScheduleRule,
    date: LocalDate,
    calendar: CalendarSnapshot,
): Boolean = when (rule.calendarMode) {
    CalendarMode.WEEKLY -> date.dayOfWeek in rule.days
    CalendarMode.CN_STATUTORY_WORKDAY,
    CalendarMode.CN_A_SHARE_TRADING_DAY,
    -> calendar.match(rule.calendarMode, date) == CalendarMatch.MATCH
}

/** 计算跨午夜规则距离本次结束还剩多少分钟。 */
fun remainingRuleMinutes(rule: ScheduleRule, now: ZonedDateTime): Int {
    val currentMinute = now.hour * MINUTES_PER_HOUR + now.minute
    return if (rule.startMinute < rule.endMinute) {
        rule.endMinute - currentMinute
    } else if (currentMinute >= rule.startMinute) {
        MINUTES_PER_DAY - currentMinute + rule.endMinute
    } else {
        rule.endMinute - currentMinute
    }
}

private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
private const val DAYS_PER_WEEK = 7
private val WORKDAYS = DayOfWeek.entries.take(5).toSet()
private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
private val WEEKDAY_LABELS = mapOf(
    DayOfWeek.MONDAY to "一",
    DayOfWeek.TUESDAY to "二",
    DayOfWeek.WEDNESDAY to "三",
    DayOfWeek.THURSDAY to "四",
    DayOfWeek.FRIDAY to "五",
    DayOfWeek.SATURDAY to "六",
    DayOfWeek.SUNDAY to "日",
)
