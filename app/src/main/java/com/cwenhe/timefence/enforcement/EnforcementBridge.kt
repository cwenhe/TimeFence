package com.cwenhe.timefence.enforcement

import android.content.Context
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 在普通 Android 组件和系统托管的无障碍服务实例之间传递边界检查请求。
 *
 * 弱引用不会延长服务生命周期；服务缺席时请求会落入持久化补检标记。
 */
object EnforcementBridge {
    private val bridgeLock = Any()
    private var serviceReference: WeakReference<BlockAccessibilityService>? = null
    private val mutableServiceConnected = MutableStateFlow(false)

    /** 当前进程是否持有已连接的无障碍服务实例。 */
    val serviceConnected: StateFlow<Boolean> = mutableServiceConnected.asStateFlow()

    /** 注册刚连接的服务，并消费此前持久化的补检标记。 */
    fun connect(service: BlockAccessibilityService, context: Context): Boolean =
        synchronized(bridgeLock) {
            serviceReference = WeakReference(service)
            mutableServiceConnected.value = true
            BoundaryCheckStore(context).consumePending()
        }

    /** 仅当销毁的是当前实例时清理引用，避免旧实例误清除新连接。 */
    fun disconnect(service: BlockAccessibilityService) {
        synchronized(bridgeLock) {
            if (serviceReference?.get() === service) {
                serviceReference?.clear()
                serviceReference = null
                mutableServiceConnected.value = false
            }
        }
    }

    /**
     * 请求当前服务检查活动窗口；没有可用实例时同步写入待补检状态。
     *
     * @return `true` 表示请求已交给已连接服务，`false` 表示已持久化等待重连。
     */
    fun requestBoundaryCheck(context: Context): Boolean {
        val service = synchronized(bridgeLock) {
            val connectedService = serviceReference?.get()
            if (connectedService == null) {
                mutableServiceConnected.value = false
                BoundaryCheckStore(context).markPending()
            }
            connectedService
        }
        service?.checkActiveWindowAtBoundary() ?: return false
        return true
    }
}
