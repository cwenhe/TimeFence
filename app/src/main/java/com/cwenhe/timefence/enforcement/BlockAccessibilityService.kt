package com.cwenhe.timefence.enforcement

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.cwenhe.timefence.TimeFenceApplication
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 监听前台窗口，并在窗口事件或时间边界命中规则时执行返回桌面动作。
 *
 * 服务只读取窗口所属包名，不读取、记录或传输界面节点文字。
 */
class BlockAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val blockOverlay by lazy { BlockOverlay(this) }
    /** 获取应用进程级依赖，避免服务自行创建数据库或调度器。 */
    private val appContainer
        get() = (application as TimeFenceApplication).container

    private var lastForegroundPackage: String? = null
    private var lastBlockedPackage: String? = null
    private var lastBlockedAtMillis = 0L
    private var boundaryCheckJob: Job? = null

    /** 连接后立即补检当前窗口、发布连接状态并恢复下一条时间边界。 */
    override fun onServiceConnected() {
        super.onServiceConnected()
        EnforcementBridge.connect(this, applicationContext)
        appContainer.permissionStatusRepository.refresh()
        checkActiveWindowAtBoundary()
        rescheduleBoundary()
    }

    /** 窗口变化时记录最近可靠包名，并按当前时刻重新计算规则后执行拦截。 */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null ||
            (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED)
        ) {
            return
        }
        val packageName = event.packageName?.toString() ?: resolveActivePackage() ?: return
        if (packageName != this.packageName) {
            lastForegroundPackage = packageName
        }
        serviceScope.launch { enforceIfBlocked(packageName) }
    }

    /** 无障碍中断不需要额外处理，后续窗口或边界事件会重新校验状态。 */
    override fun onInterrupt() = Unit

    /** 系统解绑服务时立即清理桥接状态，使边界请求能够持久化等待下次连接。 */
    override fun onUnbind(intent: Intent?): Boolean {
        EnforcementBridge.disconnect(this)
        return super.onUnbind(intent)
    }

    /** 清理服务弱引用、协程和覆盖层，确保权限状态不会继续显示为已连接。 */
    override fun onDestroy() {
        boundaryCheckJob?.cancel()
        serviceScope.cancel()
        blockOverlay.dismiss()
        EnforcementBridge.disconnect(this)
        super.onDestroy()
    }

    /**
     * 在边界到达时按 0/100/300/700ms 四个时间点主动读取活动窗口。
     *
     * 新请求会替换尚未完成的旧请求；首次确认命中规则后停止后续重试。
     */
    fun checkActiveWindowAtBoundary() {
        boundaryCheckJob?.cancel()
        boundaryCheckJob = serviceScope.launch {
            val startedAt = SystemClock.uptimeMillis()
            for (offset in BOUNDARY_RETRY_OFFSETS_MILLIS) {
                val remainingDelay = startedAt + offset - SystemClock.uptimeMillis()
                if (remainingDelay > 0) delay(remainingDelay)
                for (packageName in resolveActivePackages()) {
                    if (enforceIfBlocked(packageName)) return@launch
                }
            }
        }
    }

    /** 服务重连后从数据库读取最新规则并恢复唯一下一边界。 */
    private fun rescheduleBoundary() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val rules = appContainer.scheduleRepository.getRules()
                appContainer.boundaryAlarmScheduler.reschedule(rules, ZonedDateTime.now())
            } catch (error: Exception) {
                Log.e(TAG, "无障碍服务重建边界失败", error)
            }
        }
    }

    /**
     * 依次读取活动根窗口、active/focused 应用窗口，最后回退到最近的窗口事件包名。
     *
     * 锁屏或屏幕未点亮时返回空值，避免对系统界面执行 HOME。
     */
    private fun resolveActivePackage(): String? = resolveActivePackages().firstOrNull()

    /** 收集活动根节点及所有应用窗口包名，避免输入法窗口遮挡目标应用。 */
    private fun resolveActivePackages(): List<String> {
        if (!isDeviceInteractiveAndUnlocked()) return emptyList()
        val resolvedPackages = buildList {
            runCatching { rootInActiveWindow?.packageName?.toString() }
                .getOrNull()
                ?.let(::add)
            runCatching {
                windows.asSequence()
                    .filter { window -> window.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    .sortedByDescending { window -> window.isActive || window.isFocused }
                    .mapNotNull { window -> window.root?.packageName?.toString() }
                    .toList()
            }.getOrDefault(emptyList()).forEach(::add)
        }.distinct()
        return resolvedPackages.ifEmpty { listOfNotNull(lastForegroundPackage) }
    }

    /** 每次检查都读取数据库并按当前时区重新求值，命中时先返回桌面再显示反馈。 */
    private suspend fun enforceIfBlocked(packageName: String): Boolean {
        if (packageName == this.packageName || !isDeviceInteractiveAndUnlocked()) return false
        val evaluation = try {
            appContainer.scheduleEvaluator.evaluate(
                now = ZonedDateTime.now(),
                rules = appContainer.scheduleRepository.getRules(),
            )
        } catch (error: Exception) {
            Log.e(TAG, "读取规则并检查前台应用失败", error)
            return false
        }
        if (packageName !in evaluation.blockedPackages) return false
        if (isDuplicateBlock(packageName)) {
            return performGlobalAction(GLOBAL_ACTION_HOME)
        }
        val returnedHome = performGlobalAction(GLOBAL_ACTION_HOME)
        if (returnedHome) {
            lastBlockedPackage = packageName
            lastBlockedAtMillis = SystemClock.elapsedRealtime()
            blockOverlay.show(evaluation.activeRules, packageName)
        }
        return returnedHome
    }

    /** 判断同一包名是否刚被处理，用于抑制重复反馈但不跳过必要的 HOME 动作。 */
    private fun isDuplicateBlock(packageName: String): Boolean =
        lastBlockedPackage == packageName &&
            SystemClock.elapsedRealtime() - lastBlockedAtMillis < BLOCK_DEBOUNCE_MILLIS

    /** 只有屏幕交互中且系统未锁定时，才允许使用最近包名或执行 HOME。 */
    private fun isDeviceInteractiveAndUnlocked(): Boolean =
        powerManager.isInteractive && !keyguardManager.isKeyguardLocked

    companion object {
        private const val TAG = "BlockAccessibility"
        private const val BLOCK_DEBOUNCE_MILLIS = 500L
        private val BOUNDARY_RETRY_OFFSETS_MILLIS = longArrayOf(0L, 100L, 300L, 700L)
    }
}
