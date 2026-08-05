package com.cwenhe.timefence.ui.setup

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cwenhe.timefence.permissions.PermissionStatus

/** 首次进入时逐项展示保护所需的系统授权。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    status: PermissionStatus,
    onAccessibility: () -> Unit,
    onExactAlarm: () -> Unit,
    onNotifications: () -> Unit,
    onBattery: () -> Unit,
    onContinue: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("完成保护设置") })
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item {
                Text(
                    text = "必要权限",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            item {
                SetupRow(
                    icon = Icons.Outlined.AccessibilityNew,
                    title = "无障碍服务",
                    detail = if (status.accessibilityConnected) "已连接" else "未连接",
                    complete = status.accessibilityConnected,
                    onClick = onAccessibility,
                )
            }
            item {
                SetupRow(
                    icon = Icons.Outlined.Alarm,
                    title = "准时触发",
                    detail = if (status.exactAlarmAllowed) "精确闹钟已允许" else "需要允许精确闹钟",
                    complete = status.exactAlarmAllowed,
                    onClick = onExactAlarm,
                )
            }
            item {
                SetupRow(
                    icon = Icons.Outlined.BatterySaver,
                    title = "后台运行",
                    detail = if (status.batteryOptimizationIgnored) "电池策略已放宽" else "建议设为不限制",
                    complete = status.batteryOptimizationIgnored,
                    onClick = onBattery,
                )
            }
            item {
                Text(
                    text = "可选",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                SetupRow(
                    icon = Icons.Outlined.Notifications,
                    title = "通知",
                    detail = if (status.notificationsAllowed) "已允许" else "未允许，不影响拦截",
                    complete = status.notificationsAllowed,
                    onClick = onNotifications,
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !status.accessibilityEnabled) {
                item {
                    Surface(
                        modifier = Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = "若无障碍开关不可用，请在时界的应用信息右上角选择“允许受限设置”。",
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
                enabled = status.protectionReady,
            ) {
                Text("开始使用")
            }
            if (!status.protectionReady) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onContinue) {
                    Text("稍后设置")
                }
            }
        }
    }
}

/** 展示单项权限的真实状态和系统设置入口。 */
@Composable
private fun SetupRow(
    icon: ImageVector,
    title: String,
    detail: String,
    complete: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = if (complete) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = if (complete) "已完成" else "未完成",
            tint = if (complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
        IconButton(onClick = onClick) {
            Icon(Icons.Outlined.ChevronRight, contentDescription = "打开$title")
        }
    }
}
