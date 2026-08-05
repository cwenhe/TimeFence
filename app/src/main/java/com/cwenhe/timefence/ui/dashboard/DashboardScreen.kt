package com.cwenhe.timefence.ui.dashboard

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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cwenhe.timefence.rules.CalendarMode
import com.cwenhe.timefence.rules.RuleEvaluation
import com.cwenhe.timefence.rules.ScheduleRule
import com.cwenhe.timefence.ui.TimeFenceUiState
import com.cwenhe.timefence.ui.components.RuleRow
import com.cwenhe.timefence.ui.formatMinuteOfDay
import com.cwenhe.timefence.ui.isRuleScheduledOn
import com.cwenhe.timefence.ui.remainingRuleMinutes
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 展示当前保护状态、下一时间边界和今天的规则。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: TimeFenceUiState,
    onAddRule: () -> Unit,
    onEditRule: (ScheduleRule) -> Unit,
    onToggleRule: (ScheduleRule, Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("时界") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddRule) {
                Icon(Icons.Outlined.Add, contentDescription = "新建规则")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item {
                ProtectionStatusBand(
                    state = state,
                    onOpenSettings = onOpenSettings,
                )
                Text(
                    text = "今日规则",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            val todayRules = state.rules.filter { rule ->
                isRuleScheduledOn(rule, state.now.toLocalDate(), state.calendarSnapshot)
            }
            if (todayRules.isEmpty()) {
                item {
                    EmptyTodayRules()
                }
            } else {
                items(todayRules.size, key = { todayRules[it].id }) { index ->
                    val rule = todayRules[index]
                    RuleRow(
                        rule = rule,
                        locked = rule.lockWhileActive && rule in state.evaluation.activeRules,
                        onClick = { onEditRule(rule) },
                        onEnabledChange = { enabled -> onToggleRule(rule, enabled) },
                    )
                }
            }
        }
    }
}

/** 根据权限和规则评估结果展示不误导用户的保护状态。 */
@Composable
private fun ProtectionStatusBand(
    state: TimeFenceUiState,
    onOpenSettings: () -> Unit,
) {
    val active = state.evaluation.activeRules
    val ready = state.permissions.protectionReady
    val calendarNeedsUpdate = state.rules.any { rule ->
        rule.enabled && rule.calendarMode != CalendarMode.WEEKLY
    } && !state.calendarStatus.covers(state.now.toLocalDate())
    val background = when {
        !ready -> MaterialTheme.colorScheme.secondaryContainer
        calendarNeedsUpdate && active.isEmpty() -> MaterialTheme.colorScheme.secondaryContainer
        active.isNotEmpty() -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val foreground = when {
        !ready -> MaterialTheme.colorScheme.onSecondaryContainer
        calendarNeedsUpdate && active.isEmpty() -> MaterialTheme.colorScheme.onSecondaryContainer
        active.isNotEmpty() -> MaterialTheme.colorScheme.onTertiary
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(color = background, contentColor = foreground) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = when {
                    !ready -> Icons.Outlined.ReportProblem
                    calendarNeedsUpdate && active.isEmpty() -> Icons.Outlined.ReportProblem
                    active.isNotEmpty() -> Icons.Outlined.Timer
                    else -> Icons.Outlined.CheckCircle
                },
                contentDescription = null,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp),
            ) {
                Text(
                    text = when {
                        !ready -> "保护未就绪"
                        calendarNeedsUpdate && active.isEmpty() -> "日历需要更新"
                        active.isNotEmpty() -> "限制生效中"
                        else -> "保护已就绪"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = statusDetail(
                        evaluation = state.evaluation,
                        now = state.now,
                        ready = ready,
                        calendarNeedsUpdate = calendarNeedsUpdate,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!ready || (calendarNeedsUpdate && active.isEmpty())) {
                Button(onClick = onOpenSettings) {
                    Text("去设置")
                }
            }
        }
    }
}

/** 生成保护状态的时间或权限摘要。 */
private fun statusDetail(
    evaluation: RuleEvaluation,
    now: java.time.ZonedDateTime,
    ready: Boolean,
    calendarNeedsUpdate: Boolean,
): String {
    if (!ready) return "完成系统权限后才能准时拦截"
    if (calendarNeedsUpdate && evaluation.activeRules.isEmpty()) {
        return "工作日与交易日规则暂不执行"
    }
    if (evaluation.activeRules.isNotEmpty()) {
        val lastRule = evaluation.activeRules.maxBy { rule -> remainingRuleMinutes(rule, now) }
        val activeDetail =
            "${evaluation.blockedPackages.size} 个应用，最晚至 ${formatMinuteOfDay(lastRule.endMinute)}"
        return if (calendarNeedsUpdate) "$activeDetail；部分日历规则暂停" else activeDetail
    }
    return evaluation.nextBoundary?.let { boundary ->
        "下次边界 ${boundary.format(NEXT_BOUNDARY_FORMATTER)}"
    } ?: "当前没有启用的规则"
}

/** 空状态提供直接新建入口，不使用装饰卡片。 */
@Composable
private fun EmptyTodayRules() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("今天没有规则", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "点击右下角添加",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private val NEXT_BOUNDARY_FORMATTER = DateTimeFormatter.ofPattern("E HH:mm", Locale.CHINA)
