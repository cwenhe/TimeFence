package com.cwenhe.timefence.permissions

/**
 * 汇总时界依赖的系统授权与服务连接状态。
 *
 * 通知权限只影响提示能力，不参与核心保护就绪判断。
 */
data class PermissionStatus(
    val accessibilityEnabled: Boolean,
    val accessibilityConnected: Boolean,
    val exactAlarmAllowed: Boolean,
    val notificationsAllowed: Boolean,
    val batteryOptimizationIgnored: Boolean,
) {
    /** 只有无障碍已启用、已连接且精确闹钟可用时，才视为核心保护就绪。 */
    val protectionReady: Boolean
        get() = accessibilityEnabled && accessibilityConnected && exactAlarmAllowed
}
