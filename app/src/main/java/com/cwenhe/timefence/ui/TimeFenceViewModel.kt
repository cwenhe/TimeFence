package com.cwenhe.timefence.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cwenhe.timefence.apps.InstalledApp
import com.cwenhe.timefence.calendar.CalendarStatus
import com.cwenhe.timefence.calendar.CalendarSyncResult
import com.cwenhe.timefence.calendar.CalendarSnapshot
import com.cwenhe.timefence.core.AppContainer
import com.cwenhe.timefence.enforcement.SpeechLanguage
import com.cwenhe.timefence.enforcement.SpeechSettings
import com.cwenhe.timefence.permissions.PermissionStatus
import com.cwenhe.timefence.rules.RuleEvaluation
import com.cwenhe.timefence.rules.ScheduleRule
import java.time.ZonedDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 汇总规则、权限、系统应用和当前时间，作为 Compose 界面的唯一状态入口。 */
class TimeFenceViewModel(private val container: AppContainer) : ViewModel() {
    private val installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val appsLoading = MutableStateFlow(true)
    private val errorMessage = MutableStateFlow<String?>(null)
    private val speechSettings = MutableStateFlow(container.speechSettingsStore.read())
    private val clock = clockFlow()
    private val appState = combine(
        installedApps,
        appsLoading,
        errorMessage,
    ) { apps, loading, error -> AppState(apps, loading, error) }
    private val auxiliaryState = combine(
        appState,
        container.calendarRepository.status,
        speechSettings,
    ) { apps, calendarStatus, speech -> AuxiliaryState(apps, calendarStatus, speech) }

    val uiState = combine(
        container.ruleSnapshot,
        container.permissionStatusRepository.status,
        clock,
        auxiliaryState,
    ) { snapshot, permissions, now, auxiliary ->
        val rules = snapshot.rules
        TimeFenceUiState(
            rules = rules,
            permissions = permissions,
            evaluation = container.scheduleEvaluator.evaluate(
                now = now,
                rules = rules,
                calendar = container.calendarRepository.snapshot.value,
            ),
            installedApps = auxiliary.apps.items,
            appsLoading = auxiliary.apps.loading,
            errorMessage = auxiliary.apps.error,
            now = now,
            calendarStatus = auxiliary.calendarStatus,
            calendarSnapshot = container.calendarRepository.snapshot.value,
            speechSettings = auxiliary.speechSettings,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = TimeFenceUiState.initial(
            container.permissionStatusRepository.status.value,
        ),
    )

    init {
        loadApps()
    }

    /** 刷新无障碍、精确闹钟、通知和电池策略状态。 */
    fun refreshPermissions() {
        container.permissionStatusRepository.refresh()
    }

    /** 打开系统无障碍设置。 */
    fun openAccessibilitySettings() {
        container.systemSettingsNavigator.openAccessibilitySettings()
    }

    /** 打开当前应用的精确闹钟授权页。 */
    fun openExactAlarmSettings() {
        container.systemSettingsNavigator.openExactAlarmSettings()
    }

    /** 打开当前应用的通知设置页。 */
    fun openNotificationSettings() {
        container.systemSettingsNavigator.openNotificationSettings()
    }

    /** 打开系统电池优化设置。 */
    fun openBatterySettings() {
        container.systemSettingsNavigator.openBatteryOptimizationSettings()
    }

    /** 优先打开荣耀启动管理，失败时回退电池设置。 */
    fun openHonorBackgroundSettings() {
        container.systemSettingsNavigator.openHonorBackgroundSettings()
    }

    /** 打开时界的系统应用信息页。 */
    fun openAppDetailsSettings() {
        container.systemSettingsNavigator.openAppDetailsSettings()
    }

    /** 打开系统语音合成设置，设备不支持时回退到声音设置。 */
    fun openTextToSpeechSettings() {
        container.systemSettingsNavigator.openTextToSpeechSettings()
    }

    /** 立即联网检查日历版本，失败时保留旧数据并向用户显示错误。 */
    fun syncCalendar() {
        viewModelScope.launch {
            if (container.calendarRepository.syncNow() == CalendarSyncResult.FAILED) {
                errorMessage.value = container.calendarRepository.status.value.lastError ?: "日历更新失败"
            }
        }
    }

    /** 更新全局语音开关，规则级开关保持不变。 */
    fun setSpeechEnabled(enabled: Boolean) {
        val updated = speechSettings.value.withEnabled(enabled)
        container.speechSettingsStore.write(updated)
        speechSettings.value = updated
    }

    /** 更新语音语言策略，选择关闭时同步关闭全局语音。 */
    fun setSpeechLanguage(language: SpeechLanguage) {
        val updated = speechSettings.value.copy(
            enabled = speechSettings.value.enabled && language != SpeechLanguage.OFF,
            language = language,
        )
        container.speechSettingsStore.write(updated)
        speechSettings.value = updated
    }

    /** 重新读取设备上可由用户选择的桌面应用。 */
    fun loadApps() {
        viewModelScope.launch {
            appsLoading.value = true
            runCatching { container.installedAppRepository.loadLaunchableApps() }
                .onSuccess { installedApps.value = it }
                .onFailure { errorMessage.value = "读取已安装应用失败" }
            appsLoading.value = false
        }
    }

    /** 保存规则；成功后回调数据库生成的稳定标识。 */
    fun saveRule(rule: ScheduleRule, onSaved: (Long) -> Unit) {
        viewModelScope.launch {
            runCatching {
                ensureRuleCanChange(rule.id)
                container.scheduleRepository.saveRule(rule)
            }.onSuccess(onSaved).onFailure { error ->
                errorMessage.value = error.message ?: "保存规则失败"
            }
        }
    }

    /** 切换规则启用状态，并阻止修改正在锁定的规则。 */
    fun setRuleEnabled(rule: ScheduleRule, enabled: Boolean) {
        viewModelScope.launch {
            runCatching {
                ensureRuleCanChange(rule.id)
                container.scheduleRepository.setEnabled(rule.id, enabled)
            }.onFailure { error ->
                errorMessage.value = error.message ?: "修改规则失败"
            }
        }
    }

    /** 删除规则，并阻止删除正在锁定的规则。 */
    fun deleteRule(rule: ScheduleRule, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching {
                ensureRuleCanChange(rule.id)
                container.scheduleRepository.deleteRule(rule.id)
            }.onSuccess { onDeleted() }.onFailure { error ->
                errorMessage.value = error.message ?: "删除规则失败"
            }
        }
    }

    /** 清除已经向用户展示的瞬时错误。 */
    fun clearError() {
        errorMessage.value = null
    }

    /** 返回给定规则是否正生效且禁止在应用内修改。 */
    fun isRuleLocked(rule: ScheduleRule): Boolean =
        rule.lockWhileActive && rule in uiState.value.evaluation.activeRules

    /** 正在生效且开启锁定的规则不允许从时界内部绕过。 */
    private suspend fun ensureRuleCanChange(ruleId: Long) {
        if (ruleId == 0L) return
        val rules = container.scheduleRepository.getRules()
        val rule = rules.firstOrNull { it.id == ruleId } ?: return
        val active = rule in container.scheduleEvaluator
            .evaluate(
                now = ZonedDateTime.now(),
                rules = rules,
                calendar = container.calendarRepository.snapshot.value,
            )
            .activeRules
        require(!rule.lockWhileActive || !active) { "规则生效期间已锁定" }
    }

    /** 每 30 秒发出当前本地时间，使跨边界状态无需重启页面即可更新。 */
    private fun clockFlow() = flow {
        while (true) {
            emit(ZonedDateTime.now())
            delay(CLOCK_INTERVAL_MILLIS)
        }
    }

    companion object {
        private const val CLOCK_INTERVAL_MILLIS = 30_000L
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** 组合应用列表加载过程和瞬时错误，减少根状态的 Flow 数量。 */
private data class AppState(
    val items: List<InstalledApp>,
    val loading: Boolean,
    val error: String?,
)

/** 组合应用列表、日历状态和语音设置，控制根状态 combine 的参数数量。 */
private data class AuxiliaryState(
    val apps: AppState,
    val calendarStatus: CalendarStatus,
    val speechSettings: SpeechSettings,
)

/** 根界面所需的不可变状态快照。 */
data class TimeFenceUiState(
    val rules: List<ScheduleRule>,
    val permissions: PermissionStatus,
    val evaluation: RuleEvaluation,
    val installedApps: List<InstalledApp>,
    val appsLoading: Boolean,
    val errorMessage: String?,
    val now: ZonedDateTime,
    val calendarStatus: CalendarStatus,
    val calendarSnapshot: CalendarSnapshot,
    val speechSettings: SpeechSettings,
) {
    companion object {
        /** 在数据库首个值到达前提供稳定的空状态。 */
        fun initial(permissions: PermissionStatus): TimeFenceUiState = TimeFenceUiState(
            rules = emptyList(),
            permissions = permissions,
            evaluation = RuleEvaluation(emptyList(), emptySet(), null),
            installedApps = emptyList(),
            appsLoading = true,
            errorMessage = null,
            now = ZonedDateTime.now(),
            calendarStatus = CalendarStatus.initial(),
            calendarSnapshot = CalendarSnapshot.empty(),
            speechSettings = SpeechSettings(enabled = false, language = SpeechLanguage.SYSTEM),
        )
    }
}
