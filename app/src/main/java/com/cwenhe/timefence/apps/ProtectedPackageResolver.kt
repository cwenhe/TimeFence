package com.cwenhe.timefence.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import com.cwenhe.timefence.suspension.PackageNameValidator

/** 动态解析暂停模式绝不能操作的系统关键包。 */
class ProtectedPackageResolver(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    /** 合并静态关键包与当前桌面、电话、输入法等动态系统角色。 */
    fun resolve(): Set<String> = buildSet {
        addAll(PackageNameValidator.alwaysProtectedPackages)
        add("android")
        add(appContext.packageName)
        runCatching { queryActivities(homeIntent()) }
            .getOrDefault(emptyList())
            .mapNotNullTo(this) { it.activityInfo?.packageName }
        SYSTEM_SETTING_ACTIONS.forEach { action ->
            runCatching { resolvePackage(Intent(action)) }.getOrNull()?.let(::add)
        }
        PERMISSION_CONTROLLER_ACTIONS.forEach { action ->
            runCatching { resolvePackage(Intent(action)) }.getOrNull()?.let(::add)
        }
        runCatching {
            queryActivities(Intent(Intent.ACTION_VIEW).setType(ANDROID_PACKAGE_MIME_TYPE))
        }.getOrDefault(emptyList())
            .mapNotNullTo(this) { it.activityInfo?.packageName }
        runCatching {
            appContext.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        }.getOrNull()
            ?.takeIf(String::isNotBlank)
            ?.let(::add)
        runCatching {
            appContext.getSystemService(InputMethodManager::class.java)?.inputMethodList
        }.getOrNull()
            ?.mapTo(this) { inputMethod -> inputMethod.packageName }
        runCatching {
            Settings.Secure.getString(
                appContext.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD,
            )
        }.getOrNull()
            ?.let(ComponentName::unflattenFromString)
            ?.packageName
            ?.let(::add)
    }

    /** 创建查询所有桌面实现的 Intent。 */
    private fun homeIntent(): Intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    /** 兼容新旧 PackageManager API 查询能够处理指定 Intent 的组件。 */
    private fun queryActivities(intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }

    /** 兼容新旧 PackageManager API 解析系统角色的当前实现包。 */
    private fun resolvePackage(intent: Intent): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
                ?.activityInfo?.packageName
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
        }

    private companion object {
        const val ANDROID_PACKAGE_MIME_TYPE = "application/vnd.android.package-archive"
        val SYSTEM_SETTING_ACTIONS = listOf(
            Settings.ACTION_SETTINGS,
            Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS,
            Settings.ACTION_ACCESSIBILITY_SETTINGS,
        )
        val PERMISSION_CONTROLLER_ACTIONS = listOf(
            "android.intent.action.MANAGE_PERMISSIONS",
            "android.content.pm.action.REQUEST_PERMISSIONS",
        )
    }
}
