package dev.jdtech.jellyfin.player.xr.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.Locale

class SpatialVoiceSynthesizer(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private var cachedVoiceName: String? = null
    private var cachedVoice: Voice? = null

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.let {
                val result = it.setLanguage(Locale.getDefault())
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Timber.w("TTS: Language not supported")
                } else {
                    isInitialized = true
                    _isReady.value = true
                    it.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            Timber.i("TTS: utterance started id=%s", utteranceId)
                            _isSpeaking.value = true
                        }

                        override fun onDone(utteranceId: String?) {
                            Timber.i("TTS: utterance finished id=%s", utteranceId)
                            _isSpeaking.value = false
                        }

                        @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId)"), level = DeprecationLevel.WARNING)
                        override fun onError(utteranceId: String?) {
                            Timber.w("TTS: utterance failed id=%s", utteranceId)
                            _isSpeaking.value = false
                        }
                    })
                }
            }
        } else {
            Timber.e("TTS: Initialization failed")
        }
    }

    fun speak(
        text: String,
        languageHint: String? = null,
        voiceName: String? = null,
        queueMode: Int = TextToSpeech.QUEUE_FLUSH,
    ) {
        if (!isInitialized) {
            Timber.w("TTS: speak ignored because engine is not initialized")
            return
        }
        val engine = tts ?: return
        val applied = applyVoiceSelection(engine, voiceName)
        if (!applied) {
            resolveLocale(languageHint)?.let { engine.setLanguage(it) }
        }
        val utteranceId = "spatialfin_chat_${System.currentTimeMillis()}"
        Timber.i(
            "TTS: speak requested id=%s chars=%d voiceName=%s appliedVoice=%b mode=%d",
            utteranceId,
            text.length,
            voiceName,
            applied,
            queueMode,
        )
        engine.speak(text, queueMode, null, utteranceId)
    }

    fun canSpeak(): Boolean = isInitialized && tts != null

    fun stop() {
        if (!isInitialized) return
        tts?.stop()
        _isSpeaking.value = false
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        cachedVoice = null
        cachedVoiceName = null
        _isReady.value = false
    }

    /**
     * Snapshot of the voices the underlying TTS engine exposes. Empty until
     * [isReady] flips true. Safe to call from the UI layer — the enumeration
     * itself is a Binder call, so avoid invoking on a hot path.
     */
    fun availableVoices(): List<Voice> {
        val engine = tts ?: return emptyList()
        return runCatching { engine.voices }.getOrNull()?.filterNotNull().orEmpty()
    }

    private fun applyVoiceSelection(engine: TextToSpeech, voiceName: String?): Boolean {
        if (voiceName.isNullOrBlank()) return false
        val voice = resolveVoice(engine, voiceName) ?: return false
        return runCatching { engine.voice = voice }.isSuccess
    }

    private fun resolveVoice(engine: TextToSpeech, voiceName: String): Voice? {
        cachedVoice?.takeIf { cachedVoiceName == voiceName }?.let { return it }
        val match = runCatching { engine.voices }.getOrNull()
            ?.firstOrNull { it?.name == voiceName }
            ?: return null
        cachedVoiceName = voiceName
        cachedVoice = match
        return match
    }

    private fun resolveLocale(languageHint: String?): Locale? {
        val normalized = languageHint?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) return Locale.getDefault()

        val direct =
            Locale.forLanguageTag(normalized.replace('_', '-'))
                .takeIf { it.language.isNotBlank() && it.language != "und" }
        if (direct != null) return direct

        val keywordMap =
            mapOf(
                "english" to Locale.ENGLISH,
                "japanese" to Locale.JAPANESE,
                "ja" to Locale.JAPANESE,
                "spanish" to Locale("es"),
                "es" to Locale("es"),
                "french" to Locale.FRENCH,
                "fr" to Locale.FRENCH,
                "german" to Locale.GERMAN,
                "de" to Locale.GERMAN,
                "italian" to Locale.ITALIAN,
                "it" to Locale.ITALIAN,
                "korean" to Locale.KOREAN,
                "ko" to Locale.KOREAN,
                "chinese" to Locale.CHINESE,
                "zh" to Locale.CHINESE,
                "portuguese" to Locale("pt"),
                "pt" to Locale("pt"),
                "russian" to Locale("ru"),
                "ru" to Locale("ru"),
            )
        return keywordMap.entries.firstOrNull { normalized.contains(it.key) }?.value ?: Locale.getDefault()
    }
}
