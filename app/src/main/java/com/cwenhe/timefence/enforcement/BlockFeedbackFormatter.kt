package com.cwenhe.timefence.enforcement

import com.cwenhe.timefence.rules.ScheduleRule
import java.time.ZonedDateTime
import java.util.Locale

/** 根据命中规则、应用名称和结束时间生成统一的自定义提示文本。 */
object BlockFeedbackFormatter {
    private const val DEFAULT_MESSAGE = "已限制{app}，{until}前不可使用"
    private const val MAX_CODE_POINTS = 120

    /** 选择结束时间剩余最长的命中规则并替换三个公开占位符。 */
    fun create(
        activeRules: List<ScheduleRule>,
        packageName: String,
        appLabel: String,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): BlockFeedback? {
        val rule = activeRules
            .asSequence()
            .filter { candidate -> packageName in candidate.packages }
            .maxByOrNull { candidate -> remainingMinutes(candidate, now) }
            ?: return null
        val untilText = formatMinute(rule.endMinute)
        val template = rule.notificationMessage.trim().ifBlank { DEFAULT_MESSAGE }
        val rendered = template
            .replace("{rule}", rule.name)
            .replace("{app}", appLabel.ifBlank { packageName })
            .replace("{until}", untilText)
            .let(::truncateCodePoints)
        return BlockFeedback(
            ruleId = rule.id,
            ruleName = rule.name,
            packageName = packageName,
            appLabel = appLabel.ifBlank { packageName },
            untilText = untilText,
            text = rendered,
        )
    }

    /** 计算跨午夜规则的剩余分钟，用于稳定选择多规则反馈来源。 */
    private fun remainingMinutes(rule: ScheduleRule, now: ZonedDateTime): Int {
        val currentMinute = now.hour * MINUTES_PER_HOUR + now.minute
        return if (rule.startMinute < rule.endMinute) {
            rule.endMinute - currentMinute
        } else if (currentMinute >= rule.startMinute) {
            MINUTES_PER_DAY - currentMinute + rule.endMinute
        } else {
            rule.endMinute - currentMinute
        }
    }

    /** 将结束分钟格式化为设备本地化环境下稳定的 HH:mm 文本。 */
    private fun formatMinute(minute: Int): String = String.format(
        Locale.getDefault(),
        "%02d:%02d",
        minute / MINUTES_PER_HOUR,
        minute % MINUTES_PER_HOUR,
    )

    /** 按 Unicode 码点截断文本，避免拆开代理项导致通知显示异常。 */
    private fun truncateCodePoints(value: String): String {
        if (value.codePointCount(0, value.length) <= MAX_CODE_POINTS) return value
        val end = value.offsetByCodePoints(0, MAX_CODE_POINTS)
        return value.substring(0, end)
    }

    private const val MINUTES_PER_HOUR = 60
    private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
}
