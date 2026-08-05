package com.cwenhe.timefence.calendar

import com.cwenhe.timefence.data.local.CalendarDao
import com.cwenhe.timefence.data.local.CalendarDayEntity
import com.cwenhe.timefence.data.local.CalendarMetadataEntity
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 验证日历同步在取消、覆盖变化和 304 场景下保留一致的本地快照。 */
class CalendarRepositoryTest {
    /** 验证初始化协程取消会继续向上游传播，而不是被转换成业务失败。 */
    @Test
    fun `初始化取消会向调用方传播`() = runBlocking {
        val cancellation = CancellationException("测试取消")
        val dao = FakeCalendarDao(observeFailure = cancellation)
        val repository = repository(dao) { _, _ -> CalendarFetchResult.NotModified }

        try {
            repository.syncNow()
            fail("预期抛出 CancellationException")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
    }

    /** 验证更高 revision 不能删除当前仍缓存的完整年份。 */
    @Test
    fun `远程文档缩小覆盖范围时保留旧数据`() = runBlocking {
        val originalDays = listOf(day("2026-01-01"), day("2026-12-31"))
        val dao = FakeCalendarDao(
            initialDays = originalDays,
            initialMetadata = metadata(revision = 1, from = "2026-01-01", to = "2026-12-31"),
        )
        val repository = repository(dao) { _, _ ->
            CalendarFetchResult.Updated(documentForYear(2027, revision = 2), etag = "new")
        }

        assertEquals(CalendarSyncResult.FAILED, repository.syncNow())

        assertEquals(originalDays, dao.days.value)
        assertEquals(LocalDate.parse("2026-01-01"), repository.snapshot.value.coveredFrom())
        assertEquals(LocalDate.parse("2026-12-31"), repository.snapshot.value.coveredTo())
    }

    /** 验证 304 只刷新同步时间，不替换已经校验的逐日数据。 */
    @Test
    fun `远程未修改时保留数据并记录成功`() = runBlocking {
        val originalDays = listOf(day("2026-01-01"), day("2026-12-31"))
        val dao = FakeCalendarDao(
            initialDays = originalDays,
            initialMetadata = metadata(revision = 1, from = "2026-01-01", to = "2026-12-31"),
        )
        val repository = repository(dao) { _, _ -> CalendarFetchResult.NotModified }

        assertEquals(CalendarSyncResult.NOT_MODIFIED, repository.syncNow())

        assertEquals(originalDays, dao.days.value)
        assertTrue(requireNotNull(dao.metadata).lastSuccessfulSyncAt != null)
    }

    /** 验证界面健康状态只在目标日期落入完整覆盖范围时成立。 */
    @Test
    fun `同步状态可以判断指定日期是否被覆盖`() {
        val status = CalendarStatus(
            revision = 1,
            coveredFrom = LocalDate.parse("2026-01-01"),
            coveredTo = LocalDate.parse("2026-12-31"),
            lastSuccessfulSyncAt = null,
            lastError = null,
            isSyncing = false,
        )

        assertTrue(status.covers(LocalDate.parse("2026-08-06")))
        assertTrue(!status.covers(LocalDate.parse("2027-01-01")))
    }

    /** 创建注入假网络函数和不会被使用的内置加载器的仓库。 */
    private fun repository(
        dao: CalendarDao,
        fetch: suspend (String?, Long) -> CalendarFetchResult,
    ): CalendarRepository = CalendarRepository(
        calendarDao = dao,
        remoteFetch = fetch,
        builtinLoader = { error("已有缓存时不应读取内置文件") },
    )

    /** 构造固定布尔值的最小 Room 日期行。 */
    private fun day(value: String): CalendarDayEntity = CalendarDayEntity(
        date = value,
        isStatutoryWorkday = true,
        isAShareTradingDay = true,
        sourceVersion = 1,
        updatedAtEpochMillis = 0,
    )

    /** 构造测试同步所需的单例元数据。 */
    private fun metadata(
        revision: Long,
        from: String,
        to: String,
    ): CalendarMetadataEntity = CalendarMetadataEntity(
        revision = revision,
        locale = "zh-CN",
        coveredFrom = from,
        coveredTo = to,
        etag = "old",
        lastSuccessfulSyncAt = null,
        lastAttemptAt = null,
        lastError = null,
    )

    /** 生成一个完整自然年的远程文档，模拟已经通过解析器校验的响应。 */
    private fun documentForYear(year: Int, revision: Long): CalendarDocument {
        val first = LocalDate.of(year, 1, 1)
        val days = (0 until first.lengthOfYear()).map { offset ->
            CalendarDayDocument(
                date = first.plusDays(offset.toLong()).toString(),
                isStatutoryWorkday = true,
                isAShareTradingDay = false,
            )
        }
        return CalendarDocument(
            schemaVersion = 1,
            revision = revision,
            locale = "zh-CN",
            generatedAt = "$year-01-01T00:00:00Z",
            years = listOf(
                CalendarYearDocument(
                    year = year,
                    complete = true,
                    sources = mapOf(
                        "workday" to listOf("https://example.com/workday"),
                        "tradingDay" to listOf("https://example.com/trading"),
                    ),
                    days = days,
                ),
            ),
        )
    }

    /** 用内存 Flow 模拟 Room DAO，并保留事务替换后的可观察状态。 */
    private class FakeCalendarDao(
        initialDays: List<CalendarDayEntity> = emptyList(),
        initialMetadata: CalendarMetadataEntity? = null,
        private val observeFailure: Throwable? = null,
    ) : CalendarDao {
        val days = MutableStateFlow(initialDays)
        var metadata = initialMetadata

        /** 返回日期 Flow；指定异常时在首次收集阶段抛出。 */
        override fun observeDays(): Flow<List<CalendarDayEntity>> = observeFailure?.let { error ->
            flow { throw error }
        } ?: days

        /** 返回当前内存元数据。 */
        override suspend fun getMetadata(): CalendarMetadataEntity? = metadata

        /** 替换当前内存元数据。 */
        override suspend fun updateMetadata(metadata: CalendarMetadataEntity) {
            this.metadata = metadata
        }

        /** 清空当前内存日期。 */
        override suspend fun deleteDays() {
            days.value = emptyList()
        }

        /** 写入当前内存日期。 */
        override suspend fun insertDays(days: List<CalendarDayEntity>) {
            this.days.value = days
        }

        /** 写入替换事务的内存元数据。 */
        override suspend fun insertMetadata(metadata: CalendarMetadataEntity) {
            this.metadata = metadata
        }
    }
}
