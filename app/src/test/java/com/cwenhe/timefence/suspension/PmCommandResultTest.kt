package com.cwenhe.timefence.suspension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 覆盖 pm 子进程退出、超时和错误摘要规则。 */
class PmCommandResultTest {
    /** 验证标准 pm 成功输出不产生错误摘要。 */
    @Test
    fun `退出码为零表示命令成功`() {
        assertNull(
            PmCommandResultFormatter.errorMessage(
                exitCode = 0,
                timedOut = false,
                output = "Package com.example new suspended state: true",
            ),
        )
    }

    /** 验证超时错误不会被远端输出覆盖。 */
    @Test
    fun `超时优先返回明确错误`() {
        assertEquals(
            "系统暂停命令执行超时",
            PmCommandResultFormatter.errorMessage(
                exitCode = null,
                timedOut = true,
                output = "",
            ),
        )
    }

    /** 验证失败只展示第一行受控错误。 */
    @Test
    fun `非零退出码只返回受控摘要`() {
        assertEquals(
            "系统暂停命令失败（退出码 1）：Failure calling service package",
            PmCommandResultFormatter.errorMessage(
                exitCode = 1,
                timedOut = false,
                output = "Failure calling service package\n后续不应泄露的长文本",
            ),
        )
    }
}
