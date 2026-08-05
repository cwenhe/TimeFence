package com.cwenhe.timefence.permissions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 打开与时界授权相关的系统页面，并在厂商页面不可用时回退到标准设置。
 *
 * @param context 用于启动设置 Activity；内部只保留应用上下文。
 */
class SystemSettingsNavigator(context: Context) {
    private val appContext = context.applicationContext
    private val packageUri = Uri.parse("package:${appContext.packageName}")

    /** 打开系统无障碍服务列表。 */
    fun openAccessibilitySettings(): Boolean = startSafely(
        primary = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        fallback = appDetailsIntent(),
    )

    /** 在 Android 12 及以上打开时界的精确闹钟授权页。 */
    fun openExactAlarmSettings(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return openAppDetailsSettings()
        return startSafely(
            primary = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri),
            fallback = appDetailsIntent(),
        )
    }

    /** 打开时界的通知设置页。 */
    fun openNotificationSettings(): Boolean = startSafely(
        primary = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(
            Settings.EXTRA_APP_PACKAGE,
            appContext.packageName,
        ),
        fallback = appDetailsIntent(),
    )

    /** 打开系统电池优化应用列表，供用户将时界设为不受限制。 */
    fun openBatteryOptimizationSettings(): Boolean = startSafely(
        primary = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        fallback = appDetailsIntent(),
    )

    /** 打开应用详情页，也是 Android 13 侧载应用“允许受限设置”的入口。 */
    fun openAppDetailsSettings(): Boolean = startSafely(appDetailsIntent())

    /** 打开厂商语音合成设置，无法解析时回退到系统声音设置。 */
    fun openTextToSpeechSettings(): Boolean = startSafely(
        primary = Intent(ACTION_TEXT_TO_SPEECH_SETTINGS),
        fallback = Intent(Settings.ACTION_SOUND_SETTINGS),
    )

    /** 优先探测荣耀或华为后台启动管理页，不可用时回退到标准电池优化页。 */
    fun openHonorBackgroundSettings(): Boolean {
        val candidates = listOf(
            ComponentName(
                "com.hihonor.systemmanager",
                "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            ComponentName(
                "com.hihonor.systemmanager",
                "com.hihonor.systemmanager.optimize.process.ProtectActivity",
            ),
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity",
            ),
        )
        val availableIntent = candidates
            .asSequence()
            .map { component -> Intent().setComponent(component) }
            .firstOrNull(::canResolve)
        return if (availableIntent != null) {
            startSafely(availableIntent, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } else {
            openBatteryOptimizationSettings()
        }
    }

    /** 构造只指向时界自身的标准应用详情页 Intent。 */
    private fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)

    /** 仅在系统声明了可处理 Activity 时，才尝试厂商专属设置入口。 */
    private fun canResolve(intent: Intent): Boolean =
        intent.resolveActivity(appContext.packageManager) != null

    /** 启动设置页；目标缺失或厂商拒绝启动时尝试一次标准回退页。 */
    private fun startSafely(primary: Intent, fallback: Intent? = null): Boolean {
        val primaryStarted = runCatching {
            appContext.startActivity(primary.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (primaryStarted) return true
        return fallback != null && runCatching {
            appContext.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    private companion object {
        const val ACTION_TEXT_TO_SPEECH_SETTINGS = "com.android.settings.TTS_SETTINGS"
    }
}
