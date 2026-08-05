package com.cwenhe.timefence.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** 提供日历逐日数据和同步元数据的本地事务访问。 */
@Dao
interface CalendarDao {
    /** 观察当前完整日历行，供仓库重建不可变快照。 */
    @Query("SELECT * FROM calendar_days ORDER BY date")
    fun observeDays(): Flow<List<CalendarDayEntity>>

    /** 读取最近一次同步元数据，缺少数据时返回空值。 */
    @Query("SELECT * FROM calendar_metadata WHERE id = 1")
    suspend fun getMetadata(): CalendarMetadataEntity?

    /** 更新同步尝试结果而不修改已经缓存的逐日数据。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateMetadata(metadata: CalendarMetadataEntity)

    /** 用一份已完整校验的数据集原子替换旧日历。 */
    @Transaction
    suspend fun replaceDataset(
        days: List<CalendarDayEntity>,
        metadata: CalendarMetadataEntity,
    ) {
        deleteDays()
        insertDays(days)
        insertMetadata(metadata)
    }

    /** 清空旧日历行，调用方必须在事务中补写新数据。 */
    @Query("DELETE FROM calendar_days")
    suspend fun deleteDays()

    /** 批量写入已经通过文档校验的日历行。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDays(days: List<CalendarDayEntity>)

    /** 写入单例同步元数据。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMetadata(metadata: CalendarMetadataEntity)
}
