package com.cwenhe.timefence.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cwenhe.timefence.data.local.TimeFenceDatabase
import com.cwenhe.timefence.rules.ScheduleRule
import java.time.DayOfWeek
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 在 Android Room 实现上验证规则和应用关联的原子持久化。 */
@RunWith(AndroidJUnit4::class)
class ScheduleRepositoryInstrumentedTest {
    private lateinit var database: TimeFenceDatabase
    private lateinit var repository: ScheduleRepository

    /** 为每个测试创建相互隔离的内存数据库。 */
    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TimeFenceDatabase::class.java,
        ).build()
        repository = ScheduleRepository(database.ruleDao())
    }

    /** 测试结束后关闭数据库线程和连接。 */
    @After
    fun closeDatabase() {
        database.close()
    }

    /** 保存规则后必须能恢复数据库标识、星期和全部应用集合。 */
    @Test
    fun saveAndReadRule() = runBlocking {
        val source = sampleRule()

        val id = repository.saveRule(source)
        val restored = repository.getRules().single()

        assertEquals(id, restored.id)
        assertEquals(source.copy(id = id), restored)
    }

    /** 更新规则时完整替换应用集合，并验证启停与级联删除。 */
    @Test
    fun updateToggleAndDeleteRule() = runBlocking {
        val id = repository.saveRule(sampleRule())
        val updated = sampleRule().copy(
            id = id,
            name = "专注时间",
            packages = setOf("reader.app"),
        )

        repository.saveRule(updated)
        assertEquals(updated, repository.getRules().single())

        repository.setEnabled(id, false)
        assertEquals(false, repository.getRules().single().enabled)

        repository.deleteRule(id)
        assertEquals(emptyList<ScheduleRule>(), repository.getRules())
    }

    /** 构造用于 Room 增删改查验证的固定规则。 */
    private fun sampleRule(): ScheduleRule = ScheduleRule(
        id = 0,
        name = "午休",
        startMinute = 12 * 60,
        endMinute = 13 * 60,
        days = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
        packages = setOf("video.app", "chat.app"),
        enabled = true,
        lockWhileActive = false,
    )
}
