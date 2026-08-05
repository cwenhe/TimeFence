package com.cwenhe.timefence.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 提供规则及其应用关联的原子读写操作。 */
@Dao
interface RuleDao {
    /** 按开始时间观察全部规则。 */
    @Transaction
    @Query("SELECT * FROM rules ORDER BY startMinute, id")
    fun observeRules(): Flow<List<RuleWithApps>>

    /** 获取当前全部规则快照。 */
    @Transaction
    @Query("SELECT * FROM rules ORDER BY startMinute, id")
    suspend fun getRules(): List<RuleWithApps>

    /** 插入新规则并返回数据库标识。 */
    @Insert
    suspend fun insertRule(rule: RuleEntity): Long

    /** 更新已存在的规则。 */
    @Update
    suspend fun updateRule(rule: RuleEntity): Int

    /** 删除规则已有的应用关联。 */
    @Query("DELETE FROM rule_apps WHERE ruleId = :ruleId")
    suspend fun deleteApps(ruleId: Long)

    /** 批量插入规则选择的应用。 */
    @Insert
    suspend fun insertApps(apps: List<RuleAppEntity>)

    /** 更新单条规则的启用状态。 */
    @Query("UPDATE rules SET enabled = :enabled WHERE id = :ruleId")
    suspend fun setEnabled(ruleId: Long, enabled: Boolean): Int

    /** 删除规则，外键会同时删除应用关联。 */
    @Query("DELETE FROM rules WHERE id = :ruleId")
    suspend fun deleteRule(ruleId: Long): Int

    /** 在同一事务内保存规则并完整替换它的应用关联。 */
    @Transaction
    suspend fun replaceRule(rule: RuleEntity, packages: List<String>): Long {
        val ruleId = if (rule.id == 0L) {
            insertRule(rule)
        } else {
            require(updateRule(rule) == 1) { "要更新的规则不存在：${rule.id}" }
            rule.id
        }
        deleteApps(ruleId)
        if (packages.isNotEmpty()) {
            insertApps(packages.map { packageName -> RuleAppEntity(ruleId, packageName) })
        }
        return ruleId
    }
}
