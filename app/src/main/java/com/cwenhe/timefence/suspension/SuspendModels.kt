package com.cwenhe.timefence.suspension

import kotlinx.coroutines.flow.StateFlow

/** Shizuku 从不可用到 UserService 就绪的连接阶段。 */
enum class ShizukuConnectionPhase {
    NOT_RUNNING,
    UNSUPPORTED,
    PERMISSION_REQUIRED,
    CONNECTING,
    READY,
    ERROR,
}

/** UserService 当前获得的系统身份。 */
enum class ShizukuBackend {
    NONE,
    ADB,
    ROOT,
}

/** Shizuku 网关对应用其余模块暴露的稳定状态。 */
data class ShizukuGatewayStatus(
    val phase: ShizukuConnectionPhase,
    val backend: ShizukuBackend = ShizukuBackend.NONE,
    val message: String? = null,
) {
    val isReady: Boolean
        get() = phase == ShizukuConnectionPhase.READY

    companion object {
        /** 创建应用启动且尚未收到 Shizuku Binder 时的初始状态。 */
        fun initial(): ShizukuGatewayStatus = ShizukuGatewayStatus(
            phase = ShizukuConnectionPhase.NOT_RUNNING,
            message = "Shizuku 服务未运行",
        )
    }
}

/** 单次远端暂停或恢复命令的受控结果。 */
data class SuspendCommandResult(
    val success: Boolean,
    val errorMessage: String? = null,
) {
    companion object {
        /** 创建成功结果。 */
        fun success(): SuspendCommandResult = SuspendCommandResult(success = true)

        /** 创建不包含远端原始敏感文本的失败结果。 */
        fun failure(message: String): SuspendCommandResult = SuspendCommandResult(
            success = false,
            errorMessage = message,
        )
    }
}

/** 主进程与 Shizuku UserService 之间的可替换边界。 */
interface PackageSuspendGateway {
    val status: StateFlow<ShizukuGatewayStatus>

    /** 注册进程级 Binder 和权限监听器。 */
    fun start()

    /** 从系统重新读取 Binder、权限和 UserService 连接状态。 */
    fun refresh()

    /** 在 Binder 在线时请求 Shizuku 授权。 */
    fun requestPermission()

    /** 对当前 Android 用户中的单个合法包执行暂停或恢复。 */
    suspend fun setPackageSuspended(
        packageName: String,
        userId: Int,
        suspended: Boolean,
    ): SuspendCommandResult
}

/** 设置页展示的系统暂停模式整体状态。 */
data class SystemSuspendStatus(
    val modeEnabled: Boolean,
    val releasePending: Boolean,
    val managedPackages: Set<String>,
    val gateway: ShizukuGatewayStatus,
    val busy: Boolean,
    val lastError: String?,
) {
    val managedCount: Int
        get() = managedPackages.size
}

/** 用户触发的模式切换或紧急恢复结果。 */
data class SuspendActionResult(
    val success: Boolean,
    val message: String? = null,
)
