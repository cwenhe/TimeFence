package com.cwenhe.timefence.calendar

import kotlinx.serialization.Serializable

/** 描述远程或内置的完整中国日历文档。 */
@Serializable
data class CalendarDocument(
    val schemaVersion: Int,
    val revision: Long,
    val locale: String,
    val generatedAt: String,
    val years: List<CalendarYearDocument>,
)

/** 描述一个已经正式发布且逐日完整的年份数据集。 */
@Serializable
data class CalendarYearDocument(
    val year: Int,
    val complete: Boolean,
    val sources: Map<String, List<String>>,
    val days: List<CalendarDayDocument>,
)

/** 描述一个自然日是否属于法定工作日和 A 股共同交易日。 */
@Serializable
data class CalendarDayDocument(
    val date: String,
    val isStatutoryWorkday: Boolean,
    val isAShareTradingDay: Boolean,
)
