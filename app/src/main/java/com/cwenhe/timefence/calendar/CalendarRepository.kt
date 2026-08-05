package com.cwenhe.timefence.calendar

import android.content.Context
import com.cwenhe.timefence.R
import com.cwenhe.timefence.data.local.CalendarDao
import com.cwenhe.timefence.data.local.CalendarDayEntity
import com.cwenhe.timefence.data.local.CalendarMetadataEntity
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 汇总内置、Room 缓存和远程更新，向规则求值提供无 IO 日历快照。 */
class CalendarRepository internal constructor(
    private val calendarDao: CalendarDao,
    private val remoteFetch: suspend (String?, Long) -> CalendarFetchResult,
    private val builtinLoader: () -> String,
) {
    /** 创建读取 APK raw 资源并使用固定 HTTPS 数据源的生产仓库。 */
    constructor(
        context: Context,
        calendarDao: CalendarDao,
        remoteDataSource: CalendarRemoteDataSource = CalendarRemoteDataSource(),
    ) : this(
        calendarDao = calendarDao,
        remoteFetch = remoteDataSource::fetch,
        builtinLoader = {
            context.applicationContext.resources.openRawResource(R.raw.zh_cn_calendar)
                .bufferedReader(Charsets.UTF_8)
                .use { reader -> reader.readText() }
        },
    )

    private val initializeMutex = Mutex()
    private val syncMutex = Mutex()
    private var initialized = false
    private val mutableSnapshot = MutableStateFlow(CalendarSnapshot.empty())
    private val mutableStatus = MutableStateFlow(CalendarStatus.initial())

    val snapshot: StateFlow<CalendarSnapshot> = mutableSnapshot.asStateFlow()
    val status: StateFlow<CalendarStatus> = mutableStatus.asStateFlow()

    /** 从 Room 或 APK 内置数据初始化快照，确保首次求值不依赖网络。 */
    suspend fun initialize() = initializeMutex.withLock {
        if (initialized) return@withLock
        try {
            val storedDays = calendarDao.observeDays().first()
            if (storedDays.isNotEmpty()) {
                publish(storedDays, calendarDao.getMetadata())
            } else {
                seedBuiltinCalendar()
            }
            initialized = true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableStatus.value = mutableStatus.value.copy(lastError = error.message ?: "日历初始化失败")
            throw error
        }
    }

    /** 判断最近一次联网尝试是否超过 30 天，避免每次进程启动都请求远程文件。 */
    suspend fun needsSync(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val lastAttempt = calendarDao.getMetadata()?.lastAttemptAt ?: return true
        return nowMillis - lastAttempt >= SYNC_INTERVAL_MILLIS
    }

    /** 下载并原子替换更高版本日历，失败时保留现有快照。 */
    suspend fun syncNow(): CalendarSyncResult {
        try {
            initialize()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return CalendarSyncResult.FAILED
        }
        return syncMutex.withLock {
            val now = System.currentTimeMillis()
            val metadata = calendarDao.getMetadata()
            mutableStatus.value = mutableStatus.value.copy(isSyncing = true, lastError = null)
            try {
                when (val result = remoteFetch(metadata?.etag, metadata?.revision ?: Long.MIN_VALUE)) {
                    CalendarFetchResult.NotModified -> {
                        val updatedMetadata = metadata?.copy(
                            lastSuccessfulSyncAt = now,
                            lastAttemptAt = now,
                            lastError = null,
                        )
                        if (updatedMetadata != null) {
                            calendarDao.updateMetadata(updatedMetadata)
                            mutableStatus.value = CalendarStatus.fromSnapshot(
                                mutableSnapshot.value,
                                updatedMetadata,
                                isSyncing = false,
                            )
                        } else {
                            mutableStatus.value = mutableStatus.value.copy(isSyncing = false, lastError = null)
                        }
                        CalendarSyncResult.NOT_MODIFIED
                    }

                    is CalendarFetchResult.Updated -> {
                        if (metadata != null && result.document.revision == metadata.revision) {
                            val updatedMetadata = metadata.copy(
                                etag = result.etag ?: metadata.etag,
                                lastSuccessfulSyncAt = now,
                                lastAttemptAt = now,
                                lastError = null,
                            )
                            calendarDao.updateMetadata(updatedMetadata)
                            mutableStatus.value = CalendarStatus.fromSnapshot(
                                mutableSnapshot.value,
                                updatedMetadata,
                                isSyncing = false,
                            )
                            return@withLock CalendarSyncResult.NOT_MODIFIED
                        }
                        requireCoverageNotShrunk(result.document)
                        val days = result.document.toEntities(now)
                        val updatedMetadata = result.document.toMetadata(result.etag, now)
                        calendarDao.replaceDataset(days, updatedMetadata)
                        publish(days, updatedMetadata)
                        CalendarSyncResult.UPDATED
                    }
                }
            } catch (error: CancellationException) {
                mutableStatus.value = mutableStatus.value.copy(isSyncing = false)
                throw error
            } catch (error: Exception) {
                val failedMetadata = metadata?.copy(
                    lastAttemptAt = now,
                    lastError = error.message ?: "日历更新失败",
                )
                if (failedMetadata != null) calendarDao.updateMetadata(failedMetadata)
                mutableStatus.value = mutableStatus.value.copy(
                    isSyncing = false,
                    lastError = error.message ?: "日历更新失败",
                )
                CalendarSyncResult.FAILED
            }
        }
    }

    /** 从 APK raw 资源写入已审核的基础年份，保证首次启动离线可用。 */
    private suspend fun seedBuiltinCalendar() {
        val content = builtinLoader()
        val document = CalendarDocumentParser().parse(content)
        val days = document.toEntities(System.currentTimeMillis())
        val metadata = document.toMetadata(etag = null, now = null)
        calendarDao.replaceDataset(days, metadata)
        publish(days, metadata)
    }

    /** 拒绝删除现有覆盖边界的远程文档，年度更新只能扩展或保持范围。 */
    private fun requireCoverageNotShrunk(document: CalendarDocument) {
        val currentFrom = mutableSnapshot.value.coveredFrom() ?: return
        val currentTo = mutableSnapshot.value.coveredTo() ?: return
        val remoteDates = document.years.flatMap { year -> year.days }.map { day -> LocalDate.parse(day.date) }
        val remoteFrom = remoteDates.minOrNull() ?: error("日历文档没有日期")
        val remoteTo = remoteDates.maxOrNull() ?: error("日历文档没有日期")
        require(!remoteFrom.isAfter(currentFrom) && !remoteTo.isBefore(currentTo)) {
            "日历文档不能缩小现有覆盖范围"
        }
    }

    /** 发布新的 Room 行和元数据，使所有消费者读取同一份不可变快照。 */
    private fun publish(days: List<CalendarDayEntity>, metadata: CalendarMetadataEntity?) {
        mutableSnapshot.value = CalendarSnapshot.fromEntities(days)
        mutableStatus.value = CalendarStatus.fromSnapshot(
            snapshot = mutableSnapshot.value,
            metadata = metadata,
            isSyncing = false,
        )
    }

    private companion object {
        const val SYNC_INTERVAL_MILLIS = 30L * 24 * 60 * 60 * 1000

        /** 将文档逐日转换为 Room 行，并记录同一批次的写入时间。 */
        fun CalendarDocument.toEntities(updatedAt: Long): List<CalendarDayEntity> = years
            .flatMap { year -> year.days }
            .map { day ->
                CalendarDayEntity(
                    date = day.date,
                    isStatutoryWorkday = day.isStatutoryWorkday,
                    isAShareTradingDay = day.isAShareTradingDay,
                    sourceVersion = revision,
                    updatedAtEpochMillis = updatedAt,
                )
            }

        /** 将文档元数据转换为单例 Room 元数据行。 */
        fun CalendarDocument.toMetadata(etag: String?, now: Long?): CalendarMetadataEntity {
            val allDates = years.flatMap { year -> year.days }.map { day -> day.date }.sorted()
            return CalendarMetadataEntity(
                revision = revision,
                locale = locale,
                coveredFrom = allDates.first(),
                coveredTo = allDates.last(),
                etag = etag,
                lastSuccessfulSyncAt = now,
                lastAttemptAt = now,
                lastError = null,
            )
        }
    }
}

/** 向界面展示日历覆盖和同步状态，避免直接暴露 Room 实体。 */
data class CalendarStatus(
    val revision: Long?,
    val coveredFrom: LocalDate?,
    val coveredTo: LocalDate?,
    val lastSuccessfulSyncAt: Long?,
    val lastError: String?,
    val isSyncing: Boolean,
) {
    /** 判断给定自然日是否落在当前完整日历的闭区间覆盖内。 */
    fun covers(date: LocalDate): Boolean =
        coveredFrom?.let { from -> coveredTo?.let { to -> date in from..to } } == true

    companion object {
        /** 创建尚未初始化日历时的明确空状态。 */
        fun initial(): CalendarStatus = CalendarStatus(null, null, null, null, null, false)

        /** 根据快照和元数据构造可展示的同步状态。 */
        fun fromSnapshot(
            snapshot: CalendarSnapshot,
            metadata: CalendarMetadataEntity?,
            isSyncing: Boolean,
        ): CalendarStatus = CalendarStatus(
            revision = metadata?.revision,
            coveredFrom = snapshot.coveredFrom(),
            coveredTo = snapshot.coveredTo(),
            lastSuccessfulSyncAt = metadata?.lastSuccessfulSyncAt,
            lastError = metadata?.lastError,
            isSyncing = isSyncing,
        )
    }
}

/** 描述一次日历同步是否更新了本地数据。 */
enum class CalendarSyncResult {
    UPDATED,
    NOT_MODIFIED,
    FAILED,
}
