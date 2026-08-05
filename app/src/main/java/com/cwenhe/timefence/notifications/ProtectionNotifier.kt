package com.cwenhe.timefence.notifications

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cwenhe.timefence.MainActivity
import com.cwenhe.timefence.R
import com.cwenhe.timefence.calendar.CalendarMatch
import com.cwenhe.timefence.calendar.CalendarSnapshot
import com.cwenhe.timefence.enforcement.BlockFeedback
import com.cwenhe.timefence.permissions.PermissionStatus
import com.cwenhe.timefence.rules.CalendarMode
import com.cwenhe.timefence.rules.ScheduleEvaluator
import com.cwenhe.timefence.rules.ScheduleRule
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 在用户允许通知时展示当前保护状态，不参与核心拦截判断。 */
class ProtectionNotifier(
    context: Context,
    private val scheduleEvaluator: ScheduleEvaluator,
) {
    private val appContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(appContext)

    init {
        createChannel()
    }

    /** 根据最新规则和权限刷新状态通知；没有规则或未授权时取消通知。 */
    @SuppressLint("MissingPermission")
    fun update(
        rules: List<ScheduleRule>,
        permissions: PermissionStatus,
        now: ZonedDateTime = ZonedDateTime.now(),
        calendar: CalendarSnapshot = CalendarSnapshot.empty(),
    ) {
        if (rules.none { it.enabled } || !permissions.notificationsAllowed) {
            notificationManager.cancel(NOTIFICATION_ID)
            return
        }
        val evaluation = scheduleEvaluator.evaluate(now, rules, calendar)
        val calendarNeedsUpdate = rules.any { rule ->
            rule.enabled &&
                rule.calendarMode != CalendarMode.WEEKLY &&
                calendar.match(rule.calendarMode, now.toLocalDate()) == CalendarMatch.UNKNOWN
        }
        val title = when {
            !permissions.protectionReady -> "时界需要设置"
            calendarNeedsUpdate && evaluation.activeRules.isEmpty() -> "日历需要更新"
            evaluation.activeRules.isNotEmpty() -> "限制生效中"
            else -> "保护已就绪"
        }
        val detail = when {
            !permissions.protectionReady -> "打开时界完成系统权限设置"
            calendarNeedsUpdate && evaluation.activeRules.isEmpty() -> "工作日与交易日规则暂不执行"
            evaluation.activeRules.isNotEmpty() -> if (calendarNeedsUpdate) {
                "正在限制 ${evaluation.blockedPackages.size} 个应用；部分日历规则暂停"
            } else {
                "正在限制 ${evaluation.blockedPackages.size} 个应用"
            }
            evaluation.nextBoundary != null ->
                "下次边界 ${evaluation.nextBoundary.format(BOUNDARY_FORMATTER)}"
            else -> "等待下一条规则"
        }
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timefence_notification)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(openAppPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setOngoing(permissions.protectionReady)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching { notificationManager.notify(NOTIFICATION_ID, notification) }
    }

    /** 展示一次性拦截事件通知，文本与浮层和 TTS 使用同一反馈对象。 */
    @SuppressLint("MissingPermission")
    fun showInterception(feedback: BlockFeedback) {
        if (!notificationManager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(appContext, EVENT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timefence_notification)
            .setContentTitle(feedback.ruleName.ifBlank { "时界限制" })
            .setContentText(feedback.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(feedback.text))
            .setContentIntent(openAppPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setTimeoutAfter(EVENT_TIMEOUT_MILLIS)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { notificationManager.notify(EVENT_NOTIFICATION_ID, notification) }
    }

    /** Android 8 及以上创建常驻保护状态和一次性拦截提示渠道。 */
    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "保护状态",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示时界规则是否正在生效"
            setShowBadge(false)
        }
        appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val eventChannel = NotificationChannel(
            EVENT_CHANNEL_ID,
            "拦截提示",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "显示自定义的应用拦截提示"
        }
        appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(eventChannel)
    }

    /** 创建点击通知后复用时界主界面的不可变 PendingIntent。 */
    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        appContext,
        0,
        Intent(appContext, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val CHANNEL_ID = "protection_status"
        private const val EVENT_CHANNEL_ID = "protection_interception"
        private const val NOTIFICATION_ID = 1_001
        private const val EVENT_NOTIFICATION_ID = 1_002
        private const val EVENT_TIMEOUT_MILLIS = 5_000L
        private val BOUNDARY_FORMATTER = DateTimeFormatter.ofPattern("E HH:mm", Locale.CHINA)
    }
}
