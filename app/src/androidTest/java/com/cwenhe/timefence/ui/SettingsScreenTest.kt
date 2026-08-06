package com.cwenhe.timefence.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.cwenhe.timefence.calendar.CalendarStatus
import com.cwenhe.timefence.enforcement.SpeechLanguage
import com.cwenhe.timefence.enforcement.SpeechSettings
import com.cwenhe.timefence.permissions.PermissionStatus
import com.cwenhe.timefence.suspension.ShizukuBackend
import com.cwenhe.timefence.suspension.ShizukuConnectionPhase
import com.cwenhe.timefence.suspension.ShizukuGatewayStatus
import com.cwenhe.timefence.suspension.SystemSuspendStatus
import com.cwenhe.timefence.ui.settings.SettingsScreen
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 验证高级拦截设置区的关键状态和确认交互。 */
class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** 验证就绪状态启用高级模式前必须经过风险确认。 */
    @Test
    fun `系统暂停开关确认后才启用`() {
        var enabled: Boolean? = null
        showSettings(
            systemSuspend = suspendStatus(
                phase = ShizukuConnectionPhase.READY,
                backend = ShizukuBackend.ADB,
            ),
            onSystemSuspendEnabled = { enabled = it },
        )
        scrollTo("system-suspend-switch")

        composeRule.onNodeWithTag("system-suspend-switch").performClick()
        composeRule.onNodeWithText("启用系统暂停模式？").assertIsDisplayed()
        composeRule.onNodeWithText("启用").performClick()

        assertEquals(true, enabled)
    }

    /** 验证恢复入口展示管理数量并在确认后请求关闭。 */
    @Test
    fun `解除全部展示数量并请求关闭模式`() {
        var enabled: Boolean? = true
        showSettings(
            systemSuspend = suspendStatus(
                phase = ShizukuConnectionPhase.READY,
                backend = ShizukuBackend.ROOT,
                modeEnabled = true,
                managedPackages = setOf("com.example.one", "com.example.two"),
            ),
            onSystemSuspendEnabled = { enabled = it },
        )
        scrollTo("release-all-button")

        composeRule.onNodeWithTag("release-all-button").performClick()
        composeRule.onNodeWithText("时界将停止新增系统暂停，并恢复当前记录的 2 个应用。").assertIsDisplayed()
        composeRule.onNodeWithText("解除并关闭").performClick()

        assertEquals(false, enabled)
    }

    /** 验证未授权状态提供明确授权动作入口。 */
    @Test
    fun `未授权Shizuku状态可以触发授权动作`() {
        var actionCalled = false
        showSettings(
            systemSuspend = suspendStatus(
                phase = ShizukuConnectionPhase.PERMISSION_REQUIRED,
                message = "需要授予时界 Shizuku 权限",
            ),
            onShizukuAction = { actionCalled = true },
        )
        scrollTo("shizuku-action")

        composeRule.onNodeWithText("需要授予时界 Shizuku 权限").assertIsDisplayed()
        composeRule.onNodeWithTag("shizuku-action").performClick()

        assertTrue(actionCalled)
    }

    /** 创建带默认权限、日历和语音状态的设置页。 */
    private fun showSettings(
        systemSuspend: SystemSuspendStatus,
        onSystemSuspendEnabled: (Boolean) -> Unit = {},
        onShizukuAction: () -> Unit = {},
    ) {
        composeRule.setContent {
            TimeFenceTheme {
                SettingsScreen(
                    status = PermissionStatus(
                        accessibilityEnabled = true,
                        accessibilityConnected = true,
                        exactAlarmAllowed = true,
                        notificationsAllowed = true,
                        batteryOptimizationIgnored = true,
                    ),
                    calendarStatus = CalendarStatus.initial(),
                    today = LocalDate.of(2026, 8, 6),
                    speechSettings = SpeechSettings(false, SpeechLanguage.SYSTEM),
                    systemSuspend = systemSuspend,
                    onAccessibility = {},
                    onExactAlarm = {},
                    onNotifications = {},
                    onBattery = {},
                    onHonorBackground = {},
                    onAppDetails = {},
                    onSyncCalendar = {},
                    onSpeechEnabled = {},
                    onSpeechLanguage = {},
                    onTextToSpeechSettings = {},
                    onSystemSuspendEnabled = onSystemSuspendEnabled,
                    onShizukuAction = onShizukuAction,
                    onReleaseAllSuspensions = {},
                )
            }
        }
    }

    /** 滚动设置列表直到目标测试控件进入组合树。 */
    private fun scrollTo(tag: String) {
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag(tag))
    }

    /** 创建测试需要的系统暂停状态。 */
    private fun suspendStatus(
        phase: ShizukuConnectionPhase,
        backend: ShizukuBackend = ShizukuBackend.NONE,
        message: String? = null,
        modeEnabled: Boolean = false,
        managedPackages: Set<String> = emptySet(),
    ): SystemSuspendStatus = SystemSuspendStatus(
        modeEnabled = modeEnabled,
        releasePending = false,
        managedPackages = managedPackages,
        gateway = ShizukuGatewayStatus(phase, backend, message),
        busy = false,
        lastError = null,
    )
}
