package com.cwenhe.timefence.suspension

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 覆盖远端命令包名白名单和静态关键包门禁。 */
class PackageNameValidatorTest {
    /** 验证标准多段包名可以安全作为单个远端参数。 */
    @Test
    fun `标准 Android 包名通过校验`() {
        assertTrue(PackageNameValidator.isValid("com.example.app_2"))
    }

    /** 验证空白、路径和 shell 控制字符全部被拒绝。 */
    @Test
    fun `可能改变命令含义的包名被拒绝`() {
        listOf(
            "",
            ".com.example",
            "com.example.",
            "com example.app",
            "com/example/app",
            "com.example;id",
            "com.example\napp",
            "-rf",
            "single",
        ).forEach { packageName ->
            assertFalse("应拒绝 $packageName", PackageNameValidator.isValid(packageName))
        }
    }

    /** 验证远端命令边界始终拒绝时界和 Shizuku。 */
    @Test
    fun `静态关键包不能进入暂停命令`() {
        assertFalse(PackageNameValidator.canSuspend("com.cwenhe.timefence"))
        assertFalse(PackageNameValidator.canSuspend("moe.shizuku.privileged.api"))
        assertTrue(PackageNameValidator.canSuspend("com.example.video"))
    }
}
