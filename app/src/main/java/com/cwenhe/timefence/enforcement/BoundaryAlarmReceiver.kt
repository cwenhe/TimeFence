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

/** 接收唯一的规则边界闹钟，并在请求前台检查后安排下一条边界。 */
class BoundaryAlarmReceiver : BroadcastReceiver() {
    /** 同步通知已连接服务，再异步读取数据库并重建下一条闹钟。 */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BoundaryAlarmScheduler.ACTION_BOUNDARY) return
        EnforcementBridge.requestBoundaryCheck(context.applicationContext)
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val container = (context.applicationContext as TimeFenceApplication).container
                val rules = container.scheduleRepository.getRules()
                container.boundaryAlarmScheduler.reschedule(rules, ZonedDateTime.now())
                val permissions = container.permissionStatusRepository.refresh()
                container.protectionNotifier.update(rules, permissions)
            } catch (error: Exception) {
                Log.e(TAG, "处理规则边界失败", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BoundaryAlarmReceiver"
    }
}
