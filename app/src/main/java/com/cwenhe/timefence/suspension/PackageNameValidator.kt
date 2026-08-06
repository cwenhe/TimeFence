package com.cwenhe.timefence.suspension

import com.cwenhe.timefence.BuildConfig

/** 只允许标准多段 Android 包名进入远端 shell 命令参数。 */
internal object PackageNameValidator {
    private val packageNamePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    /** 即使上层策略遗漏也绝不能进入暂停命令的静态包集合。 */
    val alwaysProtectedPackages = setOf(
        BuildConfig.APPLICATION_ID,
        "moe.shizuku.privileged.api",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )

    /** 校验包名是否能作为单个、不含控制字符的 pm 参数。 */
    fun isValid(packageName: String): Boolean =
        packageName.length <= MAX_PACKAGE_NAME_LENGTH && packageNamePattern.matches(packageName)

    /** 校验一个包是否允许进入远端暂停命令；恢复命令不使用此限制。 */
    fun canSuspend(packageName: String): Boolean =
        isValid(packageName) && packageName !in alwaysProtectedPackages

    private const val MAX_PACKAGE_NAME_LENGTH = 255
}
