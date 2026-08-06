package com.cwenhe.timefence.suspension

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** 系统当前观察到的单包安装和暂停状态。 */
enum class PackageSuspensionState {
    SUSPENDED,
    NOT_SUSPENDED,
    NOT_INSTALLED,
    UNKNOWN,
}

/** 隔离 PackageManager 查询，便于协调逻辑做纯单元测试。 */
interface PackageSuspensionInspector {
    /** 查询包状态；权限或厂商异常使用 UNKNOWN 表示。 */
    fun inspect(packageName: String): PackageSuspensionState
}

/** 通过当前用户的 PackageManager 查询实际暂停状态。 */
class AndroidPackageSuspensionInspector(context: Context) : PackageSuspensionInspector {
    private val packageManager = context.applicationContext.packageManager

    /** 区分未安装、已暂停、未暂停和无法确定四种结果。 */
    override fun inspect(packageName: String): PackageSuspensionState = try {
        val applicationInfo = packageManager.getApplicationInfo(
            packageName,
            PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        val suspended = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            packageManager.isPackageSuspended(packageName)
        } else {
            applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SUSPENDED != 0
        }
        if (suspended) {
            PackageSuspensionState.SUSPENDED
        } else {
            PackageSuspensionState.NOT_SUSPENDED
        }
    } catch (_: PackageManager.NameNotFoundException) {
        PackageSuspensionState.NOT_INSTALLED
    } catch (_: RuntimeException) {
        PackageSuspensionState.UNKNOWN
    }
}
