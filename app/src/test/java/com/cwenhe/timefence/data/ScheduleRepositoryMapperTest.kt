package com.cwenhe.timefence.data

import com.cwenhe.timefence.data.local.RuleAppEntity
import com.cwenhe.timefence.data.local.RuleEntity
import com.cwenhe.timefence.data.local.RuleWithApps
import com.cwenhe.timefence.rules.ScheduleRule
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Test

/** 验证 Room 数据与规则领域模型之间的无损转换。 */
class ScheduleRepositoryMapperTest {
    /** 星期位、应用集合和所有规则开关必须完整写入数据库实体。 */
    @Test
    fun `领域规则转换为数据库实体`() {
        val rule = sampleRule()

        val stored = rule.toStoredRule()

        assertEquals(65, stored.entity.daysMask)
        assertEquals(listOf("mail.app", "video.app"), stored.packages)
        assertEquals(rule.id, stored.entity.id)
        assertEquals(rule.lockWhileActive, stored.entity.lockWhileActive)
    }

    /** 数据库关系转换后必须恢复同一份领域规则。 */
    @Test
    fun `数据库关系转换为领域规则`() {
        val original = sampleRule()
        val stored = original.toStoredRule()
        val relation = RuleWithApps(
            rule = stored.entity,
            apps = stored.packages.map { RuleAppEntity(original.id, it) },
        )

        assertEquals(original, relation.toDomainRule())
    }

    /** 写入数据库前会清理包名首尾空格、去重并生成稳定顺序。 */
    @Test
    fun `应用包名在持久化前规范化`() {
        val stored = sampleRule().copy(
            packages = setOf(" video.app ", "mail.app", "video.app"),
        ).toStoredRule()

        assertEquals(listOf("mail.app", "video.app"), stored.packages)
    }

    /** 构造同时跨越星期一和星期日的固定测试规则。 */
    private fun sampleRule(): ScheduleRule = ScheduleRule(
        id = 42,
        name = "夜间休息",
        startMinute = 22 * 60,
        endMinute = 7 * 60,
        days = setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY),
        packages = setOf("video.app", "mail.app"),
        enabled = true,
        lockWhileActive = true,
    )
}
