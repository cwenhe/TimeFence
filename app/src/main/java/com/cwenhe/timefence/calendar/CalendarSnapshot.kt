package com.cwenhe.timefence.calendar

import com.cwenhe.timefence.data.local.CalendarDayEntity
import com.cwenhe.timefence.rules.CalendarMode
import java.time.LocalDate

/** 表示日历规则在某个日期上的确定性结果。 */
enum class CalendarMatch {
    MATCH,
    NO_MATCH,
    UNKNOWN,
}

/** 在内存中提供无 IO 的工作日和交易日查询。 */
class CalendarSnapshot private constructor(
    private val days: Map<LocalDate, CalendarDay>,
) {
    /** 查询指定模式在日期上的结果，周模式不经过此快照。 */
    fun match(mode: CalendarMode, date: LocalDate): CalendarMatch = when (mode) {
        CalendarMode.WEEKLY -> CalendarMatch.MATCH
        CalendarMode.CN_STATUTORY_WORKDAY -> days[date]?.let { day ->
            if (day.isStatutoryWorkday) CalendarMatch.MATCH else CalendarMatch.NO_MATCH
        } ?: CalendarMatch.UNKNOWN

        CalendarMode.CN_A_SHARE_TRADING_DAY -> days[date]?.let { day ->
            if (day.isAShareTradingDay) CalendarMatch.MATCH else CalendarMatch.NO_MATCH
        } ?: CalendarMatch.UNKNOWN
    }

    /** 返回当前快照覆盖的最早日期，供设置页展示。 */
    fun coveredFrom(): LocalDate? = days.keys.minOrNull()

    /** 返回当前快照覆盖的最晚日期，供设置页展示。 */
    fun coveredTo(): LocalDate? = days.keys.maxOrNull()

    companion object {
        /** 将 Room 行转换为不可变快照，供无障碍热路径无 IO 查询。 */
        fun fromEntities(entities: List<CalendarDayEntity>): CalendarSnapshot = CalendarSnapshot(
            entities.associate { entity ->
                LocalDate.parse(entity.date) to CalendarDay(
                    isStatutoryWorkday = entity.isStatutoryWorkday,
                    isAShareTradingDay = entity.isAShareTradingDay,
                )
            },
        )

        /** 创建尚未加载任何年度数据的空快照。 */
        fun empty(): CalendarSnapshot = CalendarSnapshot(emptyMap())
    }

    private data class CalendarDay(
        val isStatutoryWorkday: Boolean,
        val isAShareTradingDay: Boolean,
    )
}
