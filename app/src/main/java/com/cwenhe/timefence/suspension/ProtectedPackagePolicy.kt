package com.cwenhe.timefence.suspension

/** 对规则目标执行最终包名校验和关键包排除。 */
internal object ProtectedPackagePolicy {
    /** 返回可以交给系统暂停协调器处理的确定性包集合。 */
    fun filterAllowed(
        candidates: Set<String>,
        protectedPackages: Set<String>,
    ): Set<String> = candidates
        .asSequence()
        .filter(PackageNameValidator::isValid)
        .filterNot(protectedPackages::contains)
        .toSortedSet()
}
