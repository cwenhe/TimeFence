package com.cwenhe.timefence.core

import android.content.Context
import com.cwenhe.timefence.apps.InstalledAppRepository
import com.cwenhe.timefence.calendar.CalendarSyncWorker
import com.cwenhe.timefence.calendar.CalendarRepository
import com.cwenhe.timefence.data.ScheduleRepository
import com.cwenhe.timefence.data.local.TimeFenceDatabase
import com.cwenhe.timefence.enforcement.BoundaryAlarmScheduler
import com.cwenhe.timefence.enforcement.BlockSpeechController
import com.cwenhe.timefence.enforcement.EnforcementBridge
import com.cwenhe.timefence.enforcement.SpeechSettingsStore
import com.cwenhe.timefence.notifications.ProtectionNotifier
import com.cwenhe.timefence.permissions.PermissionStatusRepository
import com.cwenhe.timefence.permissions.SystemSettingsNavigator
import com.cwenhe.timefence.rules.ScheduleEvaluator
import com.cwenhe.timefence.rules.ScheduleRule
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 集中装配时界的进程级依赖，避免界面和系统组件自行创建数据库。 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database = TimeFenceDatabase.create(appContext)

    val scheduleEvaluator = ScheduleEvaluator()
    val scheduleRepository = ScheduleRepository(database.ruleDao())
    val calendarRepository = CalendarRepository(appContext, database.calendarDao())
    val installedAppRepository = InstalledAppRepository(appContext)
    val permissionStatusRepository = PermissionStatusRepository(appContext)
    val systemSettingsNavigator = SystemSettingsNavigator(appContext)
    val boundaryAlarmScheduler = BoundaryAlarmScheduler(appContext, scheduleEvaluator)
    val protectionNotifier = ProtectionNotifier(appContext, scheduleEvaluator)
    val speechSettingsStore = SpeechSettingsStore(appContext)
    val blockSpeechController = BlockSpeechController(appContext, speechSettingsStore)
    val ruleSnapshot = scheduleRepository.observeRules()
        .catch { permissionStatusRepository.refresh() }
        .map { rules -> RuleSnapshot(loaded = true, rules = rules) }
        .stateIn(
            scope = applicationScope,
            started = SharingStarted.Eagerly,
            initialValue = RuleSnapshot(loaded = false, rules = emptyList()),
        )

    /** 观察规则变化，并同步执行当前窗口补检和下一边界重建。 */
    fun start() {
        applicationScope.launch {
            runCatching {
                calendarRepository.initialize()
                CalendarSyncWorker.enqueuePeriodic(appContext)
                if (calendarRepository.needsSync()) CalendarSyncWorker.enqueueImmediate(appContext)
            }
        }
        applicationScope.launch {
            combine(ruleSnapshot, calendarRepository.snapshot, ::Pair).collect { (snapshot, calendar) ->
                if (!snapshot.loaded) return@collect
                val rules = snapshot.rules
                EnforcementBridge.requestBoundaryCheck(appContext)
                boundaryAlarmScheduler.reschedule(
                    rules = rules,
                    now = ZonedDateTime.now(),
                    calendar = calendar,
                )
                permissionStatusRepository.refresh()
            }
        }
        applicationScope.launch {
            combine(
                ruleSnapshot,
                permissionStatusRepository.status,
                calendarRepository.snapshot,
            ) { snapshot, permissions, calendar -> Triple(snapshot, permissions, calendar) }
                .collect { (snapshot, permissions, calendar) ->
                    if (snapshot.loaded) {
                        protectionNotifier.update(
                            rules = snapshot.rules,
                            permissions = permissions,
                            calendar = calendar,
                        )
                    }
                }
        }
    }
}

/** 提供单一规则快照及其首次加载状态，避免无障碍热路径误读空值。 */
data class RuleSnapshot(
    val loaded: Boolean,
    val rules: List<ScheduleRule>,
)
