package com.cwenhe.timefence.suspension

import android.content.Context
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** 在 Shizuku 的 shell/root 进程中执行受限的包暂停命令。 */
@Keep
class SuspendUserService() : ISuspendUserService.Stub() {
    /** 保留 API 13 的 Context 构造路径，但不在独立进程使用该 Context。 */
    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    /** 校验单包请求并返回空字符串表示成功，否则返回可展示的短错误。 */
    override fun setPackageSuspended(
        packageName: String,
        userId: Int,
        suspended: Boolean,
    ): String {
        if (!PackageNameValidator.isValid(packageName)) return "应用包名不合法"
        if (userId < 0) return "Android 用户编号不合法"
        if (suspended && !PackageNameValidator.canSuspend(packageName)) return "拒绝暂停系统关键应用"
        return runPmCommand(packageName, userId, suspended).orEmpty()
    }

    /** 响应 Shizuku 的 UserService 销毁协议并终止独立进程。 */
    override fun destroy() {
        exitProcess(0)
    }

    /** 使用参数数组执行 pm，避免把包名交给 shell 解释器。 */
    private fun runPmCommand(
        packageName: String,
        userId: Int,
        suspended: Boolean,
    ): String? = runCatching {
        val operation = if (suspended) "suspend" else "unsuspend"
        val process = ProcessBuilder(
            PM_BINARY,
            operation,
            "--user",
            userId.toString(),
            packageName,
        )
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(FORCED_STOP_WAIT_SECONDS, TimeUnit.SECONDS)
        }
        val output = runCatching {
            process.inputStream.bufferedReader().use { reader -> reader.readText() }
        }.getOrDefault("")
        PmCommandResultFormatter.errorMessage(
            exitCode = if (finished) process.exitValue() else null,
            timedOut = !finished,
            output = output,
        )
    }.getOrElse { error ->
        "系统暂停命令异常：${error.javaClass.simpleName}"
    }

    private companion object {
        const val PM_BINARY = "/system/bin/pm"
        const val COMMAND_TIMEOUT_SECONDS = 10L
        const val FORCED_STOP_WAIT_SECONDS = 1L
    }
}
