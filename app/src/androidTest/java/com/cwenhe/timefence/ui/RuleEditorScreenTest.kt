package com.cwenhe.timefence.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cwenhe.timefence.rules.ScheduleRule
import com.cwenhe.timefence.ui.editor.RuleEditorScreen
import java.time.DayOfWeek
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 验证规则编辑页最重要的保存门禁。 */
class RuleEditorScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** 有效规则能够触发保存回调。 */
    @Test
    fun `有效规则可以保存`() {
        var saved = false
        composeRule.setContent {
            TimeFenceTheme {
                RuleEditorScreen(
                    existingRule = validRule(),
                    installedApps = emptyList(),
                    appsLoading = false,
                    locked = false,
                    onBack = {},
                    onSave = { saved = true },
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithText("保存规则").assertIsEnabled().performClick()

        assertTrue(saved)
    }

    /** 起止时间相同时必须禁用保存按钮。 */
    @Test
    fun `相同起止时间不能保存`() {
        composeRule.setContent {
            TimeFenceTheme {
                RuleEditorScreen(
                    existingRule = validRule().copy(endMinute = 8 * 60),
                    installedApps = emptyList(),
                    appsLoading = false,
                    locked = false,
                    onBack = {},
                    onSave = {},
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithText("保存规则").assertIsNotEnabled()
    }

    /** 构造表单字段完整的固定测试规则。 */
    private fun validRule(): ScheduleRule = ScheduleRule(
        id = 1,
        name = "工作时间",
        startMinute = 8 * 60,
        endMinute = 10 * 60,
        days = setOf(DayOfWeek.MONDAY),
        packages = setOf("demo.app"),
        enabled = true,
        lockWhileActive = false,
    )
}
