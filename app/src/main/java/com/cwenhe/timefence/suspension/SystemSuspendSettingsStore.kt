package com.cwenhe.timefence.suspension

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 系统暂停模式和本地恢复责任的持久快照。 */
data class SuspendSettings(
    val modeEnabled: Boolean,
    val releasePending: Boolean,
    val managedPackages: Set<String>,
)

/** 允许协调器在测试中替换持久存储。 */
interface SuspendStateStore {
    val state: StateFlow<SuspendSettings>

    /** 开启高级模式并取消旧的解除请求。 */
    fun enableMode(): Boolean

    /** 持久化解除请求，后续校正不得开始新暂停。 */
    fun beginRelease(): Boolean

    /** 在远端命令前认领一个原本未暂停的包。 */
    fun claimPackage(packageName: String): Boolean

    /** 在恢复成功或暂停失败后移除本地恢复责任。 */
    fun removeManagedPackage(packageName: String): Boolean

    /** 在所有包恢复后原子关闭模式并结束解除状态。 */
    fun completeRelease(): Boolean
}

/** 使用同步 SharedPreferences 写入保证命令与恢复责任的先后关系。 */
class SystemSuspendSettingsStore(context: Context) : SuspendStateStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val storeLock = Any()
    private val mutableState = MutableStateFlow(read(preferences))

    override val state: StateFlow<SuspendSettings> = mutableState.asStateFlow()

    /** 开启高级模式并取消旧的解除请求。 */
    override fun enableMode(): Boolean = update { current ->
        current.copy(modeEnabled = true, releasePending = false)
    }

    /** 标记进入只恢复不新增暂停的紧急状态。 */
    override fun beginRelease(): Boolean = update { current ->
        current.copy(releasePending = true)
    }

    /** 在执行暂停命令前同步认领目标包。 */
    override fun claimPackage(packageName: String): Boolean {
        if (!PackageNameValidator.isValid(packageName)) return false
        return update { current ->
            current.copy(managedPackages = current.managedPackages + packageName)
        }
    }

    /** 在不再承担恢复责任时同步移除目标包。 */
    override fun removeManagedPackage(packageName: String): Boolean = update { current ->
        current.copy(managedPackages = current.managedPackages - packageName)
    }

    /** 仅在管理集合为空时原子结束高级模式。 */
    override fun completeRelease(): Boolean = synchronized(storeLock) {
        if (mutableState.value.managedPackages.isNotEmpty()) return@synchronized false
        updateLocked(
            SuspendSettings(
                modeEnabled = false,
                releasePending = false,
                managedPackages = emptySet(),
            ),
        )
    }

    /** 在单一锁内计算并持久化新状态。 */
    private fun update(transform: (SuspendSettings) -> SuspendSettings): Boolean =
        synchronized(storeLock) {
            updateLocked(transform(mutableState.value))
        }

    /** 将完整快照一次提交，成功后再通知观察者。 */
    private fun updateLocked(updated: SuspendSettings): Boolean {
        val committed = preferences.edit()
            .putBoolean(KEY_MODE_ENABLED, updated.modeEnabled)
            .putBoolean(KEY_RELEASE_PENDING, updated.releasePending)
            .putStringSet(KEY_MANAGED_PACKAGES, updated.managedPackages)
            .commit()
        if (committed) mutableState.value = updated
        return committed
    }

    private companion object {
        /** 从磁盘读取设置，并过滤旧版本可能留下的非法包名。 */
        fun read(preferences: SharedPreferences): SuspendSettings = SuspendSettings(
            modeEnabled = preferences.getBoolean(KEY_MODE_ENABLED, false),
            releasePending = preferences.getBoolean(KEY_RELEASE_PENDING, false),
            managedPackages = preferences.getStringSet(KEY_MANAGED_PACKAGES, emptySet())
                .orEmpty()
                .filterTo(sortedSetOf(), PackageNameValidator::isValid),
        )

        const val PREFERENCES_NAME = "system_suspend_settings"
        const val KEY_MODE_ENABLED = "mode_enabled"
        const val KEY_RELEASE_PENDING = "release_pending"
        const val KEY_MANAGED_PACKAGES = "managed_packages"
    }
}
