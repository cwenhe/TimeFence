package com.cwenhe.timefence.core

import android.content.Context
import com.cwenhe.timefence.apps.InstalledAppRepository
import com.cwenhe.timefence.data.ScheduleRepository
import com.cwenhe.timefence.data.local.TimeFenceDatabase
import com.cwenhe.timefence.enforcement.BoundaryAlarmScheduler
import com.cwenhe.timefence.enforcement.EnforcementBridge
import com.cwenhe.timefence.notifications.ProtectionNotifier
import com.cwenhe.timefence.permissions.PermissionStatusRepository
import com.cwenhe.timefence.permissions.SystemSettingsNavigator
import com.cwenhe.timefence.rules.ScheduleEvaluator
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 集中装配时界的进程级依赖，避免界面和系统组件自行创建数据库。 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database = TimeFenceDatabase.create(appContext)

    val scheduleEvaluator = ScheduleEvaluator()
    val scheduleRepository = ScheduleRepository(database.ruleDao())
    val installedAppRepository = InstalledAppRepository(appContext)
    val permissionStatusRepository = PermissionStatusRepository(appContext)
    val systemSettingsNavigator = SystemSettingsNavigator(appContext)
    val boundaryAlarmScheduler = BoundaryAlarmScheduler(appContext, scheduleEvaluator)
    val protectionNotifier = ProtectionNotifier(appContext, scheduleEvaluator)

    /** 观察规则变化，并同步执行当前窗口补检和下一边界重建。 */
    fun start() {
        applicationScope.launch {
            scheduleRepository.observeRules()
                .catch { permissionStatusRepository.refresh() }
                .collect { rules ->
                    EnforcementBridge.requestBoundaryCheck(appContext)
                    boundaryAlarmScheduler.reschedule(rules, ZonedDateTime.now())
                    permissionStatusRepository.refresh()
                }
        }
        applicationScope.launch {
            combine(
                scheduleRepository.observeRules(),
                permissionStatusRepository.status,
                ::Pair,
            ).collect { (rules, permissions) ->
                protectionNotifier.update(rules, permissions)
            }
        }
    }
}
