package com.cwenhe.timefence.rules

import java.time.DayOfWeek
import java.time.ZonedDateTime

/**
 * 表示一条按设备本地星期、业务日历和分钟重复的应用限制规则。
 *
 * @property id 数据库唯一标识，尚未持久化时为 0。
 * @property name 用户可识别的规则名称。
 * @property startMinute 开始分钟，取值范围为 0..1439。
 * @property endMinute 结束分钟，取值范围为 0..1439；小于开始分钟时表示跨午夜。
 * @property days 规则开始日所属的星期集合。
 * @property packages 需要限制的应用包名集合。
 * @property enabled 是否参与生效计算。
 * @property lockWhileActive 生效期间是否禁止在时界内修改规则。
 * @property calendarMode 规则使用的每周、法定工作日或 A 股交易日模式。
 * @property notificationMessage 命中规则时显示和朗读的自定义文本。
 * @property speakNotification 是否允许本规则触发语音播报。
 */
data class ScheduleRule(
    val id: Long,
    val name: String,
    val startMinute: Int,
    val endMinute: Int,
    val days: Set<DayOfWeek>,
    val packages: Set<String>,
    val enabled: Boolean,
    val lockWhileActive: Boolean,
    val calendarMode: CalendarMode = CalendarMode.WEEKLY,
    val notificationMessage: String = "",
    val speakNotification: Boolean = false,
) {
    init {
        require(startMinute in MINUTE_OF_DAY_RANGE) { "startMinute 必须在 0..1439 范围内" }
        require(endMinute in MINUTE_OF_DAY_RANGE) { "endMinute 必须在 0..1439 范围内" }
    }

    private companion object {
        val MINUTE_OF_DAY_RANGE = 0 until 24 * 60
    }
}

/**
 * 汇总指定时刻的有效规则、受限包名及之后最近的时间边界。
 *
 * @property activeRules 当前处于左闭右开生效区间的规则。
 * @property blockedPackages 当前所有有效规则限制的包名并集。
 * @property nextBoundary 当前时刻之后最近的开始或结束边界；没有可用规则时为 null。
 */
data class RuleEvaluation(
    val activeRules: List<ScheduleRule>,
    val blockedPackages: Set<String>,
    val nextBoundary: ZonedDateTime?,
)

/** 只包含当前生效结果的轻量求值，供无障碍窗口热路径使用。 */
data class ActiveRuleEvaluation(
    val activeRules: List<ScheduleRule>,
    val blockedPackages: Set<String>,
)
