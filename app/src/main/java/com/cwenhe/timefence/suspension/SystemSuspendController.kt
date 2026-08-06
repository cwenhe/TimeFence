package com.cwenhe.timefence.suspension

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 串行协调规则目标、实际暂停状态和本地恢复责任。 */
class SystemSuspendController(
    private val scope: CoroutineScope,
    private val gateway: PackageSuspendGateway,
    private val store: SuspendStateStore,
    private val inspector: PackageSuspensionInspector,
    private val protectedPackages: () -> Set<String>,
    private val desiredPackages: () -> Set<String>?,
    private val userId: Int,
    private val operationDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val started = AtomicBoolean(false)
    private val operationMutex = Mutex()
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val operationState = MutableStateFlow(OperationState())

    val status = combine(store.state, gateway.status, operationState) { settings, gatewayStatus, operation ->
        SystemSuspendStatus(
            modeEnabled = settings.modeEnabled,
            releasePending = settings.releasePending,
            managedPackages = settings.managedPackages,
            gateway = gatewayStatus,
            busy = operation.busy,
            lastError = operation.lastError,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = SystemSuspendStatus(
            modeEnabled = store.state.value.modeEnabled,
            releasePending = store.state.value.releasePending,
            managedPackages = store.state.value.managedPackages,
            gateway = gateway.status.value,
            busy = false,
            lastError = null,
        ),
    )

    init {
        scope.launch {
            for (ignored in signals) reconcileNow()
        }
    }

    /** 注册 Shizuku 生命周期监听，并在连接阶段变化时触发一次合并校正。 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        gateway.start()
        scope.launch {
            gateway.status
                .map { current -> current.phase to current.backend }
                .distinctUntilChanged()
                .collect { requestReconcile() }
        }
        requestReconcile()
    }

    /** 刷新 Shizuku 状态，并合并请求一次暂停校正。 */
    fun refreshShizuku() {
        gateway.refresh()
        requestReconcile()
    }

    /** 将授权动作转交给进程级 Shizuku 网关。 */
    fun requestShizukuPermission() {
        gateway.requestPermission()
    }

    /** 将多来源校正信号压缩为最多一个待处理任务。 */
    fun requestReconcile(): Boolean = signals.trySend(Unit).isSuccess

    /** 开启高级模式，或在关闭时进入持久化的只恢复状态。 */
    suspend fun setModeEnabled(enabled: Boolean): SuspendActionResult = withContext(operationDispatcher) {
        if (!enabled) return@withContext releaseAll()
        if (!gateway.status.value.isReady) {
            return@withContext recordFailure(gateway.status.value.message ?: "Shizuku 尚未就绪")
        }
        if (!store.enableMode()) return@withContext recordFailure("无法保存系统暂停模式")
        reconcileNow()
    }

    /** 持久化紧急解除请求，并尝试恢复全部由时界管理的包。 */
    suspend fun releaseAll(): SuspendActionResult = withContext(operationDispatcher) {
        if (!store.beginRelease()) return@withContext recordFailure("无法保存解除全部暂停请求")
        reconcileNow()
    }

    /** 立即执行一次完整校正；所有调用通过同一互斥锁串行化。 */
    suspend fun reconcileNow(): SuspendActionResult = reconcileWithDesiredOverride(null)

    /** 使用广播已读取的规则目标立即校正，避免等待进程内规则 Flow 初始化。 */
    suspend fun reconcileDesiredPackages(packages: Set<String>): SuspendActionResult =
        reconcileWithDesiredOverride(packages)

    /** 在统一互斥锁内执行提供者目标或显式目标校正。 */
    private suspend fun reconcileWithDesiredOverride(
        desiredOverride: Set<String>?,
    ): SuspendActionResult = withContext(operationDispatcher) {
        operationMutex.withLock {
            operationState.value = OperationState(busy = true)
            val result = runCatching { reconcileLocked(desiredOverride) }
                .getOrElse { error -> failure("系统暂停校正失败：${error.javaClass.simpleName}") }
            operationState.value = OperationState(
                busy = false,
                lastError = result.message.takeUnless { result.success },
            )
            result
        }
    }

    /** 根据持久模式选择只恢复流程或正常目标差集校正。 */
    private suspend fun reconcileLocked(desiredOverride: Set<String>?): SuspendActionResult {
        val settings = store.state.value
        if (settings.releasePending) return releaseManagedPackages(completeModeRelease = true)
        if (!settings.modeEnabled) return success()
        if (!gateway.status.value.isReady) {
            return failure(gateway.status.value.message ?: "Shizuku 尚未就绪")
        }

        val candidates = runCatching { desiredOverride ?: desiredPackages() }
            .getOrElse { error ->
                return failure("读取系统暂停目标失败：${error.javaClass.simpleName}")
            }
            ?: return success()
        val desired = runCatching {
            ProtectedPackagePolicy.filterAllowed(
                candidates = candidates,
                protectedPackages = protectedPackages(),
            )
        }.getOrElse { error ->
            return failure("读取系统暂停目标失败：${error.javaClass.simpleName}")
        }
        val failures = mutableListOf<String>()
        val expired = store.state.value.managedPackages - desired
        expired.sorted().forEach { packageName ->
            releaseManagedPackage(packageName)?.let(failures::add)
        }
        desired.sorted().forEach { packageName ->
            ensureDesiredPackageSuspended(packageName)?.let(failures::add)
        }
        return failures.toResult()
    }

    /** 只恢复管理集合；集合清空后才原子关闭模式。 */
    private suspend fun releaseManagedPackages(completeModeRelease: Boolean): SuspendActionResult {
        val managed = store.state.value.managedPackages
        if (managed.isEmpty()) {
            if (completeModeRelease && !store.completeRelease()) {
                return failure("无法完成系统暂停模式关闭")
            }
            return success()
        }
        if (!gateway.status.value.isReady) {
            return failure(gateway.status.value.message ?: "启动 Shizuku 后才能解除暂停")
        }
        val failures = mutableListOf<String>()
        managed.sorted().forEach { packageName ->
            releaseManagedPackage(packageName)?.let(failures::add)
        }
        if (failures.isEmpty() && completeModeRelease) {
            if (!store.completeRelease()) failures += "无法完成系统暂停模式关闭"
        }
        return failures.toResult()
    }

    /** 恢复一个管理包；未安装或已经恢复时只清理本地责任。 */
    private suspend fun releaseManagedPackage(packageName: String): String? {
        val state = inspector.inspect(packageName)
        if (state == PackageSuspensionState.NOT_INSTALLED || state == PackageSuspensionState.NOT_SUSPENDED) {
            return if (store.removeManagedPackage(packageName)) null else "无法更新 $packageName 的恢复记录"
        }
        val result = gateway.setPackageSuspended(packageName, userId, suspended = false)
        if (!result.success) return result.errorMessage ?: "恢复 $packageName 失败"
        return if (store.removeManagedPackage(packageName)) null else "无法清除 $packageName 的恢复记录"
    }

    /** 对单个活动目标执行所有权检查、预认领和暂停命令。 */
    private suspend fun ensureDesiredPackageSuspended(packageName: String): String? {
        val managed = packageName in store.state.value.managedPackages
        return when (val current = inspector.inspect(packageName)) {
            PackageSuspensionState.NOT_INSTALLED -> {
                if (managed && !store.removeManagedPackage(packageName)) {
                    "无法清理已卸载应用 $packageName 的记录"
                } else {
                    null
                }
            }

            PackageSuspensionState.SUSPENDED -> null
            PackageSuspensionState.UNKNOWN -> if (managed) {
                suspendManagedPackage(packageName)
            } else {
                "无法确认 $packageName 的原始暂停状态"
            }

            PackageSuspensionState.NOT_SUSPENDED -> if (managed) {
                suspendManagedPackage(packageName)
            } else {
                claimAndSuspendPackage(packageName)
            }
        }
    }

    /** 先同步认领新目标，再执行远端暂停；失败时撤销认领。 */
    private suspend fun claimAndSuspendPackage(packageName: String): String? {
        if (!gateway.status.value.isReady) return "Shizuku 连接已中断"
        if (!store.claimPackage(packageName)) return "无法记录 $packageName 的恢复责任"
        val result = gateway.setPackageSuspended(packageName, userId, suspended = true)
        if (result.success) return null
        val rolledBack = store.removeManagedPackage(packageName)
        val message = result.errorMessage ?: "暂停 $packageName 失败"
        return if (rolledBack) message else "$message；恢复记录回滚失败"
    }

    /** 重新暂停已经由时界认领但当前不再暂停的活动目标。 */
    private suspend fun suspendManagedPackage(packageName: String): String? {
        if (!gateway.status.value.isReady) return "Shizuku 连接已中断"
        val result = gateway.setPackageSuspended(packageName, userId, suspended = true)
        return if (result.success) null else result.errorMessage ?: "暂停 $packageName 失败"
    }

    /** 将一次同步失败写入设置页状态并返回调用方。 */
    private fun recordFailure(message: String): SuspendActionResult {
        val result = failure(message)
        operationState.value = OperationState(lastError = message)
        return result
    }

    /** 记录当前串行操作是否进行中及最近一次错误。 */
    private data class OperationState(
        val busy: Boolean = false,
        val lastError: String? = null,
    )

    private companion object {
        /** 创建无错误的动作结果。 */
        fun success(): SuspendActionResult = SuspendActionResult(success = true)

        /** 创建带中文摘要的失败动作结果。 */
        fun failure(message: String): SuspendActionResult = SuspendActionResult(
            success = false,
            message = message,
        )

        /** 将多个独立包错误合并为有界的设置页摘要。 */
        fun List<String>.toResult(): SuspendActionResult = if (isEmpty()) {
            success()
        } else {
            failure(take(MAX_VISIBLE_ERRORS).joinToString("；"))
        }

        const val MAX_VISIBLE_ERRORS = 3
    }
}
