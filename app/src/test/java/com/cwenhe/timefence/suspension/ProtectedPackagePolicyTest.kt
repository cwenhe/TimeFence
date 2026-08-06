package com.cwenhe.timefence.suspension

import org.junit.Assert.assertEquals
import org.junit.Test

/** 覆盖协调器执行前的最终关键包过滤。 */
class ProtectedPackagePolicyTest {
    /** 验证策略同时过滤关键包和非法包名。 */
    @Test
    fun `关键包和非法包名不会进入暂停目标`() {
        val allowed = ProtectedPackagePolicy.filterAllowed(
            candidates = setOf(
                "com.example.video",
                "com.android.systemui",
                "com.honor.launcher",
                "com.example input",
            ),
            protectedPackages = setOf(
                "com.android.systemui",
                "com.honor.launcher",
            ),
        )

        assertEquals(setOf("com.example.video"), allowed)
    }
}
