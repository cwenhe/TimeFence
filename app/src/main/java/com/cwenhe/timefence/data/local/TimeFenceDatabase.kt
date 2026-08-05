package com.cwenhe.timefence.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** 时界本地数据库，仅保存用户配置的规则。 */
@Database(
    entities = [RuleEntity::class, RuleAppEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class TimeFenceDatabase : RoomDatabase() {
    /** 返回规则数据访问接口。 */
    abstract fun ruleDao(): RuleDao

    companion object {
        /** 创建进程内复用的数据库实例。 */
        fun create(context: Context): TimeFenceDatabase = Room.databaseBuilder(
            context.applicationContext,
            TimeFenceDatabase::class.java,
            "timefence.db",
        ).build()
    }
}
