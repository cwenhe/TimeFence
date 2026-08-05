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
import com.cwenhe.timefence.rules.ScheduleRule
import java.time.ZonedDateTime
import java.util.Locale

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

    /** 展示命中包名对应、结束最晚的活动规则，并在四秒后自动关闭。 */
    fun show(activeRules: List<ScheduleRule>, packageName: String) {
        val now = ZonedDateTime.now()
        val rule = activeRules
            .asSequence()
            .filter { packageName in it.packages }
            .maxByOrNull { remainingMinutes(it, now) }
            ?: return
        dismiss()
        val view = createOverlayView(rule)
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

    /** 构建包含规则名、结束时间和关闭图标的紧凑提示条。 */
    private fun createOverlayView(rule: ScheduleRule): View {
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
            text = rule.name.ifBlank { service.getString(R.string.block_overlay_default_rule) }
            setTextColor(Color.WHITE)
            textSize = 16f
        })
        textColumn.addView(TextView(service).apply {
            text = service.getString(R.string.block_overlay_until, formatEndMinute(rule.endMinute))
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

    /** 计算活动规则从当前时刻到结束边界的分钟数，用于选择结束最晚的规则。 */
    private fun remainingMinutes(rule: ScheduleRule, now: ZonedDateTime): Int {
        val currentMinute = now.hour * MINUTES_PER_HOUR + now.minute
        return if (rule.startMinute < rule.endMinute) {
            rule.endMinute - currentMinute
        } else if (currentMinute >= rule.startMinute) {
            MINUTES_PER_DAY - currentMinute + rule.endMinute
        } else {
            rule.endMinute - currentMinute
        }
    }

    /** 将领域模型中的日内分钟转换为本地化的 `HH:mm` 文本。 */
    private fun formatEndMinute(endMinute: Int): String = String.format(
        Locale.getDefault(),
        "%02d:%02d",
        endMinute / MINUTES_PER_HOUR,
        endMinute % MINUTES_PER_HOUR,
    )

    /** 把 dp 转为当前设备密度下的整数像素。 */
    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).toInt()

    companion object {
        private const val DISPLAY_DURATION_MILLIS = 4_000L
        private const val MINUTES_PER_HOUR = 60
        private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
    }
}
