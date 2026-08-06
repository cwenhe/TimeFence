package com.cwenhe.timefence.suspension

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 覆盖系统暂停协调器的所有权、离线和紧急恢复状态机。 */
class SystemSuspendControllerTest {
    /** 验证启用模式会暂停当前规则目标并建立恢复责任。 */
    @Test
    fun `启用模式暂停活动目标并写入管理集合`() = runTest {
        val fixture = fixture(setOf(VIDEO_PACKAGE))

        val result = fixture.controller.setModeEnabled(true)

        assertTrue(result.success)
        assertEquals(listOf(Command(VIDEO_PACKAGE, suspended = true)), fixture.gateway.commands)
        assertEquals(setOf(VIDEO_PACKAGE), fixture.store.state.value.managedPackages)
    }

    /** 验证其他工具已经暂停的包不会被时界认领。 */
    @Test
    fun `外部已暂停目标不会被认领或重复暂停`() = runTest {
        val fixture = fixture(setOf(VIDEO_PACKAGE))
        fixture.inspector.states[VIDEO_PACKAGE] = PackageSuspensionState.SUSPENDED

        fixture.controller.setModeEnabled(true)

        assertTrue(fixture.gateway.commands.isEmpty())
        assertTrue(fixture.store.state.value.managedPackages.isEmpty())
    }

    /** 验证规则结束只恢复由时界管理的包。 */
    @Test
    fun `规则结束恢复管理包并清除责任`() = runTest {
        val fixture = fixture(setOf(VIDEO_PACKAGE))
        fixture.controller.setModeEnabled(true)
        fixture.desired.clear()

        fixture.controller.reconcileNow()

        assertEquals(
            listOf(
                Command(VIDEO_PACKAGE, suspended = true),
                Command(VIDEO_PACKAGE, suspended = false),
            ),
            fixture.gateway.commands,
        )
        assertTrue(fixture.store.state.value.managedPackages.isEmpty())
        assertTrue(fixture.store.state.value.modeEnabled)
    }

    /** 验证 Shizuku 不可用时不启动新的系统暂停。 */
    @Test
    fun `服务离线不新增暂停`() = runTest {
        val fixture = fixture(setOf(VIDEO_PACKAGE))
        fixture.store.enableMode()
        fixture.gateway.mutableStatus.value = ShizukuGatewayStatus.initial()

        val result = fixture.controller.reconcileNow()

        assertFalse(result.success)
        assertTrue(fixture.gateway.commands.isEmpty())
        assertTrue(fixture.store.state.value.managedPackages.isEmpty())
    }

    /** 验证首个远端调用断线后，同一轮不会继续认领后续应用。 */
    @Test
    fun `暂停中途断线不会认领后续目标`() = runTest {
        val fixture = fixture(setOf(CHAT_PACKAGE, VIDEO_PACKAGE))
        fixture.gateway.disconnectOnSuspend += CHAT_PACKAGE

        val result = fixture.controller.setModeEnabled(true)

        assertFalse(result.success)
        assertEquals(listOf(Command(CHAT_PACKAGE, suspended = true)), fixture.gateway.commands)
        assertTrue(fixture.store.state.value.managedPackages.isEmpty())
    }

    /** 验证动态关键包即使存在旧规则中也不会进入命令。 */
    @Test
    fun `关键包在协调器最终边界被过滤`() = runTest {
        val fixture = fixture(setOf(VIDEO_PACKAGE, LAUNCHER_PACKAGE))
        fixture.protectedPackages += LAUNCHER_PACKAGE

        fixture.controller.setModeEnabled(true)

        assertEquals(listOf(Command(VIDEO_PACKAGE, suspended = true)), fixture.gateway.commands)
    }

    /** 验证活动管理包若被恢复会在下一次校正重新暂停。 */
    @Test
    fun `被外部恢复的活动管理包会重新暂停`() = runTest {
        val fixture = fixture(setOf(VIDEO_PACKAGE))
        fixture.controller.setModeEnabled(true)
        fixture.inspector.states[VIDEO_PACKAGE] = PackageSuspensionState.NOT_SUSPENDED

        fixture.controller.reconcileNow()

        assertEquals(2, fixture.gateway.commands.count { it.suspended })
        assertEquals(setOf(VIDEO_PACKAGE), fixture.store.state.value.managedPackages)
    }

    /** 验证紧急恢复失败后不再新增暂停，并在重试成功后才关闭模式。 */
    @Test
    fun `部分恢复失败保持只恢复状态直到全部成功`() = runTest {
        val fixture = fixture(setOf(VIDEO_PACKAGE, CHAT_PACKAGE))
        fixture.controller.setModeEnabled(true)
        fixture.gateway.failUnsuspend += CHAT_PACKAGE

        val failed = fixture.controller.releaseAll()
        fixture.desired += NEW_PACKAGE
        fixture.controller.reconcileNow()

        assertFalse(failed.success)
        assertTrue(fixture.store.state.value.modeEnabled)
        assertTrue(fixture.store.state.value.releasePending)
        assertTrue(fixture.gateway.commands.none { it.packageName == NEW_PACKAGE && it.suspended })

        fixture.gateway.failUnsuspend.clear()
        val recovered = fixture.controller.releaseAll()

        assertTrue(recovered.success)
        assertFalse(fixture.store.state.value.modeEnabled)
        assertFalse(fixture.store.state.value.releasePending)
        assertTrue(fixture.store.state.value.managedPackages.isEmpty())
    }

    /** 创建共享假实现并让命令结果同步更新查询状态。 */
    private fun fixture(initialDesired: Set<String>): Fixture {
        val inspector = FakeInspector()
        val gateway = FakeGateway(inspector)
        val store = FakeStore()
        val desired = initialDesired.toMutableSet()
        val protected = mutableSetOf<String>()
        val controller = SystemSuspendController(
            scope = kotlinx.coroutines.test.TestScope(),
            gateway = gateway,
            store = store,
            inspector = inspector,
            protectedPackages = { protected },
            desiredPackages = { desired },
            userId = 0,
        )
        return Fixture(controller, gateway, store, inspector, desired, protected)
    }

    /** 汇总单个测试使用的可变依赖。 */
    private data class Fixture(
        val controller: SystemSuspendController,
        val gateway: FakeGateway,
        val store: FakeStore,
        val inspector: FakeInspector,
        val desired: MutableSet<String>,
        val protectedPackages: MutableSet<String>,
    )

    /** 记录远端命令参数供断言。 */
    private data class Command(val packageName: String, val suspended: Boolean)

    /** 使用内存映射模拟 PackageManager 的暂停查询。 */
    private class FakeInspector : PackageSuspensionInspector {
        val states = mutableMapOf<String, PackageSuspensionState>()

        /** 未显式设置的已安装包视为未暂停。 */
        override fun inspect(packageName: String): PackageSuspensionState =
            states[packageName] ?: PackageSuspensionState.NOT_SUSPENDED
    }

    /** 模拟 Shizuku 网关并按命令更新假系统状态。 */
    private class FakeGateway(private val inspector: FakeInspector) : PackageSuspendGateway {
        val mutableStatus = MutableStateFlow(
            ShizukuGatewayStatus(
                phase = ShizukuConnectionPhase.READY,
                backend = ShizukuBackend.ADB,
            ),
        )
        val commands = mutableListOf<Command>()
        val failUnsuspend = mutableSetOf<String>()
        val disconnectOnSuspend = mutableSetOf<String>()
        override val status: StateFlow<ShizukuGatewayStatus> = mutableStatus

        /** 假网关无需注册进程监听。 */
        override fun start() = Unit

        /** 假网关状态由测试直接控制。 */
        override fun refresh() = Unit

        /** 假网关始终预置授权。 */
        override fun requestPermission() = Unit

        /** 记录命令，并为指定恢复包注入失败。 */
        override suspend fun setPackageSuspended(
            packageName: String,
            userId: Int,
            suspended: Boolean,
        ): SuspendCommandResult {
            commands += Command(packageName, suspended)
            if (suspended && packageName in disconnectOnSuspend) {
                mutableStatus.value = ShizukuGatewayStatus.initial()
                return SuspendCommandResult.failure("Shizuku 连接已中断")
            }
            if (!suspended && packageName in failUnsuspend) {
                return SuspendCommandResult.failure("恢复 $packageName 失败")
            }
            inspector.states[packageName] = if (suspended) {
                PackageSuspensionState.SUSPENDED
            } else {
                PackageSuspensionState.NOT_SUSPENDED
            }
            return SuspendCommandResult.success()
        }
    }

    /** 使用 StateFlow 模拟同步持久化账本。 */
    private class FakeStore : SuspendStateStore {
        private val mutableState = MutableStateFlow(
            SuspendSettings(
                modeEnabled = false,
                releasePending = false,
                managedPackages = emptySet(),
            ),
        )
        override val state: StateFlow<SuspendSettings> = mutableState

        /** 开启模式并取消解除标记。 */
        override fun enableMode(): Boolean = update { it.copy(modeEnabled = true, releasePending = false) }

        /** 进入只恢复状态。 */
        override fun beginRelease(): Boolean = update { it.copy(releasePending = true) }

        /** 认领目标包。 */
        override fun claimPackage(packageName: String): Boolean = update {
            it.copy(managedPackages = it.managedPackages + packageName)
        }

        /** 移除目标包恢复责任。 */
        override fun removeManagedPackage(packageName: String): Boolean = update {
            it.copy(managedPackages = it.managedPackages - packageName)
        }

        /** 管理集合为空时关闭模式。 */
        override fun completeRelease(): Boolean {
            if (mutableState.value.managedPackages.isNotEmpty()) return false
            return update { it.copy(modeEnabled = false, releasePending = false) }
        }

        /** 原子替换测试内存状态。 */
        private fun update(transform: (SuspendSettings) -> SuspendSettings): Boolean {
            mutableState.value = transform(mutableState.value)
            return true
        }
    }

    private companion object {
        const val VIDEO_PACKAGE = "com.example.video"
        const val CHAT_PACKAGE = "com.example.chat"
        const val NEW_PACKAGE = "com.example.newapp"
        const val LAUNCHER_PACKAGE = "com.honor.launcher"
    }
}
