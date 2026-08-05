package com.cwenhe.timefence.enforcement

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.cwenhe.timefence.calendar.CalendarSnapshot
import android.os.Build
import com.cwenhe.timefence.rules.ScheduleEvaluator
import com.cwenhe.timefence.rules.ScheduleRule
import java.time.ZonedDateTime

/**
 * 始终只注册一条最近的规则边界闹钟。
 *
 * @param context 用于访问 `AlarmManager` 和创建显式广播 Intent。
 * @param scheduleEvaluator 负责计算严格晚于当前时刻的下一条规则边界。
 */
class BoundaryAlarmScheduler(
    context: Context,
    private val scheduleEvaluator: ScheduleEvaluator,
) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    /** 取消旧边界并按最新规则注册下一条，缺少精确授权时降级为允许休眠的非精确闹钟。 */
    @SuppressLint("ScheduleExactAlarm")
    fun reschedule(
        rules: List<ScheduleRule>,
        now: ZonedDateTime = ZonedDateTime.now(),
        calendar: CalendarSnapshot = CalendarSnapshot.empty(),
    ) {
        val operation = boundaryPendingIntent()
        alarmManager.cancel(operation)
        val nextBoundary = scheduleEvaluator.evaluate(now, rules, calendar).nextBoundary ?: return
        val triggerAtMillis = nextBoundary.toInstant().toEpochMilli()
        if (canScheduleExactAlarms()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    operation,
                )
                return
            } catch (_: SecurityException) {
                // 授权状态可能在检查和注册之间变化，此时继续使用可用的降级路径。
            }
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
    }

    /** 取消当前固定 PendingIntent 对应的边界闹钟。 */
    fun cancel() {
        alarmManager.cancel(boundaryPendingIntent())
    }

    /** Android 12 以下无需额外授权即可使用精确闹钟。 */
    private fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    /** 创建全应用唯一、不可变且显式指向边界接收器的 PendingIntent。 */
    private fun boundaryPendingIntent(): PendingIntent {
        val intent = Intent(appContext, BoundaryAlarmReceiver::class.java).setAction(ACTION_BOUNDARY)
        return PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val ACTION_BOUNDARY = "com.cwenhe.timefence.action.CHECK_BOUNDARY"
        private const val REQUEST_CODE = 1_001
    }
}
