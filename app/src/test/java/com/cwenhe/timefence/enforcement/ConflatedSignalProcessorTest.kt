package com.cwenhe.timefence.enforcement

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflatedSignalProcessorTest {
    /** 验证事件风暴只能并行运行一个处理任务并最多合并一个待处理信号。 */
    @Test
    fun `连续请求不会创建并发处理任务`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val running = AtomicInteger(0)
        val maxRunning = AtomicInteger(0)
        val processed = AtomicInteger(0)
        val processor = ConflatedSignalProcessor(scope) {
            val current = running.incrementAndGet()
            maxRunning.updateAndGet { value -> maxOf(value, current) }
            processed.incrementAndGet()
            started.complete(Unit)
            release.await()
            running.decrementAndGet()
        }

        processor.request()
        started.await()
        repeat(1000) { processor.request() }
        delay(50)
        assertEquals(1, maxRunning.get())
        release.complete(Unit)
        while (processed.get() < 2) delay(10)
        assertEquals(2, processed.get())
        assertTrue(processor.request())
        processor.close()
        scope.cancel()
    }

    /** 验证一次处理异常只被上报，不会永久终止后续窗口信号。 */
    @Test
    fun `处理异常后消费者继续工作`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val errorReported = CompletableDeferred<Unit>()
        val recovered = CompletableDeferred<Unit>()
        var attempts = 0
        val processor = ConflatedSignalProcessor(
            scope = scope,
            onError = { _ -> errorReported.complete(Unit) },
            process = {
                attempts += 1
                if (attempts == 1) error("测试异常")
                recovered.complete(Unit)
            },
        )

        processor.request()
        errorReported.await()
        processor.request()
        recovered.await()

        assertEquals(2, attempts)
        processor.close()
        scope.cancel()
    }
}
