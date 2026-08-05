package com.cwenhe.timefence.enforcement

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.cwenhe.timefence.R

/**
 * 使用短时无障碍覆盖层说明本次拦截，不获取焦点且不长期遮挡桌面。
 *
 * @param service 已连接的无障碍服务，提供合法的覆盖层窗口令牌。
 */
internal class BlockOverlay(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var attachedView: View? = null
    private val dismissRunnable = Runnable { dismiss() }

    /** 展示统一反馈文本，并在四秒后自动关闭。 */
    fun show(feedback: BlockFeedback) {
        dismiss()
        val view = createOverlayView(feedback)
        try {
            windowManager.addView(view, createLayoutParams())
            attachedView = view
            mainHandler.postDelayed(dismissRunnable, DISPLAY_DURATION_MILLIS)
        } catch (_: RuntimeException) {
            attachedView = null
        }
    }

    /** 立即移除已显示的覆盖层；重复调用不会产生副作用。 */
    fun dismiss() {
        mainHandler.removeCallbacks(dismissRunnable)
        val view = attachedView ?: return
        attachedView = null
        runCatching { windowManager.removeViewImmediate(view) }
    }

    /** 构建包含规则名、自定义提示和关闭图标的紧凑提示条。 */
    private fun createOverlayView(feedback: BlockFeedback): View {
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.rgb(38, 41, 43))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.rgb(184, 81, 67))
            }
            elevation = dp(8).toFloat()
        }
        val textColumn = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
        }
        textColumn.addView(TextView(service).apply {
            text = feedback.ruleName.ifBlank { service.getString(R.string.block_overlay_default_rule) }
            setTextColor(Color.WHITE)
            textSize = 16f
        })
        textColumn.addView(TextView(service).apply {
            text = feedback.text
            setTextColor(Color.rgb(221, 224, 226))
            textSize = 14f
        })
        root.addView(
            textColumn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        root.addView(ImageButton(service).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = service.getString(R.string.block_overlay_close)
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        return root
    }

    /** 创建不抢焦点、位于屏幕顶部的无障碍覆盖层参数。 */
    private fun createLayoutParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(16)
        horizontalMargin = 0.04f
    }

    /** 把 dp 转为当前设备密度下的整数像素。 */
    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).toInt()

    companion object {
        private const val DISPLAY_DURATION_MILLIS = 4_000L
    }
}
