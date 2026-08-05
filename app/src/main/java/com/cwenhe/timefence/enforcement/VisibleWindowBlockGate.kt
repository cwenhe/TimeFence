package com.cwenhe.timefence.enforcement

/** 描述一次无障碍应用窗口，并保留窗口身份与前台交互状态。 */
internal data class VisibleAppWindow(
    val packageName: String,
    val windowId: Int? = null,
    val isActive: Boolean = true,
    val isFocused: Boolean = false,
)

/** 对同一窗口执行有界 HOME 重试，并在窗口重新激活时开启新一轮拦截。 */
internal class VisibleWindowBlockGate(
    private val clockMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLISECOND },
) {
    private val handledWindows = mutableMapOf<WindowKey, HandledWindow>()

    /** 选择首次出现、到达重试时间或从非活动状态重新激活的受限窗口。 */
    fun next(
        windows: List<VisibleAppWindow>,
        blockedPackages: Set<String>,
    ): VisibleAppWindow? {
        val blockedWindows = windows.filter { window -> window.packageName in blockedPackages }
        reconcileWindowIdentities(blockedWindows)
        val visibleKeys = blockedWindows.mapTo(mutableSetOf(), ::windowKey)
        handledWindows.keys.retainAll(visibleKeys)
        val now = clockMillis()
        return blockedWindows.firstOrNull { window ->
            val key = windowKey(window)
            val handled = handledWindows[key] ?: return@firstOrNull true
            when {
                !window.isActive && !window.isFocused -> {
                    handled.observedInactive = true
                    false
                }

                handled.observedInactive -> {
                    handledWindows.remove(key)
                    true
                }

                handled.attempts < MAX_HOME_ATTEMPTS && now >= handled.retryAtMillis -> true
                else -> false
            }
        }
    }

    /** 记录一次系统已接受的 HOME，并返回是否需要展示本轮首次反馈。 */
    fun markHomeSucceeded(window: VisibleAppWindow): Boolean {
        val key = windowKey(window)
        val previous = handledWindows[key]
        val firstAttempt = previous == null || previous.observedInactive
        val attempts = if (firstAttempt) 1 else previous.attempts + 1
        handledWindows[key] = HandledWindow(
            attempts = attempts,
            retryAtMillis = clockMillis() + HOME_RETRY_DELAY_MILLIS,
            observedInactive = false,
        )
        return firstAttempt
    }

    /** 清空所有已处理记录，使下一次规则开始时能够重新拦截持续可见应用。 */
    fun clear() {
        handledWindows.clear()
    }

    /** 使用窗口 ID 区分同包多窗口；厂商未提供 ID 时安全回退到包名。 */
    private fun windowKey(window: VisibleAppWindow): WindowKey = WindowKey(
        packageName = window.packageName,
        windowId = window.windowId,
    )

    /** 在厂商 fallback 与真实窗口 ID 之间迁移状态，避免身份抖动重置重试预算。 */
    private fun reconcileWindowIdentities(windows: List<VisibleAppWindow>) {
        windows.groupBy(VisibleAppWindow::packageName).forEach { (packageName, packageWindows) ->
            val fallbackKey = WindowKey(packageName, windowId = null)
            if (packageWindows.any { window -> window.windowId == null }) {
                val exactKeys = handledWindows.keys.filter { key ->
                    key.packageName == packageName && key.windowId != null
                }
                val states = exactKeys.mapNotNull(handledWindows::get)
                if (states.isNotEmpty()) {
                    handledWindows[fallbackKey] = mergeHandledStates(
                        states + listOfNotNull(handledWindows[fallbackKey]),
                    )
                    exactKeys.forEach(handledWindows::remove)
                }
            } else {
                val fallbackState = handledWindows.remove(fallbackKey) ?: return@forEach
                val targetKey = packageWindows.map(::windowKey).first()
                handledWindows[targetKey] = mergeHandledStates(
                    listOfNotNull(handledWindows[targetKey], fallbackState),
                )
            }
        }
    }

    /** 合并模糊和精确窗口状态时采用最保守的次数、时间和活动观察结果。 */
    private fun mergeHandledStates(states: List<HandledWindow>): HandledWindow = HandledWindow(
        attempts = states.maxOf(HandledWindow::attempts),
        retryAtMillis = states.maxOf(HandledWindow::retryAtMillis),
        observedInactive = states.all(HandledWindow::observedInactive),
    )

    /** 保存一个窗口当前拦截轮次的次数、退避时间和重新激活状态。 */
    private data class HandledWindow(
        val attempts: Int,
        val retryAtMillis: Long,
        var observedInactive: Boolean,
    )

    /** 作为去重键绑定包名和系统窗口 ID。 */
    private data class WindowKey(
        val packageName: String,
        val windowId: Int?,
    )

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAX_HOME_ATTEMPTS = 3
        const val HOME_RETRY_DELAY_MILLIS = 250L
    }
}
