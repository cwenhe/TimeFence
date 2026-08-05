package com.cwenhe.timefence.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cwenhe.timefence.apps.InstalledApp
import com.cwenhe.timefence.calendar.CalendarStatus
import com.cwenhe.timefence.rules.CalendarMode
import com.cwenhe.timefence.rules.ScheduleRule
import com.cwenhe.timefence.ui.formatMinuteOfDay
import com.cwenhe.timefence.ui.picker.AppPickerScreen
import java.time.DayOfWeek

/** 编辑规则的时间、日期模式、应用、自定义提示、语音和锁定选项。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditorScreen(
    existingRule: ScheduleRule?,
    installedApps: List<InstalledApp>,
    appsLoading: Boolean,
    locked: Boolean,
    calendarStatus: CalendarStatus = CalendarStatus.initial(),
    onBack: () -> Unit,
    onSave: (ScheduleRule) -> Unit,
    onDelete: (ScheduleRule) -> Unit,
) {
    var name by rememberSaveable(existingRule?.id) { mutableStateOf(existingRule?.name.orEmpty()) }
    var startMinute by rememberSaveable(existingRule?.id) {
        mutableIntStateOf(existingRule?.startMinute ?: DEFAULT_START_MINUTE)
    }
    var endMinute by rememberSaveable(existingRule?.id) {
        mutableIntStateOf(existingRule?.endMinute ?: DEFAULT_END_MINUTE)
    }
    var daysMask by rememberSaveable(existingRule?.id) {
        mutableIntStateOf(daysToMask(existingRule?.days ?: DEFAULT_DAYS))
    }
    var selectedPackages by rememberSaveable(existingRule?.id) {
        mutableStateOf(existingRule?.packages?.sorted().orEmpty())
    }
    var lockWhileActive by rememberSaveable(existingRule?.id) {
        mutableStateOf(existingRule?.lockWhileActive ?: false)
    }
    var calendarModeName by rememberSaveable(existingRule?.id) {
        mutableStateOf(existingRule?.calendarMode?.name ?: CalendarMode.WEEKLY.name)
    }
    var notificationMessage by rememberSaveable(existingRule?.id) {
        mutableStateOf(existingRule?.notificationMessage.orEmpty())
    }
    var speakNotification by rememberSaveable(existingRule?.id) {
        mutableStateOf(existingRule?.speakNotification ?: false)
    }
    var showAppPicker by rememberSaveable { mutableStateOf(false) }
    var showStartPicker by rememberSaveable { mutableStateOf(false) }
    var showEndPicker by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }

    if (showAppPicker) {
        AppPickerScreen(
            apps = installedApps,
            selectedPackages = selectedPackages.toSet(),
            loading = appsLoading,
            onToggle = { packageName ->
                selectedPackages = if (packageName in selectedPackages) {
                    selectedPackages - packageName
                } else {
                    (selectedPackages + packageName).sorted()
                }
            },
            onBack = { showAppPicker = false },
            onDone = { showAppPicker = false },
        )
        return
    }

    val days = maskToDays(daysMask)
    val calendarMode = runCatching { CalendarMode.valueOf(calendarModeName) }
        .getOrDefault(CalendarMode.WEEKLY)
    val valid = name.isNotBlank() && startMinute != endMinute &&
        (calendarMode != CalendarMode.WEEKLY || days.isNotEmpty()) && selectedPackages.isNotEmpty()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existingRule == null) "新建规则" else "编辑规则") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (existingRule != null) {
                        IconButton(
                            onClick = { showDeleteConfirmation = true },
                            enabled = !locked,
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = "删除规则")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (locked) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Lock, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("规则生效期间已锁定")
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("notification-message-input"),
                label = { Text("规则名称") },
                placeholder = { Text("例如：夜间休息") },
                singleLine = true,
                enabled = !locked,
            )
            SectionLabel("时间")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TimeButton(
                    label = "开始",
                    minute = startMinute,
                    onClick = { showStartPicker = true },
                    enabled = !locked,
                    modifier = Modifier.weight(1f),
                )
                TimeButton(
                    label = "结束",
                    minute = endMinute,
                    onClick = { showEndPicker = true },
                    enabled = !locked,
                    modifier = Modifier.weight(1f),
                )
            }
            if (startMinute == endMinute) {
                Text(
                    text = "开始时间不能等于结束时间",
                    modifier = Modifier.padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            SectionLabel("重复")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CALENDAR_MODE_LABELS.forEach { (mode, label) ->
                    ModeToggle(
                        selected = calendarMode == mode,
                        onClick = { calendarModeName = mode.name },
                        label = label,
                        enabled = !locked,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (calendarMode == CalendarMode.WEEKLY) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    DayOfWeek.entries.forEach { day ->
                        val selected = day in days
                        DayToggle(
                            selected = selected,
                            onClick = { daysMask = toggleDay(daysMask, day) },
                            label = { Text(DAY_LABELS.getValue(day)) },
                            enabled = !locked,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else {
                Text(
                    text = calendarModeDescription(calendarMode, calendarStatus),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (calendarStatus.coveredTo == null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            SectionLabel("应用")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clickable(enabled = !locked, role = Role.Button) { showAppPicker = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp),
                ) {
                    Text("受限应用", style = MaterialTheme.typography.titleMedium)
                    Text(
                        selectedAppSummary(selectedPackages, installedApps),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Icon(Icons.Outlined.ChevronRight, contentDescription = "选择应用")
            }
            SectionLabel("拦截提示")
            OutlinedTextField(
                value = notificationMessage,
                onValueChange = { value ->
                    if (value.codePointCount(0, value.length) <= MAX_NOTIFICATION_CODE_POINTS) {
                        notificationMessage = value
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("提示文本") },
                placeholder = { Text(DEFAULT_NOTIFICATION_MESSAGE) },
                supportingText = {
                    Text("${notificationMessage.codePointCount(0, notificationMessage.length)}/$MAX_NOTIFICATION_CODE_POINTS")
                },
                minLines = 2,
                maxLines = 4,
                enabled = !locked,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PLACEHOLDERS.forEach { placeholder ->
                    TextButton(
                        onClick = { notificationMessage = appendPlaceholder(notificationMessage, placeholder) },
                        enabled = !locked && notificationMessage.length + placeholder.length <= MAX_NOTIFICATION_CODE_POINTS,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(placeholder)
                    }
                }
            }
            Text(
                text = "预览：" + notificationPreview(
                    template = notificationMessage,
                    ruleName = name.ifBlank { "规则名称" },
                    appName = selectedPreviewAppName(selectedPackages, installedApps),
                    endMinute = endMinute,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp),
                ) {
                    Text("语音播报", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "返回桌面后朗读本条提示",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = speakNotification,
                    onCheckedChange = { speakNotification = it },
                    enabled = !locked,
                    modifier = Modifier.testTag("rule-speech-toggle"),
                )
            }
            SectionLabel("保护")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp),
                ) {
                    Text("生效期间锁定规则", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "生效时不能在时界内停用或删除",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = lockWhileActive,
                    onCheckedChange = { lockWhileActive = it },
                    enabled = !locked,
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    onSave(
                        ScheduleRule(
                            id = existingRule?.id ?: 0,
                            name = name.trim(),
                            startMinute = startMinute,
                            endMinute = endMinute,
                            days = days,
                            packages = selectedPackages.toSet(),
                            enabled = existingRule?.enabled ?: true,
                            lockWhileActive = lockWhileActive,
                            calendarMode = calendarMode,
                            notificationMessage = notificationMessage.trim(),
                            speakNotification = speakNotification,
                        ),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = valid && !locked,
            ) {
                Text("保存规则")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showStartPicker) {
        TimePickerDialog(
            title = "开始时间",
            minute = startMinute,
            onDismiss = { showStartPicker = false },
            onConfirm = { selected ->
                startMinute = selected
                showStartPicker = false
            },
        )
    }
    if (showEndPicker) {
        TimePickerDialog(
            title = "结束时间",
            minute = endMinute,
            onDismiss = { showEndPicker = false },
            onConfirm = { selected ->
                endMinute = selected
                showEndPicker = false
            },
        )
    }
    if (showDeleteConfirmation && existingRule != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("删除规则？") },
            text = { Text("“${existingRule.name}”将不再生效。") },
            confirmButton = {
                TextButton(onClick = { onDelete(existingRule) }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("取消")
                }
            },
        )
    }
}

/** 展示一个等分宽度的星期多选格，避免窄屏发生横向溢出。 */
@Composable
private fun DayToggle(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .height(40.dp)
            .clickable(enabled = enabled, role = Role.Checkbox, onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            label()
        }
    }
}

/** 展示三等分的重复方式单选项，保持窄屏布局稳定。 */
@Composable
private fun ModeToggle(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .height(44.dp)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 展示规则编辑表单的小节标题。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 22.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
}

/** 展示开始或结束时间的固定尺寸按钮。 */
@Composable
private fun TimeButton(
    label: String,
    minute: Int,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .height(72.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Schedule, contentDescription = null)
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall)
                Text(
                    formatMinuteOfDay(minute),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** 使用 Material 时间选择器返回一天内分钟数。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    title: String,
    minute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = minute / MINUTES_PER_HOUR,
        initialMinute = minute % MINUTES_PER_HOUR,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * MINUTES_PER_HOUR + state.minute) }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

/** 将星期集合转换为可保存的位集合。 */
private fun daysToMask(days: Set<DayOfWeek>): Int =
    days.fold(0) { mask, day -> mask or (1 shl (day.value - 1)) }

/** 将星期位集合恢复为领域枚举集合。 */
private fun maskToDays(mask: Int): Set<DayOfWeek> =
    DayOfWeek.entries.filterTo(linkedSetOf()) { day -> mask and (1 shl (day.value - 1)) != 0 }

/** 切换一周中某一天的选择状态。 */
private fun toggleDay(mask: Int, day: DayOfWeek): Int = mask xor (1 shl (day.value - 1))

/** 将已选择包名转换成紧凑的应用名称摘要。 */
private fun selectedAppSummary(
    packages: List<String>,
    apps: List<InstalledApp>,
): String {
    if (packages.isEmpty()) return "尚未选择"
    val labels = packages.map { packageName ->
        apps.firstOrNull { it.packageName == packageName }?.label ?: packageName
    }
    return if (labels.size <= MAX_VISIBLE_APP_NAMES) {
        labels.joinToString("、")
    } else {
        labels.take(MAX_VISIBLE_APP_NAMES).joinToString("、") + " 等 ${labels.size} 个"
    }
}

/** 根据模式和同步状态生成规则编辑页的日历覆盖说明。 */
private fun calendarModeDescription(mode: CalendarMode, status: CalendarStatus): String {
    val prefix = when (mode) {
        CalendarMode.CN_STATUTORY_WORKDAY -> "按中国法定工作日（含调休）生效"
        CalendarMode.CN_A_SHARE_TRADING_DAY -> "按沪深交易所共同开市日生效"
        CalendarMode.WEEKLY -> return ""
    }
    val coverage = if (status.coveredFrom != null && status.coveredTo != null) {
        "日历覆盖 ${status.coveredFrom} 至 ${status.coveredTo}"
    } else {
        "日历尚未加载，未知日期不会生效"
    }
    return "$prefix；$coverage"
}

/** 在提示文本末尾追加占位符，并在空文本时避免多余空格。 */
private fun appendPlaceholder(value: String, placeholder: String): String =
    if (value.isBlank()) placeholder else "$value $placeholder"

/** 使用当前表单值替换提示占位符，给用户展示保存前预览。 */
private fun notificationPreview(
    template: String,
    ruleName: String,
    appName: String,
    endMinute: Int,
): String = template.trim()
    .ifBlank { DEFAULT_NOTIFICATION_MESSAGE }
    .replace("{rule}", ruleName)
    .replace("{app}", appName)
    .replace("{until}", formatMinuteOfDay(endMinute))

/** 选择首个受限应用名称作为预览，无法解析时回退包名或通用文本。 */
private fun selectedPreviewAppName(
    packages: List<String>,
    apps: List<InstalledApp>,
): String {
    val packageName = packages.firstOrNull() ?: return "受限应用"
    return apps.firstOrNull { app -> app.packageName == packageName }?.label ?: packageName
}

private const val MINUTES_PER_HOUR = 60
private const val DEFAULT_START_MINUTE = 22 * MINUTES_PER_HOUR
private const val DEFAULT_END_MINUTE = 7 * MINUTES_PER_HOUR
private const val MAX_VISIBLE_APP_NAMES = 2
private const val MAX_NOTIFICATION_CODE_POINTS = 120
private const val DEFAULT_NOTIFICATION_MESSAGE = "已限制{app}，{until}前不可使用"
private val DEFAULT_DAYS = DayOfWeek.entries.toSet()
private val PLACEHOLDERS = listOf("{rule}", "{app}", "{until}")
private val CALENDAR_MODE_LABELS = listOf(
    CalendarMode.WEEKLY to "每周",
    CalendarMode.CN_STATUTORY_WORKDAY to "工作日",
    CalendarMode.CN_A_SHARE_TRADING_DAY to "交易日",
)
private val DAY_LABELS = mapOf(
    DayOfWeek.MONDAY to "一",
    DayOfWeek.TUESDAY to "二",
    DayOfWeek.WEDNESDAY to "三",
    DayOfWeek.THURSDAY to "四",
    DayOfWeek.FRIDAY to "五",
    DayOfWeek.SATURDAY to "六",
    DayOfWeek.SUNDAY to "日",
)
