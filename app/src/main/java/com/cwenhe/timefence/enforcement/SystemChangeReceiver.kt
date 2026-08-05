package com.cwenhe.timefence.enforcement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.cwenhe.timefence.TimeFenceApplication
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 在开机、升级及系统时间环境变化后恢复当前检查和下一条边界。 */
class SystemChangeReceiver : BroadcastReceiver() {
    /** 对支持的系统变化立即请求一次检查，并在异步任务中按当前时间重建闹钟。 */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SUPPORTED_ACTIONS) return
        EnforcementBridge.requestBoundaryCheck(context.applicationContext)
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val container = (context.applicationContext as TimeFenceApplication).container
                container.calendarRepository.initialize()
                val rules = container.scheduleRepository.getRules()
                container.boundaryAlarmScheduler.reschedule(
                    rules = rules,
                    now = ZonedDateTime.now(),
                    calendar = container.calendarRepository.snapshot.value,
                )
                val permissions = container.permissionStatusRepository.refresh()
                container.protectionNotifier.update(
                    rules = rules,
                    permissions = permissions,
                    calendar = container.calendarRepository.snapshot.value,
                )
            } catch (error: Exception) {
                Log.e(TAG, "恢复规则边界失败", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "SystemChangeReceiver"
        private const val ACTION_EXACT_ALARM_PERMISSION_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            ACTION_EXACT_ALARM_PERMISSION_CHANGED,
        )
    }
}
