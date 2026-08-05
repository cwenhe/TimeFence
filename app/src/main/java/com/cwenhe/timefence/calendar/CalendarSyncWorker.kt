package com.cwenhe.timefence.calendar

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cwenhe.timefence.TimeFenceApplication
import java.util.concurrent.TimeUnit

/** 在网络可用时定期更新日历，失败时由 WorkManager 按退避策略重试。 */
class CalendarSyncWorker(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    /** 执行一次日历同步并将网络或解析失败交给 WorkManager 重试。 */
    override suspend fun doWork(): Result {
        val application = applicationContext as? TimeFenceApplication ?: return Result.failure()
        return when (application.container.calendarRepository.syncNow()) {
            CalendarSyncResult.UPDATED,
            CalendarSyncResult.NOT_MODIFIED,
            -> Result.success()

            CalendarSyncResult.FAILED -> Result.retry()
        }
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "calendar-periodic-sync"
        private const val IMMEDIATE_WORK_NAME = "calendar-immediate-sync"

        /** 注册每 30 天一次且只在网络连接时执行的唯一任务。 */
        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<CalendarSyncWorker>(30, TimeUnit.DAYS)
                .setConstraints(networkConstraints())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** 注册一次性同步任务，用于启动过期检查和设置页手动更新。 */
        fun enqueueImmediate(context: Context) {
            val request = OneTimeWorkRequestBuilder<CalendarSyncWorker>()
                .setConstraints(networkConstraints())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        /** 约束同步任务只在设备联网时运行，断网由本地快照继续服务。 */
        private fun networkConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}
