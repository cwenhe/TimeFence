package com.cwenhe.timefence.suspension

/** 将 UserService 的进程结果转换为可安全展示的短错误。 */
internal object PmCommandResultFormatter {
    /** 成功时返回空值，失败时仅保留第一行受限长度摘要。 */
    fun errorMessage(exitCode: Int?, timedOut: Boolean, output: String): String? {
        if (timedOut) return "系统暂停命令执行超时"
        if (exitCode == 0) return null
        val summary = output
            .lineSequence()
            .map(String::trim)
            .firstOrNull(String::isNotEmpty)
            ?.take(MAX_ERROR_LENGTH)
        val prefix = "系统暂停命令失败（退出码 ${exitCode ?: "未知"}）"
        return if (summary == null) prefix else "$prefix：$summary"
    }

    private const val MAX_ERROR_LENGTH = 120
}
