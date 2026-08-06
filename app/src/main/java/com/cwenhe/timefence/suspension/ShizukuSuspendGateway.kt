package com.cwenhe.timefence.suspension

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.cwenhe.timefence.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/** 管理 Shizuku 授权、Binder 生命周期和 UserService 调用。 */
class ShizukuSuspendGateway(context: Context) : PackageSuspendGateway {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)
    private val binding = AtomicBoolean(false)
    private val serviceLock = Any()
    private val mutableStatus = MutableStateFlow(ShizukuGatewayStatus.initial())
    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(appContext, SuspendUserService::class.java),
    )
        .daemon(false)
        .tag(USER_SERVICE_TAG)
        .version(BuildConfig.VERSION_CODE)
        .processNameSuffix(USER_SERVICE_PROCESS_SUFFIX)
        .debuggable(BuildConfig.DEBUG)

    @Volatile
    private var userService: ISuspendUserService? = null

    override val status: StateFlow<ShizukuGatewayStatus> = mutableStatus.asStateFlow()

    /** Binder 到达后重新检查版本和授权，并按需绑定 UserService。 */
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        handleBinderReceived()
    }

    /** Binder 死亡时清空远端代理，但保留上层恢复账本。 */
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        clearService()
        mutableStatus.value = ShizukuGatewayStatus(
            phase = ShizukuConnectionPhase.NOT_RUNNING,
            message = "Shizuku 服务已停止",
        )
    }

    /** 将当前应用的 Shizuku 授权结果转换为连接阶段。 */
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, result ->
        if (requestCode != PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
        if (result == PackageManager.PERMISSION_GRANTED) {
            handleBinderReceived()
        } else {
            clearService()
            mutableStatus.value = ShizukuGatewayStatus(
                phase = ShizukuConnectionPhase.PERMISSION_REQUIRED,
                message = "Shizuku 授权被拒绝",
            )
        }
    }

    /** 接收 UserService Binder，并处理独立进程退出或绑定失败。 */
    private val serviceConnection = object : ServiceConnection {
        /** 保存 AIDL 代理并标记 ADB 或 Root 后端就绪。 */
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            synchronized(serviceLock) {
                userService = ISuspendUserService.Stub.asInterface(service)
            }
            binding.set(false)
            mutableStatus.value = ShizukuGatewayStatus(
                phase = ShizukuConnectionPhase.READY,
                backend = resolveBackend(),
                message = null,
            )
        }

        /** UserService 正常断开时进入可重连错误态。 */
        override fun onServiceDisconnected(name: ComponentName) {
            markServiceFailure("Shizuku UserService 已退出")
        }

        /** UserService 绑定进程死亡时进入可重连错误态。 */
        override fun onBindingDied(name: ComponentName) {
            markServiceFailure("Shizuku UserService 连接已失效")
        }

        /** 远端返回空 Binder 时停止本次绑定并提示重试。 */
        override fun onNullBinding(name: ComponentName) {
            markServiceFailure("Shizuku UserService 未返回连接")
        }
    }

    /** 在应用进程内只注册一次 Shizuku 监听器。 */
    override fun start() {
        if (!started.compareAndSet(false, true)) return
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener, mainHandler)
        Shizuku.addBinderDeadListener(binderDeadListener, mainHandler)
        Shizuku.addRequestPermissionResultListener(permissionResultListener, mainHandler)
        refresh()
    }

    /** 主动重读 Binder 和授权状态，供 Activity 返回前台时调用。 */
    override fun refresh() {
        runCatching {
            if (!Shizuku.pingBinder()) {
                clearService()
                mutableStatus.value = ShizukuGatewayStatus(
                    phase = ShizukuConnectionPhase.NOT_RUNNING,
                    message = "Shizuku 服务未运行",
                )
                return
            }
            handleBinderReceived()
        }.onFailure { error ->
            markServiceFailure("读取 Shizuku 状态失败：${error.javaClass.simpleName}")
        }
    }

    /** 在 Binder 在线且版本受支持时打开 Shizuku 授权对话框。 */
    override fun requestPermission() {
        runCatching {
            if (!Shizuku.pingBinder()) {
                mutableStatus.value = ShizukuGatewayStatus(
                    phase = ShizukuConnectionPhase.NOT_RUNNING,
                    message = "请先启动 Shizuku 服务",
                )
                return
            }
            if (Shizuku.isPreV11()) {
                mutableStatus.value = unsupportedStatus()
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bindUserServiceIfNeeded()
            } else {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            }
        }.onFailure { error ->
            markServiceFailure("请求 Shizuku 授权失败：${error.javaClass.simpleName}")
        }
    }

    /** 在 IO 线程调用远端 AIDL，并把 Binder 异常转换为稳定结果。 */
    override suspend fun setPackageSuspended(
        packageName: String,
        userId: Int,
        suspended: Boolean,
    ): SuspendCommandResult = withContext(Dispatchers.IO) {
        if (!PackageNameValidator.isValid(packageName)) {
            return@withContext SuspendCommandResult.failure("应用包名不合法")
        }
        if (suspended && !PackageNameValidator.canSuspend(packageName)) {
            return@withContext SuspendCommandResult.failure("拒绝暂停系统关键应用")
        }
        if (userId < 0) return@withContext SuspendCommandResult.failure("Android 用户编号不合法")
        val service = synchronized(serviceLock) { userService }
            ?: return@withContext SuspendCommandResult.failure("Shizuku UserService 未连接")
        runCatching {
            val error = service.setPackageSuspended(packageName, userId, suspended)
                .lineSequence()
                .firstOrNull()
                .orEmpty()
                .trim()
                .take(MAX_REMOTE_ERROR_LENGTH)
            if (error.isEmpty()) SuspendCommandResult.success() else SuspendCommandResult.failure(error)
        }.getOrElse { throwable ->
            markServiceFailure("Shizuku 远端调用失败：${throwable.javaClass.simpleName}")
            SuspendCommandResult.failure("Shizuku 连接已中断")
        }
    }

    /** Binder 可用时检查最低版本、授权状态并绑定 UserService。 */
    private fun handleBinderReceived() {
        runCatching {
            if (Shizuku.isPreV11()) {
                clearService()
                mutableStatus.value = unsupportedStatus()
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                clearService()
                mutableStatus.value = ShizukuGatewayStatus(
                    phase = ShizukuConnectionPhase.PERMISSION_REQUIRED,
                    message = "需要授予时界 Shizuku 权限",
                )
                return
            }
            bindUserServiceIfNeeded()
        }.onFailure { error ->
            markServiceFailure("连接 Shizuku 失败：${error.javaClass.simpleName}")
        }
    }

    /** 避免对同一个非 daemon UserService 发起重复绑定。 */
    private fun bindUserServiceIfNeeded() {
        if (synchronized(serviceLock) { userService != null }) return
        if (!binding.compareAndSet(false, true)) return
        mutableStatus.value = ShizukuGatewayStatus(
            phase = ShizukuConnectionPhase.CONNECTING,
            message = "正在连接 Shizuku UserService",
        )
        runCatching {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        }.onFailure { error ->
            binding.set(false)
            throw error
        }
    }

    /** 清空远端代理和绑定中标记。 */
    private fun clearService() {
        synchronized(serviceLock) {
            userService = null
        }
        binding.set(false)
    }

    /** 统一处理 UserService 与远端命令断开状态。 */
    private fun markServiceFailure(message: String) {
        clearService()
        mutableStatus.value = ShizukuGatewayStatus(
            phase = ShizukuConnectionPhase.ERROR,
            message = message,
        )
    }

    /** 根据 Shizuku 进程 UID 区分无线调试和 Root 后端。 */
    private fun resolveBackend(): ShizukuBackend = when (runCatching(Shizuku::getUid).getOrNull()) {
        ROOT_UID -> ShizukuBackend.ROOT
        SHELL_UID -> ShizukuBackend.ADB
        else -> ShizukuBackend.NONE
    }

    private companion object {
        /** 创建旧版 Shizuku 不支持 UserService 时的状态。 */
        fun unsupportedStatus(): ShizukuGatewayStatus = ShizukuGatewayStatus(
            phase = ShizukuConnectionPhase.UNSUPPORTED,
            message = "Shizuku 版本过旧，请升级后重试",
        )

        const val PERMISSION_REQUEST_CODE = 14_017
        const val USER_SERVICE_TAG = "timefence-package-suspend"
        const val USER_SERVICE_PROCESS_SUFFIX = "suspend"
        const val MAX_REMOTE_ERROR_LENGTH = 160
        const val ROOT_UID = 0
        const val SHELL_UID = 2_000
    }
}
