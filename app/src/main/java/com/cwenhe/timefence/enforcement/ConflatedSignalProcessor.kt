package com.cwenhe.timefence.enforcement

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * 将高频窗口事件合并为单一消费者，保证最多一个规则检查任务在途。
 *
 * @param scope 绑定消费者生命周期的协程作用域。
 * @param onError 处理单次任务异常且不中断后续事件的回调。
 * @param process 每个合并信号执行的规则检查。
 */
internal class ConflatedSignalProcessor(
    scope: CoroutineScope,
    private val onError: (Throwable) -> Unit = {},
    process: suspend () -> Unit,
) {
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val worker = scope.launch {
        for (ignored in signals) {
            try {
                process()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onError(error)
            }
        }
    }

    /** 提交最新检查请求，已有待处理请求时不继续堆积事件。 */
    fun request(): Boolean = signals.trySend(Unit).isSuccess

    /** 关闭信号通道并取消唯一消费者，避免服务销毁后继续运行。 */
    fun close() {
        signals.close()
        worker.cancel()
    }
}
