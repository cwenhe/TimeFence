package com.cwenhe.timefence.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 保存一个自然日的工作日和 A 股共同交易日状态。 */
@Entity(tableName = "calendar_days")
data class CalendarDayEntity(
    @PrimaryKey val date: String,
    val isStatutoryWorkday: Boolean,
    val isAShareTradingDay: Boolean,
    val sourceVersion: Long,
    val updatedAtEpochMillis: Long,
)

/** 保存日历数据集版本、覆盖范围和最近同步结果。 */
@Entity(tableName = "calendar_metadata")
data class CalendarMetadataEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val revision: Long,
    val locale: String,
    val coveredFrom: String,
    val coveredTo: String,
    val etag: String?,
    val lastSuccessfulSyncAt: Long?,
    val lastAttemptAt: Long?,
    val lastError: String?,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
