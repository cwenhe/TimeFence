package com.cwenhe.timefence.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 读取可由用户选择且不属于关键系统角色的桌面应用。 */
class InstalledAppRepository(
    context: Context,
    private val protectedPackageResolver: ProtectedPackageResolver = ProtectedPackageResolver(context),
) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    /** 在 IO 线程加载、去重并按本地语言排序可选应用。 */
    suspend fun loadLaunchableApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val excludedPackages = pickerExcludedPackages(
            protectedPackages = protectedPackageResolver.resolve(),
            ownPackageName = appContext.packageName,
        )
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

    /** 创建查询普通桌面入口的 Intent。 */
    private fun launcherIntent(): Intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    /** 兼容新旧 PackageManager API 查询桌面入口。 */
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
}

/** 为应用选择器移除时界自身，保留系统暂停层使用的完整保护集合。 */
internal fun pickerExcludedPackages(
    protectedPackages: Set<String>,
    ownPackageName: String,
): Set<String> = protectedPackages - ownPackageName
