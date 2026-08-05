package com.cwenhe.timefence.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/** 保存一条规则的时间、星期和开关状态。 */
@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startMinute: Int,
    val endMinute: Int,
    val daysMask: Int,
    val enabled: Boolean,
    val lockWhileActive: Boolean,
)

/** 保存规则与受限应用包名的多对多关联。 */
@Entity(
    tableName = "rule_apps",
    primaryKeys = ["ruleId", "packageName"],
    foreignKeys = [
        ForeignKey(
            entity = RuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["ruleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("ruleId")],
)
data class RuleAppEntity(
    val ruleId: Long,
    val packageName: String,
)

/** 将规则实体与它选择的应用一次性加载。 */
data class RuleWithApps(
    @Embedded val rule: RuleEntity,
    @Relation(parentColumn = "id", entityColumn = "ruleId")
    val apps: List<RuleAppEntity>,
)
