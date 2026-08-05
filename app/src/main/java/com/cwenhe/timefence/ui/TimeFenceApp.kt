package com.cwenhe.timefence.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cwenhe.timefence.rules.ScheduleRule
import com.cwenhe.timefence.ui.dashboard.DashboardScreen
import com.cwenhe.timefence.ui.editor.RuleEditorScreen
import com.cwenhe.timefence.ui.rules.RulesScreen
import com.cwenhe.timefence.ui.settings.SettingsScreen
import com.cwenhe.timefence.ui.setup.SetupScreen

/** 组织首次设置、底部导航、规则编辑和全局错误反馈。 */
@Composable
fun TimeFenceApp(viewModel: TimeFenceViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val onboardingPreferences = remember {
        context.getSharedPreferences(ONBOARDING_PREFERENCES, android.content.Context.MODE_PRIVATE)
    }
    var setupPassed by rememberSaveable {
        mutableStateOf(onboardingPreferences.getBoolean(KEY_SETUP_SEEN, false))
    }
    if (!setupPassed) {
        SetupScreen(
            status = state.permissions,
            onAccessibility = viewModel::openAccessibilitySettings,
            onExactAlarm = viewModel::openExactAlarmSettings,
            onNotifications = viewModel::openNotificationSettings,
            onBattery = viewModel::openBatterySettings,
            onContinue = {
                onboardingPreferences.edit().putBoolean(KEY_SETUP_SEEN, true).apply()
                setupPassed = true
            },
        )
        return
    }

    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.errorMessage) {
        val message = state.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearError()
    }
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val mainRoutes = MAIN_DESTINATIONS.map(MainDestination::route)
    Scaffold(
        bottomBar = {
            if (currentRoute in mainRoutes) {
                MainNavigationBar(navController, currentRoute)
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = ROUTE_TODAY,
            modifier = Modifier.padding(padding),
        ) {
            composable(ROUTE_TODAY) {
                DashboardScreen(
                    state = state,
                    onAddRule = { navController.navigate(editorRoute(NEW_RULE_ID)) },
                    onEditRule = { rule -> navController.navigate(editorRoute(rule.id)) },
                    onToggleRule = viewModel::setRuleEnabled,
                    onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                )
            }
            composable(ROUTE_RULES) {
                RulesScreen(
                    state = state,
                    onAddRule = { navController.navigate(editorRoute(NEW_RULE_ID)) },
                    onEditRule = { rule -> navController.navigate(editorRoute(rule.id)) },
                    onToggleRule = viewModel::setRuleEnabled,
                )
            }
            composable(ROUTE_SETTINGS) {
                SettingsScreen(
                    status = state.permissions,
                    calendarStatus = state.calendarStatus,
                    today = state.now.toLocalDate(),
                    speechSettings = state.speechSettings,
                    onAccessibility = viewModel::openAccessibilitySettings,
                    onExactAlarm = viewModel::openExactAlarmSettings,
                    onNotifications = viewModel::openNotificationSettings,
                    onBattery = viewModel::openBatterySettings,
                    onHonorBackground = viewModel::openHonorBackgroundSettings,
                    onAppDetails = viewModel::openAppDetailsSettings,
                    onSyncCalendar = viewModel::syncCalendar,
                    onSpeechEnabled = viewModel::setSpeechEnabled,
                    onSpeechLanguage = viewModel::setSpeechLanguage,
                    onTextToSpeechSettings = viewModel::openTextToSpeechSettings,
                )
            }
            composable(ROUTE_EDITOR) { entry ->
                val ruleId = entry.arguments?.getString(ARG_RULE_ID)?.toLongOrNull() ?: NEW_RULE_ID
                val existingRule = state.rules.firstOrNull { rule -> rule.id == ruleId }
                RuleEditorScreen(
                    existingRule = existingRule,
                    installedApps = state.installedApps,
                    appsLoading = state.appsLoading,
                    locked = existingRule?.let(viewModel::isRuleLocked) ?: false,
                    calendarStatus = state.calendarStatus,
                    onBack = navController::navigateUp,
                    onSave = { rule ->
                        viewModel.saveRule(rule) { navController.navigateUp() }
                    },
                    onDelete = { rule ->
                        viewModel.deleteRule(rule) { navController.navigateUp() }
                    },
                )
            }
        }
    }
}

/** 展示今天、规则和设置三个稳定主入口。 */
@Composable
private fun MainNavigationBar(navController: NavHostController, currentRoute: String?) {
    NavigationBar {
        MAIN_DESTINATIONS.forEach { destination ->
            NavigationBarItem(
                selected = currentRoute == destination.route,
                onClick = {
                    navController.navigate(destination.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(destination.icon, contentDescription = destination.label) },
                label = { Text(destination.label) },
            )
        }
    }
}

/** 将规则标识编码为编辑页面路由。 */
private fun editorRoute(ruleId: Long): String = "editor/$ruleId"

/** 描述一个底部导航入口。 */
private data class MainDestination(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

private const val ROUTE_TODAY = "today"
private const val ROUTE_RULES = "rules"
private const val ROUTE_SETTINGS = "settings"
private const val ARG_RULE_ID = "ruleId"
private const val ROUTE_EDITOR = "editor/{$ARG_RULE_ID}"
private const val NEW_RULE_ID = 0L
private const val ONBOARDING_PREFERENCES = "onboarding"
private const val KEY_SETUP_SEEN = "setup_seen"
private val MAIN_DESTINATIONS = listOf(
    MainDestination(ROUTE_TODAY, "今天", Icons.Outlined.CalendarToday),
    MainDestination(ROUTE_RULES, "规则", Icons.AutoMirrored.Outlined.FormatListBulleted),
    MainDestination(ROUTE_SETTINGS, "设置", Icons.Outlined.Settings),
)
