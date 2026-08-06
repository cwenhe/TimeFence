package com.cwenhe.timefence.enforcement

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.cwenhe.timefence.TimeFenceApplication
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 监听前台窗口，并在窗口事件或时间边界命中规则时执行返回桌面动作。 */
class BlockAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val blockOverlay by lazy { BlockOverlay(this) }
    private val appContainer
        get() = (application as TimeFenceApplication).container
    private val processMutex = Mutex()
    private val visibleWindowGate = VisibleWindowBlockGate()
    private val eventProcessor = ConflatedSignalProcessor(
        scope = serviceScope,
        onError = { error -> Log.e(TAG, "处理窗口事件失败", error) },
        process = { processVisibleWindows() },
    )

    private var lastForegroundPackage: String? = null
    private var boundaryCheckJob: Job? = null
    private var lastSystemSuspendSignalAt = 0L

    /** 连接后立即补检当前窗口、发布连接状态并恢复下一条时间边界。 */
    override fun onServiceConnected() {
        super.onServiceConnected()
        EnforcementBridge.connect(this, applicationContext)
        appContainer.permissionStatusRepository.refresh()
        checkActiveWindowAtBoundary()
        rescheduleBoundary()
    }

    /** 只复制事件原始值并提交合并信号，避免窗口事件创建无限并发协程。 */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !shouldProcessEvent(event)) return
        val packageName = event.packageName?.toString()
        if (!packageName.isNullOrBlank() && packageName != this.packageName) {
            lastForegroundPackage = packageName
        }
        eventProcessor.request()
    }

    /** 无障碍中断不需要额外处理，后续窗口或边界事件会重新校验状态。 */
    override fun onInterrupt() = Unit

    /** 系统解绑服务时立即清理桥接状态，使边界请求能够持久化等待下次连接。 */
    override fun onUnbind(intent: Intent?): Boolean {
        EnforcementBridge.disconnect(this)
        return super.onUnbind(intent)
    }

    /** 清理服务弱引用、协程、合并队列和覆盖层，避免服务销毁后继续访问窗口。 */
    override fun onDestroy() {
        boundaryCheckJob?.cancel()
        eventProcessor.close()
        serviceScope.cancel()
        appContainer.blockSpeechController.stop()
        blockOverlay.dismiss()
        EnforcementBridge.disconnect(this)
        super.onDestroy()
    }

    /** 在边界到达时按 0/100/300/700ms 四个时间点主动读取活动窗口。 */
    fun checkActiveWindowAtBoundary() {
        boundaryCheckJob?.cancel()
        boundaryCheckJob = serviceScope.launch {
            val startedAt = SystemClock.uptimeMillis()
            for (offset in BOUNDARY_RETRY_OFFSETS_MILLIS) {
                val remainingDelay = startedAt + offset - SystemClock.uptimeMillis()
                if (remainingDelay > 0) delay(remainingDelay)
                processVisibleWindows()
            }
        }
    }

    /** 服务重连后从进程内规则快照恢复唯一下一边界，不在热路径读取 Room。 */
    private fun rescheduleBoundary() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val snapshot = appContainer.ruleSnapshot.value
                if (!snapshot.loaded) return@launch
                appContainer.boundaryAlarmScheduler.reschedule(
                    rules = snapshot.rules,
                    now = ZonedDateTime.now(),
                    calendar = appContainer.calendarRepository.snapshot.value,
                )
            } catch (error: Exception) {
                Log.e(TAG, "无障碍服务重建边界失败", error)
            }
        }
    }

    /** 串行求值并批量检查可见窗口，保证同一边界只读取一次规则快照。 */
    private suspend fun processVisibleWindows(): Boolean = processMutex.withLock {
        if (!isDeviceInteractiveAndUnlocked()) return@withLock false
        val snapshot = appContainer.ruleSnapshot.value
        if (!snapshot.loaded) return@withLock false
        val evaluation = try {
            appContainer.scheduleEvaluator.evaluateActive(
                now = ZonedDateTime.now(),
                rules = snapshot.rules,
                calendar = appContainer.calendarRepository.snapshot.value,
            )
        } catch (error: Exception) {
            Log.e(TAG, "读取规则并检查前台应用失败", error)
            return@withLock false
        }
        if (evaluation.blockedPackages.isEmpty()) {
            visibleWindowGate.clear()
            return@withLock false
        }
        // 窗口事件是闹钟延迟时的第二条系统暂停触发路径；请求本身会在合并队列中去重。
        val suspendStatus = appContainer.systemSuspendController.status.value
        val suspendSignalNow = SystemClock.uptimeMillis()
        if (suspendStatus.modeEnabled &&
            suspendSignalNow - lastSystemSuspendSignalAt >= SYSTEM_SUSPEND_SIGNAL_MIN_INTERVAL_MILLIS
        ) {
            lastSystemSuspendSignalAt = suspendSignalNow
            appContainer.systemSuspendController.requestReconcile()
        }
        val candidate = visibleWindowGate.next(
            windows = resolveActiveWindows(),
            blockedPackages = evaluation.blockedPackages,
        ) ?: return@withLock false
        val returnedHome = performGlobalAction(GLOBAL_ACTION_HOME)
        if (returnedHome) {
            val shouldShowFeedback = visibleWindowGate.markHomeSucceeded(candidate)
            scheduleHomeVerification()
            if (shouldShowFeedback) {
                val feedback = BlockFeedbackFormatter.create(
                    activeRules = evaluation.activeRules,
                    packageName = candidate.packageName,
                    appLabel = resolveAppLabel(candidate.packageName),
                )
                if (feedback != null) {
                    blockOverlay.show(feedback)
                    appContainer.protectionNotifier.showInterception(feedback)
                    appContainer.blockSpeechController.speak(
                        feedback = feedback,
                        ruleEnabled = evaluation.activeRules.any { rule ->
                            rule.id == feedback.ruleId && rule.speakNotification
                        },
                    )
                }
            }
        }
        returnedHome
    }

    /** HOME 后延迟复查窗口状态，确保没有后续系统事件时仍能完成有界重试。 */
    private fun scheduleHomeVerification() {
        serviceScope.launch {
            delay(HOME_VERIFICATION_DELAY_MILLIS)
            eventProcessor.request()
        }
    }

    /** 过滤纯窗口几何变化，保留会改变前台归属的无障碍窗口事件。 */
    private fun shouldProcessEvent(event: AccessibilityEvent): Boolean {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return true
        if (event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
        return hasRelevantWindowChanges(event)
    }

    /** 在 API 28 以上判断窗口变化位，纯边界和层级变化不触发完整求值。 */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun hasRelevantWindowChanges(event: AccessibilityEvent): Boolean {
        val changes = event.windowChanges
        if (changes == 0) return true
        val relevantChanges = AccessibilityEvent.WINDOWS_CHANGE_ADDED or
            AccessibilityEvent.WINDOWS_CHANGE_REMOVED or
            AccessibilityEvent.WINDOWS_CHANGE_ACTIVE or
            AccessibilityEvent.WINDOWS_CHANGE_FOCUSED or
            AccessibilityEvent.WINDOWS_CHANGE_PIP
        return changes and relevantChanges != 0
    }

    /** 读取根窗口和所有应用窗口一次，并以最近事件包名作为最后回退。 */
    private fun resolveActiveWindows(): List<VisibleAppWindow> {
        if (!isDeviceInteractiveAndUnlocked()) return emptyList()
        val applicationWindows = runCatching {
            windows.asSequence()
                .filter { window -> window.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .sortedByDescending { window -> window.isActive || window.isFocused }
                .mapNotNull { window ->
                    runCatching {
                        window.root?.packageName?.toString()?.let { packageName ->
                            VisibleAppWindow(
                                packageName = packageName,
                                windowId = window.id,
                                isActive = window.isActive,
                                isFocused = window.isFocused,
                            )
                        }
                    }.getOrNull()
                }
                .toList()
        }.getOrDefault(emptyList())
        val rootPackage = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        val resolved = buildList {
            addAll(applicationWindows)
            if (rootPackage != null && applicationWindows.none { window -> window.packageName == rootPackage }) {
                add(VisibleAppWindow(packageName = rootPackage, isActive = true))
            }
        }
            .filter { window -> window.packageName != this.packageName }
            .distinctBy { window -> window.packageName to window.windowId }
        return resolved.ifEmpty {
            listOfNotNull(lastForegroundPackage)
                .filter { packageName -> packageName != this.packageName }
                .map { packageName -> VisibleAppWindow(packageName = packageName, isActive = true) }
        }
    }

    /** 读取受限应用的当前显示名称，包管理器异常时回退为包名。 */
    private fun resolveAppLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    /** 只有屏幕交互中且系统未锁定时，才允许执行 HOME。 */
    private fun isDeviceInteractiveAndUnlocked(): Boolean =
        powerManager.isInteractive && !keyguardManager.isKeyguardLocked

    companion object {
        private const val TAG = "BlockAccessibility"
        private const val HOME_VERIFICATION_DELAY_MILLIS = 250L
        private const val SYSTEM_SUSPEND_SIGNAL_MIN_INTERVAL_MILLIS = 500L
        private val BOUNDARY_RETRY_OFFSETS_MILLIS = longArrayOf(0L, 100L, 300L, 700L)
    }
}
