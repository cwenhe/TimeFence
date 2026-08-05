package com.cwenhe.timefence.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.cwenhe.timefence.rules.CalendarMode
import com.cwenhe.timefence.rules.ScheduleRule
import com.cwenhe.timefence.ui.editor.RuleEditorScreen
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
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

    /** 工作日规则不依赖星期多选，也可以保存完整模式。 */
    @Test
    fun `工作日规则无需选择星期`() {
        var saved: ScheduleRule? = null
        composeRule.setContent {
            TimeFenceTheme {
                RuleEditorScreen(
                    existingRule = validRule().copy(
                        days = emptySet(),
                        calendarMode = CalendarMode.CN_STATUTORY_WORKDAY,
                    ),
                    installedApps = emptyList(),
                    appsLoading = false,
                    locked = false,
                    onBack = {},
                    onSave = { saved = it },
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithText("保存规则").assertIsEnabled().performClick()

        assertEquals(CalendarMode.CN_STATUTORY_WORKDAY, saved?.calendarMode)
    }

    /** 自定义文本和规则级语音开关在未编辑时能够无损保存。 */
    @Test
    fun `提示配置可以保存`() {
        var saved: ScheduleRule? = null
        composeRule.setContent {
            TimeFenceTheme {
                RuleEditorScreen(
                    existingRule = validRule().copy(
                        notificationMessage = "{rule} 已限制 {app}",
                        speakNotification = true,
                    ),
                    installedApps = emptyList(),
                    appsLoading = false,
                    locked = false,
                    onBack = {},
                    onSave = { saved = it },
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithText("保存规则").performClick()

        assertEquals("{rule} 已限制 {app}", saved?.notificationMessage)
        assertTrue(saved?.speakNotification == true)
    }

    /** 验证用户可切换交易日、输入提示、查看预览并开启规则级语音。 */
    @Test
    fun `交易日提示与语音可以交互保存`() {
        var saved: ScheduleRule? = null
        composeRule.setContent {
            TimeFenceTheme {
                RuleEditorScreen(
                    existingRule = validRule(),
                    installedApps = emptyList(),
                    appsLoading = false,
                    locked = false,
                    onBack = {},
                    onSave = { saved = it },
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithText("交易日").performClick()
        val notificationInput = composeRule.onNodeWithTag("notification-message-input")
        notificationInput.performTextClearance()
        notificationInput.performTextInput("{app} 暂停至 {until}")
        composeRule.onNodeWithText("预览：demo.app 暂停至 10:00").assertExists()
        composeRule.onNodeWithTag("rule-speech-toggle").performClick()
        composeRule.onNodeWithText("保存规则").performClick()

        assertEquals(CalendarMode.CN_A_SHARE_TRADING_DAY, saved?.calendarMode)
        assertEquals("{app} 暂停至 {until}", saved?.notificationMessage)
        assertTrue(saved?.speakNotification == true)
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
