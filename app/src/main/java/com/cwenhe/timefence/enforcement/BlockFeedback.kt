package com.cwenhe.timefence.enforcement

/** 保存一次拦截要展示和朗读的完整反馈，保证多种出口使用同一份文本。 */
data class BlockFeedback(
    val ruleId: Long,
    val ruleName: String,
    val packageName: String,
    val appLabel: String,
    val untilText: String,
    val text: String,
)
