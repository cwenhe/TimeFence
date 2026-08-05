package com.cwenhe.timefence.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cwenhe.timefence.rules.ScheduleRule
import com.cwenhe.timefence.ui.formatMinuteOfDay
import com.cwenhe.timefence.ui.formatWeekdays

/** 展示单条规则，并提供稳定尺寸的启停和编辑入口。 */
@Composable
fun RuleRow(
    rule: ScheduleRule,
    locked: Boolean,
    onClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .clickable(enabled = !locked, role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = rule.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    if (locked) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = "规则已锁定",
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${formatMinuteOfDay(rule.startMinute)} - ${formatMinuteOfDay(rule.endMinute)}  ·  ${formatWeekdays(rule.days)}  ·  ${rule.packages.size} 个应用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Switch(
                checked = rule.enabled,
                onCheckedChange = onEnabledChange,
                enabled = !locked,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
    }
}
