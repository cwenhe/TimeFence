package com.cwenhe.timefence.data

import com.cwenhe.timefence.data.local.RuleAppEntity
import com.cwenhe.timefence.data.local.RuleDao
import com.cwenhe.timefence.data.local.RuleEntity
import com.cwenhe.timefence.data.local.RuleWithApps
import com.cwenhe.timefence.rules.ScheduleRule
import java.time.DayOfWeek
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 提供规则领域模型的本地持久化入口。 */
class ScheduleRepository(private val ruleDao: RuleDao) {
    /** 持续观察全部规则，数据库细节不会泄漏到调用方。 */
    fun observeRules(): Flow<List<ScheduleRule>> = ruleDao.observeRules().map { storedRules ->
        storedRules.map(RuleWithApps::toDomainRule)
    }

    /** 读取当前全部规则快照。 */
    suspend fun getRules(): List<ScheduleRule> = ruleDao.getRules().map(RuleWithApps::toDomainRule)

    /** 校验并保存规则，返回数据库中的稳定标识。 */
    suspend fun saveRule(rule: ScheduleRule): Long {
        require(rule.name.isNotBlank()) { "规则名称不能为空" }
        require(rule.startMinute != rule.endMinute) { "开始时间不能等于结束时间" }
        require(rule.days.isNotEmpty()) { "至少选择一天" }
        require(rule.packages.isNotEmpty()) { "至少选择一个应用" }
        require(rule.packages.all { it.isNotBlank() }) { "应用包名不能为空" }
        val stored = rule.copy(name = rule.name.trim()).toStoredRule()
        return ruleDao.replaceRule(stored.entity, stored.packages)
    }

    /** 启用或停用一条已存在规则。 */
    suspend fun setEnabled(ruleId: Long, enabled: Boolean) {
        require(ruleDao.setEnabled(ruleId, enabled) == 1) { "要修改的规则不存在：$ruleId" }
    }

    /** 删除一条已存在规则。 */
    suspend fun deleteRule(ruleId: Long) {
        require(ruleDao.deleteRule(ruleId) == 1) { "要删除的规则不存在：$ruleId" }
    }
}

/** 领域规则准备写入数据库时使用的稳定结构。 */
internal data class StoredRule(
    val entity: RuleEntity,
    val packages: List<String>,
)

/** 将领域规则转换为数据库实体和排序后的包名集合。 */
internal fun ScheduleRule.toStoredRule(): StoredRule = StoredRule(
    entity = RuleEntity(
        id = id,
        name = name,
        startMinute = startMinute,
        endMinute = endMinute,
        daysMask = days.fold(0) { mask, day -> mask or (1 shl (day.value - 1)) },
        enabled = enabled,
        lockWhileActive = lockWhileActive,
    ),
    packages = packages.map(String::trim).distinct().sorted(),
)

/** 将 Room 关系对象恢复为规则领域模型。 */
internal fun RuleWithApps.toDomainRule(): ScheduleRule = ScheduleRule(
    id = rule.id,
    name = rule.name,
    startMinute = rule.startMinute,
    endMinute = rule.endMinute,
    days = DayOfWeek.entries.filterTo(linkedSetOf()) { day ->
        rule.daysMask and (1 shl (day.value - 1)) != 0
    },
    packages = apps.mapTo(linkedSetOf(), RuleAppEntity::packageName),
    enabled = rule.enabled,
    lockWhileActive = rule.lockWhileActive,
)
