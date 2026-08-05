package com.cwenhe.timefence.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.provider.Settings
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 查询用户可选择的桌面应用，并排除可能导致设备无法恢复的系统入口。 */
class InstalledAppRepository(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    /** 在 IO 线程加载、去重并按名称排序可启动应用。 */
    suspend fun loadLaunchableApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val excludedPackages = loadExcludedPackages()
        val labelCollator = Collator.getInstance(Locale.getDefault())
        queryActivities(launcherIntent())
            .asSequence()
            .mapNotNull { resolveInfo -> resolveInfo.activityInfo?.applicationInfo }
            .filterNot { applicationInfo -> applicationInfo.packageName in excludedPackages }
            .distinctBy { applicationInfo -> applicationInfo.packageName }
            .map { applicationInfo ->
                InstalledApp(
                    packageName = applicationInfo.packageName,
                    label = packageManager.getApplicationLabel(applicationInfo).toString(),
                    icon = packageManager.getApplicationIcon(applicationInfo),
                )
            }
            .sortedWith { first, second ->
                labelCollator.compare(first.label, second.label).takeIf { it != 0 }
                    ?: first.packageName.compareTo(second.packageName)
            }
            .toList()
    }

    /** 动态收集时界自身、桌面、设置、权限控制器和安装器包名。 */
    private fun loadExcludedPackages(): Set<String> = buildSet {
        add(appContext.packageName)
        queryActivities(homeIntent()).mapNotNullTo(this) { it.activityInfo?.packageName }
        SYSTEM_SETTING_ACTIONS.forEach { action ->
            resolvePackage(Intent(action))?.let(::add)
        }
        PERMISSION_CONTROLLER_ACTIONS.forEach { action ->
            resolvePackage(Intent(action))?.let(::add)
        }
        queryActivities(
            Intent(Intent.ACTION_VIEW).setType(ANDROID_PACKAGE_MIME_TYPE),
        ).mapNotNullTo(this) { it.activityInfo?.packageName }
    }

    /** 构造桌面启动器查询 Intent。 */
    private fun launcherIntent(): Intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    /** 构造系统桌面处理器查询 Intent。 */
    private fun homeIntent(): Intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    /** 兼容不同 Android 版本查询可处理 Intent 的 Activity。 */
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

    /** 返回能够处理指定 Intent 的首选包名。 */
    private fun resolvePackage(intent: Intent): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
                ?.activityInfo?.packageName
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
        }

    companion object {
        private const val ANDROID_PACKAGE_MIME_TYPE = "application/vnd.android.package-archive"
        private val SYSTEM_SETTING_ACTIONS = listOf(
            Settings.ACTION_SETTINGS,
            Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS,
            Settings.ACTION_ACCESSIBILITY_SETTINGS,
        )
        private val PERMISSION_CONTROLLER_ACTIONS = listOf(
            "android.intent.action.MANAGE_PERMISSIONS",
            "android.content.pm.action.REQUEST_PERMISSIONS",
        )
    }
}
