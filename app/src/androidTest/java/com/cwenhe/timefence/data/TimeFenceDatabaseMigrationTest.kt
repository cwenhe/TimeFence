package com.cwenhe.timefence.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cwenhe.timefence.data.local.TimeFenceDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimeFenceDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TimeFenceDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    /** 删除迁移测试数据库，避免不同测试运行之间共享旧状态。 */
    @After
    fun deleteDatabase() {
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(TEST_DATABASE)
    }

    /** 验证 v1 规则升级后字段默认值、应用关联和新日历表都完整保留。 */
    @Test
    fun migrateFrom1To2() {
        helper.createDatabase(TEST_DATABASE, 1).apply {
            execSQL(
                "INSERT INTO rules " +
                    "(id, name, startMinute, endMinute, daysMask, enabled, lockWhileActive) " +
                    "VALUES (7, '旧规则', 480, 600, 1, 1, 1)",
            )
            execSQL("INSERT INTO rule_apps (ruleId, packageName) VALUES (7, 'demo.app')")
            close()
        }

        val database = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            2,
            true,
            TimeFenceDatabase.MIGRATION_1_2,
        )

        database.query(
            "SELECT scheduleMode, notificationMessage, speakNotification FROM rules WHERE id = 7",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("WEEKLY", cursor.getString(0))
            assertEquals("", cursor.getString(1))
            assertEquals(0, cursor.getInt(2))
        }
        database.query("SELECT packageName FROM rule_apps WHERE ruleId = 7").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("demo.app", cursor.getString(0))
        }
        database.query("SELECT COUNT(*) FROM calendar_days").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        database.close()
    }

    private companion object {
        const val TEST_DATABASE = "timefence-migration-test"
    }
}
