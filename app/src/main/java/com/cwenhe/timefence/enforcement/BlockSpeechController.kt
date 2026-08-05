package com.cwenhe.timefence.enforcement

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import java.util.Locale

/** 控制服务生命周期内的系统 TTS，失败时只降级为视觉反馈。 */
class BlockSpeechController internal constructor(
    private val settingsProvider: () -> SpeechSettings,
    private val engineFactory: SpeechEngineFactory,
    private val throttle: SpeechThrottle = SpeechThrottle(),
) {
    /** 创建使用应用设置和 Android 系统 TTS 的生产控制器。 */
    constructor(context: Context, settingsStore: SpeechSettingsStore) : this(
        settingsProvider = settingsStore::read,
        engineFactory = AndroidSpeechEngineFactory(context),
    )

    private var speechEngine: SpeechEngine? = null
    private var initialized = false
    private var pendingText: String? = null
    private var engineGeneration = 0L

    /** 按全局和规则开关提交一次播报，并对窗口事件风暴做十秒节流。 */
    fun speak(feedback: BlockFeedback, ruleEnabled: Boolean) {
        val settings = settingsProvider()
        if (!settings.enabled || settings.language == SpeechLanguage.OFF || !ruleEnabled) return
        val key = "${feedback.ruleId}:${feedback.packageName}:${feedback.text}"
        if (!throttle.shouldSpeak(key)) return
        pendingText = feedback.text
        ensureInitialized()
        if (initialized) flushPending(settings.language)
    }

    /** 停止未完成播报并释放引擎，服务销毁后不保留 Context 或线程引用。 */
    fun stop() {
        engineGeneration += 1
        pendingText = null
        runCatching { speechEngine?.stop() }
        runCatching { speechEngine?.shutdown() }
        speechEngine = null
        initialized = false
    }

    /** 延迟创建 TTS 引擎，避免无障碍服务连接时阻塞窗口处理。 */
    private fun ensureInitialized() {
        if (speechEngine != null) return
        val generation = ++engineGeneration
        speechEngine = runCatching {
            engineFactory.create { status -> handleInitialization(generation, status) }
        }.getOrElse {
            pendingText = null
            null
        }
    }

    /** 只接受当前代际的初始化回调，旧服务回调不得修改新引擎状态。 */
    private fun handleInitialization(generation: Long, status: Int) {
        if (generation != engineGeneration) return
        initialized = status == ENGINE_SUCCESS
        if (initialized) {
            flushPending(settingsProvider().language)
        } else {
            pendingText = null
            runCatching { speechEngine?.shutdown() }
            speechEngine = null
        }
    }

    /** 在引擎已就绪时设置语言并朗读最新一条提示。 */
    private fun flushPending(language: SpeechLanguage) {
        val engine = speechEngine ?: return
        val text = pendingText ?: return
        val locale = when (language) {
            SpeechLanguage.SYSTEM -> Locale.getDefault()
            SpeechLanguage.ZH_CN -> Locale.SIMPLIFIED_CHINESE
            SpeechLanguage.OFF -> return
        }
        val languageStatus = runCatching { engine.setLanguage(locale) }.getOrElse {
            pendingText = null
            return
        }
        if (languageStatus < LANGUAGE_AVAILABLE) {
            pendingText = null
            return
        }
        pendingText = null
        runCatching { engine.speak(text) }
    }

    private companion object {
        const val ENGINE_SUCCESS = TextToSpeech.SUCCESS
        const val LANGUAGE_AVAILABLE = TextToSpeech.LANG_AVAILABLE
    }
}

/** 抽象控制器使用的最小 TTS 操作，使异步生命周期能够在 JVM 中验证。 */
internal interface SpeechEngine {
    /** 切换本次播报语言并返回 Android TTS 兼容状态码。 */
    fun setLanguage(locale: Locale): Int

    /** 使用替换队列朗读给定文本。 */
    fun speak(text: String)

    /** 停止当前 utterance。 */
    fun stop()

    /** 释放底层 TTS 资源。 */
    fun shutdown()
}

/** 创建异步初始化的语音引擎，并通过状态码报告初始化结果。 */
internal fun interface SpeechEngineFactory {
    /** 创建一台新引擎，回调可能在稍后到达。 */
    fun create(onInitialized: (Int) -> Unit): SpeechEngine
}

/** 使用应用 Context 创建 Android TTS 适配器。 */
private class AndroidSpeechEngineFactory(context: Context) : SpeechEngineFactory {
    private val appContext = context.applicationContext

    /** 创建由系统 TextToSpeech 提供能力的引擎。 */
    override fun create(onInitialized: (Int) -> Unit): SpeechEngine =
        AndroidSpeechEngine(appContext, onInitialized)
}

/** 将 Android TextToSpeech API 适配为控制器所需的最小接口。 */
private class AndroidSpeechEngine(
    context: Context,
    onInitialized: (Int) -> Unit,
) : SpeechEngine {
    private val delegate = TextToSpeech(context, onInitialized)

    /** 把语言选择交给系统 TTS 引擎。 */
    override fun setLanguage(locale: Locale): Int = delegate.setLanguage(locale)

    /** 用 QUEUE_FLUSH 只保留最新一次拦截提示。 */
    override fun speak(text: String) {
        delegate.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    /** 停止系统引擎当前正在朗读的内容。 */
    override fun stop() {
        delegate.stop()
    }

    /** 释放系统引擎持有的服务连接和线程。 */
    override fun shutdown() {
        delegate.shutdown()
    }

    private companion object {
        const val UTTERANCE_ID = "timefence-block-feedback"
    }
}

/** 全局语音设置，可由设置页修改并由无障碍服务实时读取。 */
data class SpeechSettings(
    val enabled: Boolean,
    val language: SpeechLanguage,
) {
    /** 切换总开关；从关闭语言启用时恢复系统语言，避免出现开启但不播报。 */
    fun withEnabled(value: Boolean): SpeechSettings = copy(
        enabled = value,
        language = if (value && language == SpeechLanguage.OFF) SpeechLanguage.SYSTEM else language,
    )
}

/** 表示系统默认、中文普通话或关闭三种语音语言策略。 */
enum class SpeechLanguage {
    SYSTEM,
    ZH_CN,
    OFF,
}

/** 使用应用私有 SharedPreferences 持久化全局语音开关和语言。 */
class SpeechSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** 读取当前全局语音设置，未知枚举值安全回退到系统语言。 */
    fun read(): SpeechSettings = SpeechSettings(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        language = runCatching {
            SpeechLanguage.valueOf(preferences.getString(KEY_LANGUAGE, SpeechLanguage.SYSTEM.name).orEmpty())
        }.getOrDefault(SpeechLanguage.SYSTEM),
    )

    /** 保存全局语音开关和语言选择，下一次拦截立即生效。 */
    fun write(settings: SpeechSettings) {
        preferences.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_LANGUAGE, settings.language.name)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "speech_settings"
        const val KEY_ENABLED = "enabled"
        const val KEY_LANGUAGE = "language"
    }
}

/** 以单调时钟为基准控制同一规则提示的最短播报间隔。 */
internal class SpeechThrottle(
    private val clockMillis: () -> Long = SystemClock::elapsedRealtime,
    private val cooldownMillis: Long = 10_000L,
) {
    private val lastSpokenAt = mutableMapOf<String, Long>()

    /** 判断给定反馈是否已经超过冷却时间并记录本次播报。 */
    fun shouldSpeak(key: String): Boolean {
        val now = clockMillis()
        val lastAt = lastSpokenAt[key]
        if (lastAt != null && now - lastAt < cooldownMillis) return false
        lastSpokenAt.entries.removeAll { entry -> now - entry.value >= cooldownMillis }
        lastSpokenAt[key] = now
        return true
    }
}
