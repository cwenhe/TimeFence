package com.cwenhe.timefence.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cwenhe.timefence.BuildConfig
import com.cwenhe.timefence.calendar.CalendarStatus
import com.cwenhe.timefence.enforcement.SpeechLanguage
import com.cwenhe.timefence.enforcement.SpeechSettings
import com.cwenhe.timefence.permissions.PermissionStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 展示保护状态、日历同步、语音设置、荣耀后台入口和版本信息。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    status: PermissionStatus,
    calendarStatus: CalendarStatus,
    today: LocalDate,
    speechSettings: SpeechSettings,
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
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("设置") })
        LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
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
}

/** 展示全局语音总开关，不改变每条规则自己的朗读选择。 */
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

/** 使用三个紧凑选项切换系统语言、中文普通话和关闭策略。 */
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

/** 展示设置页的分组标题。 */
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

/** 展示一项系统设置及其健康状态。 */
@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    detail: String,
    healthy: Boolean?,
    onClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp)
                .padding(horizontal = 20.dp),
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
            IconButton(onClick = onClick) {
                Icon(Icons.Outlined.ChevronRight, contentDescription = "打开$title")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
    }
}

/** 判断当前设备是否需要展示荣耀或华为后台管理入口。 */
private fun isHonorDevice(): Boolean =
    Build.MANUFACTURER.equals("HONOR", ignoreCase = true) ||
        Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true)

/** 将日历覆盖和最近同步错误转换为一行可扫描状态。 */
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

/** 将最近联网成功时间转换为设备本地的紧凑日期时间。 */
private fun formatSyncTime(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis)
    .atZone(ZoneId.systemDefault())
    .format(SYNC_TIME_FORMATTER)

private val SPEECH_LANGUAGE_LABELS = listOf(
    SpeechLanguage.SYSTEM to "跟随系统",
    SpeechLanguage.ZH_CN to "中文",
    SpeechLanguage.OFF to "关闭",
)
private val SYNC_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
