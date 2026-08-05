package com.cwenhe.timefence.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cwenhe.timefence.rules.ScheduleRule
import com.cwenhe.timefence.ui.TimeFenceUiState
import com.cwenhe.timefence.ui.components.RuleRow

/** 展示全部规则，并提供新建、编辑和启停操作。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(
    state: TimeFenceUiState,
    onAddRule: () -> Unit,
    onEditRule: (ScheduleRule) -> Unit,
    onToggleRule: (ScheduleRule, Boolean) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("规则") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddRule) {
                Icon(Icons.Outlined.Add, contentDescription = "新建规则")
            }
        },
    ) { padding ->
        if (state.rules.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("还没有规则", style = MaterialTheme.typography.titleLarge)
                Text(
                    "点击右下角添加第一条规则",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                items(state.rules.size, key = { state.rules[it].id }) { index ->
                    val rule = state.rules[index]
                    RuleRow(
                        rule = rule,
                        locked = rule.lockWhileActive && rule in state.evaluation.activeRules,
                        onClick = { onEditRule(rule) },
                        onEnabledChange = { enabled -> onToggleRule(rule, enabled) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
