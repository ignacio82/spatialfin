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

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

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
        voicePreference: String = "male",
    ) {
        if (!isInitialized) {
            Timber.w("TTS: speak ignored because engine is not initialized")
            return
        }
        val locale = resolveLocale(languageHint)
        locale?.let {
            applyVoiceSelection(locale, voicePreference)
        }
        val utteranceId = "spatialfin_chat_${System.currentTimeMillis()}"
        Timber.i(
            "TTS: speak requested id=%s chars=%d locale=%s voicePreference=%s",
            utteranceId,
            text.length,
            locale,
            voicePreference,
        )
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
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
    }

    private fun applyVoiceSelection(locale: Locale, voicePreference: String) {
        val engine = tts ?: return
        val languageResult = engine.setLanguage(locale)
        if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            Timber.w("TTS: Requested locale %s is not supported", locale)
            return
        }

        if (voicePreference == "system") return

        val preferredVoice =
            engine.voices
                ?.asSequence()
                ?.filter { voiceMatchesLocale(it, locale) }
                ?.sortedWith(
                    compareBy<Voice>(
                        { if (voiceMatchesPreference(it, voicePreference)) 0 else 1 },
                        { if (it.isNetworkConnectionRequired) 1 else 0 },
                        { it.latency },
                        { -it.quality },
                    )
                )
                ?.firstOrNull()

        if (preferredVoice != null && voiceMatchesPreference(preferredVoice, voicePreference)) {
            engine.voice = preferredVoice
        }
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

    private fun voiceMatchesLocale(voice: Voice, locale: Locale): Boolean {
        val voiceLocale = voice.locale ?: return false
        return voiceLocale.language.equals(locale.language, ignoreCase = true)
    }

    private fun voiceMatchesPreference(voice: Voice, voicePreference: String): Boolean {
        val haystack =
            buildString {
                append(voice.name.lowercase())
                append(' ')
                append(voice.features.joinToString(" ").lowercase())
            }
        val maleMarkers = listOf("male", "man", "boy", "masculine")
        val femaleMarkers = listOf("female", "woman", "girl", "feminine")
        return when (voicePreference) {
            "male" -> maleMarkers.any(haystack::contains) && femaleMarkers.none(haystack::contains)
            "female" -> femaleMarkers.any(haystack::contains) && maleMarkers.none(haystack::contains)
            else -> true
        }
    }
}
