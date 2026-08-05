package com.cwenhe.timefence.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 时界本地数据库，保存用户规则和已校验的公开日历缓存。 */
@Database(
    entities = [
        RuleEntity::class,
        RuleAppEntity::class,
        CalendarDayEntity::class,
        CalendarMetadataEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class TimeFenceDatabase : RoomDatabase() {
    /** 返回规则数据访问接口。 */
    abstract fun ruleDao(): RuleDao

    /** 返回日历数据访问接口。 */
    abstract fun calendarDao(): CalendarDao

    companion object {
        /** 从 v1 增加规则提示字段和本地日历表，保留已有规则与应用关联。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            /** 执行不丢数据的列追加与日历表创建。 */
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE rules ADD COLUMN scheduleMode TEXT NOT NULL DEFAULT 'WEEKLY'",
                )
                database.execSQL(
                    "ALTER TABLE rules ADD COLUMN notificationMessage TEXT NOT NULL DEFAULT ''",
                )
                database.execSQL(
                    "ALTER TABLE rules ADD COLUMN speakNotification INTEGER NOT NULL DEFAULT 0",
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS calendar_days (" +
                        "date TEXT NOT NULL, " +
                        "isStatutoryWorkday INTEGER NOT NULL, " +
                        "isAShareTradingDay INTEGER NOT NULL, " +
                        "sourceVersion INTEGER NOT NULL, " +
                        "updatedAtEpochMillis INTEGER NOT NULL, " +
                        "PRIMARY KEY(date))",
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS calendar_metadata (" +
                        "id INTEGER NOT NULL, " +
                        "revision INTEGER NOT NULL, " +
                        "locale TEXT NOT NULL, " +
                        "coveredFrom TEXT NOT NULL, " +
                        "coveredTo TEXT NOT NULL, " +
                        "etag TEXT, " +
                        "lastSuccessfulSyncAt INTEGER, " +
                        "lastAttemptAt INTEGER, " +
                        "lastError TEXT, " +
                        "PRIMARY KEY(id))",
                )
            }
        }

        /** 创建进程内复用的数据库实例。 */
        fun create(context: Context): TimeFenceDatabase = Room.databaseBuilder(
            context.applicationContext,
            TimeFenceDatabase::class.java,
            "timefence.db",
        ).addMigrations(MIGRATION_1_2).build()
    }
}
