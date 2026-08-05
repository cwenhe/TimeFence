package com.cwenhe.timefence.permissions

import android.Manifest
import android.annotation.SuppressLint
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cwenhe.timefence.enforcement.BlockAccessibilityService
import com.cwenhe.timefence.enforcement.EnforcementBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 读取系统授权并提供进程内权限状态流。
 *
 * @param context 用于访问系统服务；内部只保留应用上下文。
 */
class PermissionStatusRepository(context: Context) {
    private val appContext = context.applicationContext
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableStatus = MutableStateFlow(readStatus())

    /** 由系统服务连接状态变化和显式刷新共同驱动的权限快照流。 */
    val status: StateFlow<PermissionStatus> = mutableStatus.asStateFlow()

    init {
        processScope.launch {
            EnforcementBridge.serviceConnected.collect {
                refresh()
            }
        }
    }

    /** 重新读取全部系统状态，并把最新快照发布到状态流。 */
    fun refresh(): PermissionStatus {
        val refreshed = readStatus()
        mutableStatus.value = refreshed
        return refreshed
    }

    /** 从系统服务生成一次不带缓存的权限快照。 */
    private fun readStatus(): PermissionStatus = PermissionStatus(
        accessibilityEnabled = isAccessibilityEnabled(),
        accessibilityConnected = EnforcementBridge.serviceConnected.value,
        exactAlarmAllowed = isExactAlarmAllowed(),
        notificationsAllowed = areNotificationsAllowed(),
        batteryOptimizationIgnored = isBatteryOptimizationIgnored(),
    )

    /** 判断系统设置中是否启用了时界无障碍服务。 */
    private fun isAccessibilityEnabled(): Boolean {
        val accessibilityManager = appContext.getSystemService(AccessibilityManager::class.java)
        val expectedComponent = ComponentName(appContext, BlockAccessibilityService::class.java)
        return accessibilityManager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { serviceInfo ->
                val actual = serviceInfo.resolveInfo.serviceInfo
                ComponentName(actual.packageName, actual.name) == expectedComponent
            }
    }

    /** 判断当前系统是否允许应用注册精确闹钟。 */
    private fun isExactAlarmAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return appContext.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    /** 同时考虑 Android 13 运行时授权和系统级通知总开关。 */
    private fun areNotificationsAllowed(): Boolean {
        val runtimeAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return runtimeAllowed && NotificationManagerCompat.from(appContext).areNotificationsEnabled()
    }

    /** 判断时界是否已被系统电池优化豁免。 */
    @SuppressLint("MissingPermission")
    private fun isBatteryOptimizationIgnored(): Boolean {
        val powerManager = appContext.getSystemService(PowerManager::class.java)
        return runCatching {
            powerManager.isIgnoringBatteryOptimizations(appContext.packageName)
        }.getOrDefault(false)
    }
}
