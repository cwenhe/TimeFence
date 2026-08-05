package com.cwenhe.timefence.calendar

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 表示一次日历请求的结果，区分内容更新和服务器 304。 */
sealed interface CalendarFetchResult {
    data class Updated(val document: CalendarDocument, val etag: String?) : CalendarFetchResult

    data object NotModified : CalendarFetchResult
}

/** 通过固定 HTTPS raw 文件更新日历，不把规则数据发送到网络。 */
class CalendarRemoteDataSource(
    private val parser: CalendarDocumentParser = CalendarDocumentParser(),
) {
    /** 请求远程文档并按 ETag 和本地 revision 进行条件更新。 */
    suspend fun fetch(etag: String?, minimumRevision: Long): CalendarFetchResult = withContext(Dispatchers.IO) {
        val connection = (URL(CALENDAR_URL).openConnection() as? HttpsURLConnection)
            ?: error("日历地址不是 HTTPS")
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.connectTimeout = REQUEST_TIMEOUT_MILLIS
            connection.readTimeout = REQUEST_TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "text/plain, application/json")
            if (!etag.isNullOrBlank()) connection.setRequestProperty("If-None-Match", etag)
            when (val responseCode = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> CalendarFetchResult.NotModified
                HttpURLConnection.HTTP_OK -> {
                    val content = connection.inputStream.use(::readLimitedUtf8)
                    CalendarFetchResult.Updated(parser.parse(content, minimumRevision), connection.getHeaderField("ETag"))
                }

                else -> error("日历服务器返回 HTTP $responseCode")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** 从响应流读取有限大小的 UTF-8 文档，防止异常响应耗尽进程内存。 */
    private fun readLimitedUtf8(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var totalBytes = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            totalBytes += read
            require(totalBytes <= MAX_DOCUMENT_BYTES) { "日历文档超过 ${MAX_DOCUMENT_BYTES / 1024} KiB" }
            output.write(buffer, 0, read)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private companion object {
        const val CALENDAR_URL = "https://raw.githubusercontent.com/cwenhe/TimeFence/main/data/calendar/zh-CN.json"
        const val REQUEST_TIMEOUT_MILLIS = 10_000
        const val MAX_DOCUMENT_BYTES = 1 * 1024 * 1024
        const val BUFFER_SIZE = 8 * 1024
    }
}
