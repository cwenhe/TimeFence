package com.cwenhe.timefence.ui

import com.cwenhe.timefence.rules.ScheduleRule
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
