package com.cwenhe.timefence.core

import android.content.Context
import android.os.Process
import com.cwenhe.timefence.apps.InstalledAppRepository
import com.cwenhe.timefence.apps.ProtectedPackageResolver
import com.cwenhe.timefence.calendar.CalendarSnapshot
import com.cwenhe.timefence.calendar.CalendarRepository
import com.cwenhe.timefence.calendar.CalendarSyncWorker
import com.cwenhe.timefence.data.ScheduleRepository
import com.cwenhe.timefence.data.local.TimeFenceDatabase
import com.cwenhe.timefence.enforcement.BoundaryAlarmScheduler
import com.cwenhe.timefence.enforcement.BlockSpeechController
import com.cwenhe.timefence.enforcement.EnforcementBridge
import com.cwenhe.timefence.enforcement.SpeechSettingsStore
import com.cwenhe.timefence.notifications.ProtectionNotifier
import com.cwenhe.timefence.permissions.PermissionStatusRepository
import com.cwenhe.timefence.permissions.PermissionStatus
import com.cwenhe.timefence.permissions.SystemSettingsNavigator
import com.cwenhe.timefence.rules.ScheduleEvaluator
import com.cwenhe.timefence.rules.ScheduleRule
import com.cwenhe.timefence.suspension.AndroidPackageSuspensionInspector
import com.cwenhe.timefence.suspension.ShizukuSuspendGateway
import com.cwenhe.timefence.suspension.SuspendActionResult
import com.cwenhe.timefence.suspension.SystemSuspendController
import com.cwenhe.timefence.suspension.SystemSuspendSettingsStore
import com.cwenhe.timefence.suspension.SystemSuspendStatus
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

/** 集中装配仓库、调度器、无障碍保护和 Shizuku 暂停协调器。 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database = TimeFenceDatabase.create(appContext)

    val scheduleEvaluator = ScheduleEvaluator()
    val scheduleRepository = ScheduleRepository(database.ruleDao())
    val calendarRepository = CalendarRepository(appContext, database.calendarDao())
    val protectedPackageResolver = ProtectedPackageResolver(appContext)
    val installedAppRepository = InstalledAppRepository(appContext, protectedPackageResolver)
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
    val systemSuspendSettingsStore = SystemSuspendSettingsStore(appContext)
    val shizukuSuspendGateway = ShizukuSuspendGateway(appContext)
    val systemSuspendController = SystemSuspendController(
        scope = applicationScope,
        gateway = shizukuSuspendGateway,
        store = systemSuspendSettingsStore,
        inspector = AndroidPackageSuspensionInspector(appContext),
        protectedPackages = protectedPackageResolver::resolve,
        desiredPackages = {
            val snapshot = ruleSnapshot.value
            if (!snapshot.loaded) {
                null
            } else {
                scheduleEvaluator.evaluateActive(
                    now = ZonedDateTime.now(),
                    rules = snapshot.rules,
                    calendar = calendarRepository.snapshot.value,
                ).blockedPackages
            }
        },
        userId = Process.myUid() / ANDROID_UIDS_PER_USER,
    )

    /** 观察规则变化，并同步执行当前窗口补检和下一边界重建。 */
    fun start() {
        systemSuspendController.start()
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
                systemSuspendController.requestReconcile()
                permissionStatusRepository.refresh()
            }
        }
        applicationScope.launch {
            combine(
                ruleSnapshot,
                permissionStatusRepository.status,
                calendarRepository.snapshot,
                systemSuspendController.status,
            ) { snapshot, permissions, calendar, systemSuspend ->
                NotificationSnapshot(snapshot, permissions, calendar, systemSuspend)
            }
                .collect { notification ->
                    val snapshot = notification.rules
                    if (snapshot.loaded) {
                        protectionNotifier.update(
                            rules = snapshot.rules,
                            permissions = notification.permissions,
                            calendar = notification.calendar,
                            systemSuspend = notification.systemSuspend,
                        )
                    }
                }
        }
    }

    /** 使用广播已经读取的规则立即校正系统暂停状态。 */
    suspend fun reconcileSystemSuspensions(
        rules: List<ScheduleRule>,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): SuspendActionResult {
        val desiredPackages = scheduleEvaluator.evaluateActive(
            now = now,
            rules = rules,
            calendar = calendarRepository.snapshot.value,
        ).blockedPackages
        return systemSuspendController.reconcileDesiredPackages(desiredPackages)
    }

    private companion object {
        /** Android 为每个用户保留的 UID 数量，用于从当前进程 UID 推导用户编号。 */
        const val ANDROID_UIDS_PER_USER = 100_000
    }
}

/** 提供单一规则快照及其首次加载状态，避免无障碍热路径误读空值。 */
data class RuleSnapshot(
    val loaded: Boolean,
    val rules: List<ScheduleRule>,
)

/** 合并通知刷新所需的规则、权限、日历和系统暂停状态。 */
private data class NotificationSnapshot(
    val rules: RuleSnapshot,
    val permissions: PermissionStatus,
    val calendar: CalendarSnapshot,
    val systemSuspend: SystemSuspendStatus,
)
