package dev.jdtech.jellyfin.player.beam.voice

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

enum class BeamVoiceState {
    IDLE,
    LISTENING,
    PROCESSING,
    ERROR,
}

class BeamVoiceService(private val context: Context) {
    companion object {
        private const val ERROR_CLIENT = 5
        private const val ERROR_NO_MATCH = 7
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val _state = MutableStateFlow(BeamVoiceState.IDLE)
    val state: StateFlow<BeamVoiceState> = _state.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var onResult: ((String) -> Unit)? = null
    private var originalNotificationVolume: Int = -1

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun canStartListening(): Boolean = audioManager?.mode != AudioManager.MODE_IN_COMMUNICATION

    fun startListening(onTranscript: (String) -> Unit) {
        if (_state.value == BeamVoiceState.LISTENING || !isAvailable()) return
        if (!canStartListening()) {
            _state.value = BeamVoiceState.ERROR
            return
        }

        onResult = onTranscript
        _state.value = BeamVoiceState.LISTENING
        _partialTranscript.value = ""
        muteSystemBeep()

        recognizer?.destroy()
        recognizer =
            SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(
                    object : RecognitionListener {
                        override fun onResults(results: Bundle?) {
                            unmuteSystemBeep()
                            val transcript =
                                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    ?.firstOrNull()
                                    .orEmpty()
                            _state.value = BeamVoiceState.PROCESSING
                            _partialTranscript.value = transcript
                            onResult?.invoke(transcript)
                        }

                        override fun onError(error: Int) {
                            unmuteSystemBeep()
                            _state.value =
                                if (error == ERROR_CLIENT || error == ERROR_NO_MATCH) {
                                    BeamVoiceState.IDLE
                                } else {
                                    Timber.w("VOICE: speech recognition error=%s", error)
                                    BeamVoiceState.ERROR
                                }
                        }

                        override fun onReadyForSpeech(params: Bundle?) = Unit
                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() = Unit
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit

                        override fun onPartialResults(partialResults: Bundle?) {
                            val partial =
                                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    ?.firstOrNull()
                                    .orEmpty()
                            if (partial.isNotEmpty()) {
                                _partialTranscript.value = partial
                            }
                        }
                    },
                )
            }

        recognizer?.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            },
        )
    }

    fun stopListening() {
        recognizer?.stopListening()
        if (_state.value == BeamVoiceState.LISTENING) {
            _state.value = BeamVoiceState.PROCESSING
        }
    }

    fun resetState() {
        _state.value = BeamVoiceState.IDLE
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        _state.value = BeamVoiceState.IDLE
    }

    private fun muteSystemBeep() {
        audioManager?.let { manager ->
            runCatching {
                originalNotificationVolume = manager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
                manager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
            }.onFailure { Timber.w(it, "VOICE: Failed to mute system beep") }
        }
    }

    private fun unmuteSystemBeep() {
        if (originalNotificationVolume == -1) return
        audioManager?.let { manager ->
            runCatching {
                manager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, originalNotificationVolume, 0)
            }.onFailure { Timber.w(it, "VOICE: Failed to restore notification volume") }
        }
        originalNotificationVolume = -1
    }
}
