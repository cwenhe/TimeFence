package com.cwenhe.timefence.enforcement

import android.accessibilityservice.AccessibilityService
import android.widget.Toast

/** 使用系统 Toast 显示拦截提示，避免自定义悬浮层覆盖屏幕。 */
internal class BlockOverlay(private val service: AccessibilityService) {
    private var toast: Toast? = null

    /** 复用 Toast 显示当前拦截正文，避免连续拦截时提示排队堆叠。 */
    fun show(feedback: BlockFeedback) {
        val currentToast = toast
        if (currentToast == null) {
            toast = Toast.makeText(service, feedback.text, Toast.LENGTH_LONG)
        } else {
            currentToast.setText(feedback.text)
            currentToast.duration = Toast.LENGTH_LONG
        }
        toast?.show()
    }

    /** 取消当前提示并清理 Toast 引用，避免服务销毁后继续持有状态。 */
    fun dismiss() {
        toast?.cancel()
        toast = null
    }
}
