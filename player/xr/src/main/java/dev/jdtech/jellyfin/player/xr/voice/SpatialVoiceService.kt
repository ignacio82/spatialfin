package dev.jdtech.jellyfin.player.xr.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

enum class VoiceState {
    IDLE,
    LISTENING,
    PROCESSING,
    ERROR,
}

class SpatialVoiceService(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var onResult: ((String) -> Unit)? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun canStartListening(): Boolean {
        return audioManager?.mode != AudioManager.MODE_IN_COMMUNICATION
    }

    fun startListening(onTranscript: (String) -> Unit) {
        if (_state.value == VoiceState.LISTENING || !isAvailable()) return
        if (!canStartListening()) {
            _state.value = VoiceState.ERROR
            return
        }

        onResult = onTranscript
        _state.value = VoiceState.LISTENING

        recognizer?.destroy()
        recognizer =
            SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(
                    object : RecognitionListener {
                        override fun onResults(results: Bundle?) {
                            val transcript =
                                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    ?.firstOrNull()
                                    .orEmpty()
                            _state.value = VoiceState.PROCESSING
                            onResult?.invoke(transcript)
                        }

                        override fun onError(error: Int) {
                            Timber.w("VOICE: speech recognition error=%s", error)
                            _state.value = VoiceState.ERROR
                        }

                        override fun onReadyForSpeech(params: Bundle?) = Unit

                        override fun onBeginningOfSpeech() = Unit

                        override fun onRmsChanged(rmsdB: Float) = Unit

                        override fun onBufferReceived(buffer: ByteArray?) = Unit

                        override fun onEndOfSpeech() = Unit

                        override fun onPartialResults(partialResults: Bundle?) = Unit

                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    }
                )
            }

        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
        recognizer?.startListening(intent)
    }

    fun stopListening() {
        recognizer?.stopListening()
        if (_state.value == VoiceState.LISTENING) {
            _state.value = VoiceState.PROCESSING
        }
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        _state.value = VoiceState.IDLE
    }

    fun resetState() {
        _state.value = VoiceState.IDLE
    }
}
