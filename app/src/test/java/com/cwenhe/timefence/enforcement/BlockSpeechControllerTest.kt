package com.cwenhe.timefence.enforcement

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证 TTS 异步初始化、服务重连和失败降级不会污染后续播报。 */
class BlockSpeechControllerTest {
    /** 验证旧引擎回调晚到时不会让新引擎提前消费或丢失待播文本。 */
    @Test
    fun `停止后的旧初始化回调不会污染新引擎`() {
        val factory = FakeSpeechEngineFactory()
        val controller = controller(factory)

        controller.speak(feedback("第一条"), ruleEnabled = true)
        val first = factory.engines.single()
        controller.stop()
        controller.speak(feedback("第二条"), ruleEnabled = true)
        val second = factory.engines.last()

        first.completeInitialization(ENGINE_SUCCESS)
        assertTrue(second.spokenTexts.isEmpty())
        second.completeInitialization(ENGINE_SUCCESS)

        assertEquals(listOf("第二条"), second.spokenTexts)
        assertTrue(first.shutdownCalled)
    }

    /** 验证初始化失败会关闭当前引擎且不会抛出到拦截主流程。 */
    @Test
    fun `初始化失败安全降级为无语音`() {
        val factory = FakeSpeechEngineFactory()
        val controller = controller(factory)

        controller.speak(feedback("提示"), ruleEnabled = true)
        val engine = factory.engines.single()
        engine.completeInitialization(ENGINE_ERROR)

        assertTrue(engine.shutdownCalled)
        assertTrue(engine.spokenTexts.isEmpty())
    }

    /** 创建使用固定设置和单调测试时钟的控制器。 */
    private fun controller(factory: FakeSpeechEngineFactory): BlockSpeechController = BlockSpeechController(
        settingsProvider = { SpeechSettings(enabled = true, language = SpeechLanguage.SYSTEM) },
        engineFactory = factory,
        throttle = SpeechThrottle(clockMillis = { 1_000L }),
    )

    /** 创建只包含测试所需字段的拦截反馈。 */
    private fun feedback(text: String): BlockFeedback = BlockFeedback(
        ruleId = 1,
        ruleName = "专注",
        packageName = "video.app",
        appLabel = "视频",
        untilText = "10:00",
        text = text,
    )

    /** 保存每次创建的可控假引擎，供测试主动触发异步回调。 */
    private class FakeSpeechEngineFactory : SpeechEngineFactory {
        val engines = mutableListOf<FakeSpeechEngine>()

        /** 创建尚未完成初始化的假引擎。 */
        override fun create(onInitialized: (Int) -> Unit): SpeechEngine = FakeSpeechEngine(onInitialized)
            .also(engines::add)
    }

    /** 记录语言、朗读与释放调用，并允许测试控制初始化完成顺序。 */
    private class FakeSpeechEngine(
        private val onInitialized: (Int) -> Unit,
    ) : SpeechEngine {
        val spokenTexts = mutableListOf<String>()
        var shutdownCalled = false

        /** 主动完成一次异步初始化。 */
        fun completeInitialization(status: Int) {
            onInitialized(status)
        }

        /** 测试引擎始终声明目标语言可用。 */
        override fun setLanguage(locale: Locale): Int = LANGUAGE_AVAILABLE

        /** 记录控制器真正提交的朗读文本。 */
        override fun speak(text: String) {
            spokenTexts += text
        }

        /** 测试不需要记录停止中的 utterance。 */
        override fun stop() = Unit

        /** 记录引擎资源已经释放。 */
        override fun shutdown() {
            shutdownCalled = true
        }
    }

    private companion object {
        const val ENGINE_SUCCESS = 0
        const val ENGINE_ERROR = -1
        const val LANGUAGE_AVAILABLE = 0
    }
}
