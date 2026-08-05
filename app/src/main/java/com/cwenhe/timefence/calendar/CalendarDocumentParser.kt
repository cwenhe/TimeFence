package com.cwenhe.timefence.calendar

import java.nio.charset.StandardCharsets
import java.net.URI
import java.time.DateTimeException
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 将外部日历 JSON 解码并校验为可安全写入本地数据库的文档。 */
class CalendarDocumentParser {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = true
    }

    /** 解析文档并拒绝缺失日期、回退版本和违反交易日约束的数据。 */
    fun parse(content: String, minimumRevision: Long = Long.MIN_VALUE): CalendarDocument {
        require(content.toByteArray(StandardCharsets.UTF_8).size <= MAX_DOCUMENT_BYTES) {
            "日历文档超过 ${MAX_DOCUMENT_BYTES / 1024} KiB"
        }
        val document = runCatching {
            val element = json.parseToJsonElement(content)
            validateBooleanPrimitives(element)
            json.decodeFromJsonElement<CalendarDocument>(element)
        }
            .getOrElse { error -> throw IllegalArgumentException("日历文档格式无效", error) }
        require(document.schemaVersion == SUPPORTED_SCHEMA_VERSION) { "不支持的日历文档版本" }
        require(document.revision > 0) { "日历文档 revision 必须为正数" }
        require(document.revision >= minimumRevision) { "日历文档版本回退" }
        require(document.locale == SUPPORTED_LOCALE) { "日历文档语言不受支持" }
        require(document.years.isNotEmpty()) { "日历文档没有完整年份" }
        validateGeneratedAt(document.generatedAt)
        validateYears(document.years)
        return document
    }

    /** 拒绝序列化器可宽松转换的字符串布尔值，保证远程类型与 schema 完全一致。 */
    private fun validateBooleanPrimitives(element: JsonElement) {
        val years = element.jsonObject["years"]?.jsonArray ?: return
        years.forEach { yearElement ->
            val year = yearElement.jsonObject
            year["complete"]?.jsonPrimitive?.let { value ->
                require(!value.isString && value.booleanOrNull != null) { "complete 必须是布尔值" }
            }
            year["days"]?.jsonArray?.forEach { dayElement ->
                val day = dayElement.jsonObject
                listOf("isStatutoryWorkday", "isAShareTradingDay").forEach { key ->
                    day[key]?.jsonPrimitive?.let { value ->
                        require(!value.isString && value.booleanOrNull != null) { "$key 必须是布尔值" }
                    }
                }
            }
        }
    }

    /** 检查年份唯一性、日期连续性、来源字段和工作日交易日关系。 */
    private fun validateYears(years: List<CalendarYearDocument>) {
        require(years.map { it.year }.distinct().size == years.size) { "日历年份重复" }
        require(years.map { it.year } == years.map { it.year }.sorted()) { "日历年份顺序错误" }
        require(years.zipWithNext().all { (previous, next) -> next.year == previous.year + 1 }) {
            "日历年份必须连续"
        }
        years.forEach { year ->
            require(year.complete) { "日历年份未完成：${year.year}" }
            require(year.sources["workday"].orEmpty().isNotEmpty()) { "缺少工作日来源：${year.year}" }
            require(year.sources["tradingDay"].orEmpty().isNotEmpty()) { "缺少交易日来源：${year.year}" }
            year.sources.values.flatten().forEach(::validateSourceUrl)
            val expectedSize = LocalDate.of(year.year, 1, 1).lengthOfYear()
            require(year.days.size == expectedSize) { "年份 ${year.year} 的日期数量错误" }
            year.days.forEachIndexed { index, day ->
                val parsedDate = parseDate(day.date)
                require(parsedDate.year == year.year) { "日期不属于年份 ${year.year}" }
                require(parsedDate == LocalDate.of(year.year, 1, 1).plusDays(index.toLong())) {
                    "年份 ${year.year} 的日期不连续"
                }
                require(!day.isAShareTradingDay || day.isStatutoryWorkday) {
                    "交易日不能不是工作日：${day.date}"
                }
                require(!day.isAShareTradingDay || parsedDate.dayOfWeek.value <= 5) {
                    "交易日不能落在周末：${day.date}"
                }
            }
        }
    }

    /** 将 ISO 日期转换为 LocalDate，并把格式错误统一转换成可读异常。 */
    private fun parseDate(value: String): LocalDate = try {
        LocalDate.parse(value)
    } catch (error: DateTimeException) {
        throw IllegalArgumentException("日历日期无效：$value", error)
    }

    /** 校验生成时间使用带时区的 ISO-8601 格式。 */
    private fun validateGeneratedAt(value: String) {
        try {
            OffsetDateTime.parse(value)
        } catch (error: DateTimeException) {
            throw IllegalArgumentException("日历生成时间无效", error)
        }
    }

    /** 校验公告来源是带主机名的 HTTPS 地址。 */
    private fun validateSourceUrl(value: String) {
        val uri = runCatching { URI(value) }
            .getOrElse { error -> throw IllegalArgumentException("日历来源地址无效", error) }
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) { "日历来源必须使用 HTTPS" }
    }

    private companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        const val SUPPORTED_LOCALE = "zh-CN"
        const val MAX_DOCUMENT_BYTES = 1 * 1024 * 1024
    }
}
