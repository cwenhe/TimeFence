package com.cwenhe.timefence.calendar

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarDocumentParserTest {
    private val parser = CalendarDocumentParser()

    /** 验证内置 2026 数据包含完整日期和官方计数。 */
    @Test
    fun `内置日历包含完整的2026年度数据`() {
        val document = parser.parse(readCalendarAsset())
        val year = document.years.single()

        assertEquals(2026, year.year)
        assertEquals(365, year.days.size)
        assertEquals(248, year.days.count { it.isStatutoryWorkday })
        assertEquals(242, year.days.count { it.isAShareTradingDay })
        assertEquals("2026-01-01", year.days.first().date)
        assertEquals("2026-12-31", year.days.last().date)
    }

    /** 验证调休周末可作为工作日但不会被误判为 A 股交易日。 */
    @Test
    fun `调休周末只属于工作日`() {
        val days = parser.parse(readCalendarAsset()).years.single().days.associateBy { it.date }

        assertTrue(days.getValue("2026-01-04").isStatutoryWorkday)
        assertTrue(!days.getValue("2026-01-04").isAShareTradingDay)
        assertTrue(days.getValue("2026-01-05").isAShareTradingDay)
        assertTrue(!days.getValue("2026-01-01").isStatutoryWorkday)
    }

    /** 验证缺失日期时解析器拒绝不完整的年度数据。 */
    @Test(expected = IllegalArgumentException::class)
    fun `缺失日期会被拒绝`() {
        val content = readCalendarAsset().replace("2026-01-02", "2026-01-01")
        parser.parse(content)
    }

    /** 验证旧 revision 不能覆盖当前已缓存的数据集。 */
    @Test(expected = IllegalArgumentException::class)
    fun `回退版本会被拒绝`() {
        parser.parse(readCalendarAsset(), minimumRevision = 2026080602)
    }

    /** 验证远程更新文件和 APK 内置兜底始终保持字节一致。 */
    @Test
    fun `远程与内置日历保持一致`() {
        val candidates = listOf(
            Path.of("../data/calendar/zh-CN.json"),
            Path.of("data/calendar/zh-CN.json"),
        )
        val path = candidates.firstOrNull(Files::exists)
            ?: error("找不到远程日历文件")

        assertEquals(readCalendarAsset(), Files.newBufferedReader(path).use { reader -> reader.readText() })
    }

    /** 验证远程文档不能跳过尚未发布的中间年份。 */
    @Test(expected = IllegalArgumentException::class)
    fun `年份存在缺口会被拒绝`() {
        val base = parser.parse(readCalendarAsset())
        val document = base.copy(years = listOf(base.years.single(), completeYear(2028)))

        parser.parse(Json.encodeToString(document))
    }

    /** 验证未知 schema 版本不会被客户端静默接受。 */
    @Test(expected = IllegalArgumentException::class)
    fun `未知文档版本会被拒绝`() {
        parser.parse(readCalendarAsset().replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": 2"))
    }

    /** 验证非中国日历不能覆盖当前本地缓存。 */
    @Test(expected = IllegalArgumentException::class)
    fun `错误语言会被拒绝`() {
        parser.parse(readCalendarAsset().replaceFirst("\"locale\": \"zh-CN\"", "\"locale\": \"en-US\""))
    }

    /** 验证交易日必须同时是法定工作日。 */
    @Test(expected = IllegalArgumentException::class)
    fun `非工作日交易状态会被拒绝`() {
        parser.parse(readCalendarAsset().replaceFirst("\"isAShareTradingDay\": false", "\"isAShareTradingDay\": true"))
    }

    /** 验证超过一 MiB 的响应在 JSON 解码前就被拒绝。 */
    @Test(expected = IllegalArgumentException::class)
    fun `超大文档会被拒绝`() {
        parser.parse(" ".repeat(1024 * 1024 + 1))
    }

    /** 验证布尔字段不能使用字符串等宽松类型。 */
    @Test(expected = IllegalArgumentException::class)
    fun `非法布尔字段会被拒绝`() {
        parser.parse(
            readCalendarAsset().replaceFirst(
                "\"isStatutoryWorkday\": false",
                "\"isStatutoryWorkday\": \"false\"",
            ),
        )
    }

    /** 读取模块资源文件，兼容 Gradle 从仓库根目录或 app 目录运行测试。 */
    private fun readCalendarAsset(): String {
        val candidates = listOf(
            Path.of("src/main/res/raw/zh_cn_calendar.json"),
            Path.of("app/src/main/res/raw/zh_cn_calendar.json"),
        )
        val path = candidates.firstOrNull(Files::exists)
            ?: error("找不到内置日历资源")
        return Files.newBufferedReader(path).use { reader -> reader.readText() }
    }

    /** 构造日期连续且来源完整的自然年，用于隔离测试年份序列规则。 */
    private fun completeYear(year: Int): CalendarYearDocument {
        val first = LocalDate.of(year, 1, 1)
        return CalendarYearDocument(
            year = year,
            complete = true,
            sources = mapOf(
                "workday" to listOf("https://example.com/workday"),
                "tradingDay" to listOf("https://example.com/trading"),
            ),
            days = (0 until first.lengthOfYear()).map { offset ->
                CalendarDayDocument(
                    date = first.plusDays(offset.toLong()).toString(),
                    isStatutoryWorkday = false,
                    isAShareTradingDay = false,
                )
            },
        )
    }
}
