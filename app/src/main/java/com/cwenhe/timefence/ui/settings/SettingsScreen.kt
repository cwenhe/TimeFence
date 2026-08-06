package com.cwenhe.timefence.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cwenhe.timefence.BuildConfig
import com.cwenhe.timefence.calendar.CalendarStatus
import com.cwenhe.timefence.enforcement.SpeechLanguage
import com.cwenhe.timefence.enforcement.SpeechSettings
import com.cwenhe.timefence.permissions.PermissionStatus
import com.cwenhe.timefence.suspension.ShizukuBackend
import com.cwenhe.timefence.suspension.ShizukuConnectionPhase
import com.cwenhe.timefence.suspension.SystemSuspendStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 展示普通保护权限、可选系统暂停、日历、语音和应用设置。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    status: PermissionStatus,
    calendarStatus: CalendarStatus,
    today: LocalDate,
    speechSettings: SpeechSettings,
    systemSuspend: SystemSuspendStatus,
    onAccessibility: () -> Unit,
    onExactAlarm: () -> Unit,
    onNotifications: () -> Unit,
    onBattery: () -> Unit,
    onHonorBackground: () -> Unit,
    onAppDetails: () -> Unit,
    onSyncCalendar: () -> Unit,
    onSpeechEnabled: (Boolean) -> Unit,
    onSpeechLanguage: (SpeechLanguage) -> Unit,
    onTextToSpeechSettings: () -> Unit,
    onSystemSuspendEnabled: (Boolean) -> Unit,
    onShizukuAction: () -> Unit,
    onReleaseAllSuspensions: () -> Unit,
) {
    var showEnableConfirmation by rememberSaveable { mutableStateOf(false) }
    var showReleaseConfirmation by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("设置") })
        LazyColumn(
            contentPadding = PaddingValues(bottom = 88.dp),
            modifier = Modifier.testTag("settings-list"),
        ) {
            item { SectionTitle("保护状态") }
            item {
                SettingRow(
                    icon = Icons.Outlined.AccessibilityNew,
                    title = "无障碍服务",
                    detail = if (status.accessibilityConnected) "服务已连接" else "服务未连接",
                    healthy = status.accessibilityConnected,
                    onClick = onAccessibility,
                )
            }
            item {
                SettingRow(
                    icon = Icons.Outlined.Alarm,
                    title = "精确闹钟",
                    detail = if (status.exactAlarmAllowed) "可准时检查前台应用" else "未授权，触发可能延迟",
                    healthy = status.exactAlarmAllowed,
                    onClick = onExactAlarm,
                )
            }
            item {
                SettingRow(
                    icon = Icons.Outlined.BatterySaver,
                    title = "电池优化",
                    detail = if (status.batteryOptimizationIgnored) "不限制后台运行" else "系统可能限制后台运行",
                    healthy = status.batteryOptimizationIgnored,
                    onClick = onBattery,
                )
            }
            item {
                SettingRow(
                    icon = Icons.Outlined.Notifications,
                    title = "通知",
                    detail = if (status.notificationsAllowed) "已允许" else "未允许，可选",
                    healthy = status.notificationsAllowed,
                    onClick = onNotifications,
                )
            }

            item { SectionTitle("高级拦截") }
            item {
                SystemSuspendModeRow(
                    status = systemSuspend,
                    onEnableRequested = { showEnableConfirmation = true },
                    onDisableRequested = { showReleaseConfirmation = true },
                )
            }
            item {
                SettingRow(
                    icon = Icons.Outlined.PowerSettingsNew,
                    title = "Shizuku 服务",
                    detail = shizukuStatusDetail(systemSuspend),
                    healthy = when (systemSuspend.gateway.phase) {
                        ShizukuConnectionPhase.READY -> true
                        ShizukuConnectionPhase.CONNECTING -> null
                        else -> false
                    },
                    actionTag = "shizuku-action",
                    onClick = onShizukuAction,
                )
            }
            item {
                SuspendRecoveryRow(
                    status = systemSuspend,
                    onReleaseRequested = { showReleaseConfirmation = true },
                )
            }
            item {
                Text(
                    text = "系统暂停可能跨重启保留。非 Root 手机重启后，请先启动 Shizuku 以便时界恢复应用。",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (systemSuspend.lastError != null) {
                item {
                    Text(
                        text = systemSuspend.lastError,
                        modifier = Modifier
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                            .testTag("system-suspend-error"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (isHonorDevice()) {
                item { SectionTitle("荣耀 MagicOS") }
                item {
                    SettingRow(
                        icon = Icons.Outlined.PhoneAndroid,
                        title = "自启动与后台运行",
                        detail = "检查系统管家中的启动管理",
                        healthy = null,
                        onClick = onHonorBackground,
                    )
                }
            }
            item { SectionTitle("日历") }
            item {
                SettingRow(
                    icon = if (calendarStatus.isSyncing) Icons.Outlined.Sync else Icons.Outlined.CalendarMonth,
                    title = "工作日与交易日",
                    detail = calendarStatusDetail(calendarStatus, today),
                    healthy = calendarStatus.covers(today) && calendarStatus.lastError == null,
                    onClick = onSyncCalendar,
                )
            }
            item { SectionTitle("语音提示") }
            item {
                SpeechToggleRow(
                    settings = speechSettings,
                    onEnabledChange = onSpeechEnabled,
                )
            }
            item {
                SpeechLanguageRow(
                    selected = speechSettings.language,
                    onSelected = onSpeechLanguage,
                )
            }
            item {
                SettingRow(
                    icon = Icons.Outlined.RecordVoiceOver,
                    title = "系统语音引擎",
                    detail = "管理系统 TTS 音色与数据",
                    healthy = null,
                    onClick = onTextToSpeechSettings,
                )
            }
            item { SectionTitle("应用") }
            item {
                SettingRow(
                    icon = Icons.Outlined.Info,
                    title = "时界 ${BuildConfig.VERSION_NAME}",
                    detail = "查看应用信息与受限设置",
                    healthy = null,
                    onClick = onAppDetails,
                )
            }
        }
    }

    if (showEnableConfirmation) {
        AlertDialog(
            onDismissRequest = { showEnableConfirmation = false },
            title = { Text("启用系统暂停模式？") },
            text = { Text("规则生效时目标应用会被系统暂停。非 Root 手机重启后，需要重新启动 Shizuku 才能继续校正和恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEnableConfirmation = false
                        onSystemSuspendEnabled(true)
                    },
                ) {
                    Text("启用")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEnableConfirmation = false }) {
                    Text("取消")
                }
            },
        )
    }
    if (showReleaseConfirmation) {
        AlertDialog(
            onDismissRequest = { showReleaseConfirmation = false },
            title = { Text("解除全部系统暂停？") },
            text = { Text("时界将停止新增系统暂停，并恢复当前记录的 ${systemSuspend.managedCount} 个应用。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showReleaseConfirmation = false
                        if (systemSuspend.modeEnabled) {
                            onSystemSuspendEnabled(false)
                        } else {
                            onReleaseAllSuspensions()
                        }
                    },
                ) {
                    Text("解除并关闭")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReleaseConfirmation = false }) {
                    Text("取消")
                }
            },
        )
    }
}

/** 展示高级模式开关，并在风险操作前交由父级弹出确认。 */
@Composable
private fun SystemSuspendModeRow(
    status: SystemSuspendStatus,
    onEnableRequested: () -> Unit,
    onDisableRequested: () -> Unit,
) {
    val switchEnabled = !status.busy && !status.releasePending &&
        (status.modeEnabled || status.gateway.isReady)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 84.dp)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Block, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp),
        ) {
            Text("系统暂停模式", style = MaterialTheme.typography.titleMedium)
            Text(
                text = systemSuspendModeDetail(status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = status.modeEnabled,
            onCheckedChange = { enabled ->
                if (enabled) onEnableRequested() else onDisableRequested()
            },
            enabled = switchEnabled,
            modifier = Modifier.testTag("system-suspend-switch"),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
}

/** 展示本地恢复责任数量和紧急解除入口。 */
@Composable
private fun SuspendRecoveryRow(
    status: SystemSuspendStatus,
    onReleaseRequested: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Restore, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp),
        ) {
            Text("恢复责任", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (status.releasePending) {
                    "正在解除 ${status.managedCount} 个应用"
                } else {
                    "时界当前管理 ${status.managedCount} 个应用"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = onReleaseRequested,
            enabled = !status.busy && status.managedCount > 0,
            modifier = Modifier.testTag("release-all-button"),
        ) {
            Icon(Icons.Outlined.Restore, contentDescription = null)
            Text("解除全部", modifier = Modifier.padding(start = 6.dp))
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
}

/** 展示全局语音开关及厂商引擎隐私提示。 */
@Composable
private fun SpeechToggleRow(
    settings: SpeechSettings,
    onEnabledChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp),
        ) {
            Text("允许语音播报", style = MaterialTheme.typography.titleMedium)
            Text(
                "系统 TTS 可能由厂商引擎联网处理",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = settings.enabled, onCheckedChange = onEnabledChange)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
}

/** 使用互斥选项设置系统 TTS 的语言策略。 */
@Composable
private fun SpeechLanguageRow(
    selected: SpeechLanguage,
    onSelected: (SpeechLanguage) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text("播报语言", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SPEECH_LANGUAGE_LABELS.forEach { (language, label) ->
                FilterChip(
                    selected = selected == language,
                    onClick = { onSelected(language) },
                    label = { Text(label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
}

/** 渲染设置分组标题。 */
@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
}

/** 渲染可打开系统页面或执行单一状态动作的设置行。 */
@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    detail: String,
    healthy: Boolean?,
    actionTag: String? = null,
    onClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (healthy != null) {
                Icon(
                    imageVector = if (healthy) Icons.Outlined.CheckCircle else Icons.Outlined.ReportProblem,
                    contentDescription = if (healthy) "正常" else "需要处理",
                    tint = if (healthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                )
            }
            IconButton(
                onClick = onClick,
                modifier = if (actionTag == null) Modifier else Modifier.testTag(actionTag),
            ) {
                Icon(Icons.Outlined.ChevronRight, contentDescription = "打开$title")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
    }
}

/** 根据厂商决定是否展示荣耀后台管理入口。 */
private fun isHonorDevice(): Boolean =
    Build.MANUFACTURER.equals("HONOR", ignoreCase = true) ||
        Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true)

/** 将高级模式、恢复流程和 Shizuku 可用性压缩为一行说明。 */
private fun systemSuspendModeDetail(status: SystemSuspendStatus): String = when {
    status.releasePending && status.gateway.isReady -> "正在解除全部暂停，不会新增暂停"
    status.releasePending -> "等待 Shizuku 恢复后继续解除"
    status.modeEnabled && status.gateway.isReady -> "系统暂停与无障碍双重保护"
    status.modeEnabled -> "已开启；Shizuku 不可用时只保留无障碍拦截"
    status.gateway.isReady -> "规则生效时直接暂停目标应用"
    else -> "配置 Shizuku 后可启用"
}

/** 将 Shizuku 生命周期状态转换为设置行说明。 */
private fun shizukuStatusDetail(status: SystemSuspendStatus): String = when (status.gateway.phase) {
    ShizukuConnectionPhase.NOT_RUNNING -> status.gateway.message ?: "服务未运行，点击打开 Shizuku"
    ShizukuConnectionPhase.UNSUPPORTED -> status.gateway.message ?: "版本过旧，点击更新"
    ShizukuConnectionPhase.PERMISSION_REQUIRED -> status.gateway.message ?: "未授权，点击授权"
    ShizukuConnectionPhase.CONNECTING -> "正在连接 UserService"
    ShizukuConnectionPhase.ERROR -> status.gateway.message ?: "连接异常，点击重试"
    ShizukuConnectionPhase.READY -> when (status.gateway.backend) {
        ShizukuBackend.ROOT -> "已授权，Root 后端"
        ShizukuBackend.ADB -> "已授权，ADB 后端"
        ShizukuBackend.NONE -> "已授权，UserService 已连接"
    }
}

/** 将日历覆盖与同步状态转换为设置行说明。 */
private fun calendarStatusDetail(status: CalendarStatus, today: LocalDate): String = when {
    status.isSyncing -> "正在联网更新"
    !status.covers(today) && status.lastError != null -> "未覆盖 $today，更新失败，相关规则暂不执行"
    !status.covers(today) -> "未覆盖 $today，相关规则暂不执行，点击更新"
    status.lastError != null -> "更新失败，继续使用本地缓存"
    status.coveredFrom != null && status.coveredTo != null -> {
        val synced = status.lastSuccessfulSyncAt?.let(::formatSyncTime)
        if (synced == null) {
            "覆盖 ${status.coveredFrom} 至 ${status.coveredTo}，点击更新"
        } else {
            "覆盖 ${status.coveredFrom} 至 ${status.coveredTo}，更新于 $synced"
        }
    }

    else -> "尚未加载日历，点击更新"
}

/** 使用设备时区格式化日历最近同步时间。 */
private fun formatSyncTime(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis)
    .atZone(ZoneId.systemDefault())
    .format(SYNC_TIME_FORMATTER)

private val SPEECH_LANGUAGE_LABELS = listOf(
    SpeechLanguage.SYSTEM to "跟随系统",
    SpeechLanguage.ZH_CN to "中文",
    SpeechLanguage.OFF to "关闭",
)
private val SYNC_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
