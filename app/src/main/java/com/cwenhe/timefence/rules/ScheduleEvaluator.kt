package com.cwenhe.timefence.rules

import com.cwenhe.timefence.calendar.CalendarMatch
import com.cwenhe.timefence.calendar.CalendarSnapshot
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.zone.ZoneRules

/** 使用调用方提供的设备本地时刻计算重复规则状态，不读取系统时钟。 */
class ScheduleEvaluator {
    /**
     * 计算 [now] 时刻的有效规则和最近未来边界。
     *
     * 开始边界包含在有效区间内，结束边界不包含；无星期、无应用或起止相同的
     * 不完整规则不会参与拦截和闹钟计算。
     */
    fun evaluate(
        now: ZonedDateTime,
        rules: List<ScheduleRule>,
        calendar: CalendarSnapshot = CalendarSnapshot.empty(),
    ): RuleEvaluation {
        val evaluableRules = rules.filter { it.enabled && it.isEvaluable() }
        val activeRules = activeRules(now, evaluableRules, calendar)
        val blockedPackages = activeRules.flatMapTo(linkedSetOf()) { it.packages }
        val nextBoundary = evaluableRules
            .asSequence()
            .flatMap { rule -> futureBoundaries(now, rule, calendar) }
            .minByOrNull { boundary -> boundary.toInstant() }

        return RuleEvaluation(
            activeRules = activeRules,
            blockedPackages = blockedPackages,
            nextBoundary = nextBoundary,
        )
    }

    /** 只计算当前有效规则，不枚举未来边界，避免窗口事件重复消耗时区计算。 */
    fun evaluateActive(
        now: ZonedDateTime,
        rules: List<ScheduleRule>,
        calendar: CalendarSnapshot = CalendarSnapshot.empty(),
    ): ActiveRuleEvaluation {
        val activeRules = activeRules(
            now = now,
            rules = rules.filter { it.enabled && it.isEvaluable() },
            calendar = calendar,
        )
        return ActiveRuleEvaluation(
            activeRules = activeRules,
            blockedPackages = activeRules.flatMapTo(linkedSetOf()) { it.packages },
        )
    }

    /** 判断规则是否具备可计算的日期、应用和非零时间区间。 */
    private fun ScheduleRule.isEvaluable(): Boolean =
        (calendarMode != CalendarMode.WEEKLY || days.isNotEmpty()) &&
            packages.isNotEmpty() &&
            startMinute != endMinute

    /** 统一计算当前时刻命中的规则，保证完整求值和热路径结果一致。 */
    private fun activeRules(
        now: ZonedDateTime,
        rules: List<ScheduleRule>,
        calendar: CalendarSnapshot,
    ): List<ScheduleRule> = rules.filter { rule -> isActive(now, rule, calendar) }

    /** 判断当前瞬间是否落入规则开始日对应的真实时区区间。 */
    private fun isActive(
        now: ZonedDateTime,
        rule: ScheduleRule,
        calendar: CalendarSnapshot,
    ): Boolean {
        val nowInstant = now.toInstant()
        return sequenceOf(now.toLocalDate().minusDays(1), now.toLocalDate())
            .filter { startDate -> rule.matchesStartDate(startDate, calendar) }
            .mapNotNull { startDate -> createInterval(startDate, rule, now.zone.rules, now) }
            .any { interval ->
                !nowInstant.isBefore(interval.start.toInstant()) &&
                    nowInstant.isBefore(interval.end.toInstant())
            }
    }

    /** 枚举未来规则边界；周规则看下一周，日历规则最多查看 370 天。 */
    private fun futureBoundaries(
        now: ZonedDateTime,
        rule: ScheduleRule,
        calendar: CalendarSnapshot,
    ): Sequence<ZonedDateTime> = sequence {
        val today = now.toLocalDate()
        val zoneRules = now.zone.rules
        val lastOffset = when (rule.calendarMode) {
            CalendarMode.WEEKLY -> DAYS_IN_WEEK
            else -> calendar.coveredTo()?.let { coveredTo ->
                java.time.temporal.ChronoUnit.DAYS.between(today, coveredTo)
                    .coerceAtMost(MAX_CALENDAR_LOOK_AHEAD_DAYS)
            } ?: return@sequence
        }
        for (dayOffset in PREVIOUS_DAY_OFFSET..lastOffset) {
            val startDate = today.plusDays(dayOffset)
            if (!rule.matchesStartDate(startDate, calendar)) continue

            val interval = createInterval(startDate, rule, zoneRules, now) ?: continue
            if (interval.start.toInstant().isAfter(now.toInstant())) yield(interval.start)
            if (interval.end.toInstant().isAfter(now.toInstant())) yield(interval.end)
        }
    }

    /** 判断规则开始日是否匹配星期、法定工作日或交易日数据。 */
    private fun ScheduleRule.matchesStartDate(
        startDate: LocalDate,
        calendar: CalendarSnapshot,
    ): Boolean = when (calendarMode) {
        CalendarMode.WEEKLY -> startDate.dayOfWeek in days
        CalendarMode.CN_STATUTORY_WORKDAY,
        CalendarMode.CN_A_SHARE_TRADING_DAY,
        -> calendar.match(calendarMode, startDate) == CalendarMatch.MATCH
    }

    /**
     * 将规则某个开始日解析为真实瞬间区间；时区跳变令区间为空时返回 null。
     */
    private fun createInterval(
        startDate: LocalDate,
        rule: ScheduleRule,
        zoneRules: ZoneRules,
        zoneSource: ZonedDateTime,
    ): RuleInterval? {
        val endDate = if (rule.endMinute < rule.startMinute) startDate.plusDays(1) else startDate
        val start = resolveBoundary(startDate, rule.startMinute, zoneRules, zoneSource)
        val end = resolveBoundary(endDate, rule.endMinute, zoneRules, zoneSource)
        return if (start.toInstant().isBefore(end.toInstant())) RuleInterval(start, end) else null
    }

    /**
     * 将本地分钟解析到明确瞬间：缺口取跳变后的首个有效时刻，重复时间取首次 offset。
     */
    private fun resolveBoundary(
        date: LocalDate,
        minuteOfDay: Int,
        zoneRules: ZoneRules,
        zoneSource: ZonedDateTime,
    ): ZonedDateTime {
        val localDateTime = date.atStartOfDay().plusMinutes(minuteOfDay.toLong())
        val validOffsets = zoneRules.getValidOffsets(localDateTime)
        return when {
            validOffsets.isNotEmpty() -> ZonedDateTime.ofLocal(
                localDateTime,
                zoneSource.zone,
                validOffsets.first(),
            )

            else -> {
                val transition = requireNotNull(zoneRules.getTransition(localDateTime)) {
                    "无有效 offset 的本地时间必须位于时区跳变缺口内"
                }
                ZonedDateTime.ofLocal(
                    transition.dateTimeAfter,
                    zoneSource.zone,
                    transition.offsetAfter,
                )
            }
        }
    }

    /** 表示由开始日解析出的真实时间区间。 */
    private data class RuleInterval(
        val start: ZonedDateTime,
        val end: ZonedDateTime,
    )

    private companion object {
        const val PREVIOUS_DAY_OFFSET = -1L
        const val DAYS_IN_WEEK = 7L
        const val MAX_CALENDAR_LOOK_AHEAD_DAYS = 370L
    }
}
