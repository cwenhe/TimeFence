package com.cwenhe.timefence.apps

import org.junit.Assert.assertEquals
import org.junit.Test

/** 验证应用选择器对自身与关键系统包的展示边界。 */
class InstalledAppRepositoryTest {
    /** 验证选择器保留时界自身但继续隐藏其他保护包。 */
    @Test
    fun `应用选择器保留时界自身但继续排除其他保护包`() {
        val protectedPackages = setOf(
            "com.cwenhe.timefence",
            "com.honor.launcher",
            "com.android.settings",
        )

        assertEquals(
            setOf("com.honor.launcher", "com.android.settings"),
            pickerExcludedPackages(
                protectedPackages = protectedPackages,
                ownPackageName = "com.cwenhe.timefence",
            ),
        )
    }
}
