package dev.spatialfin.companion.wear.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.spatialfin.companion.protocol.WearProtocolCodec
import dev.spatialfin.companion.protocol.WearProtocolPaths
import dev.spatialfin.companion.protocol.WearVoiceQuery
import dev.spatialfin.companion.wear.transport.WearMessageClientRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

sealed interface VoiceRecordingState {
    data object Idle : VoiceRecordingState
    data object Connecting : VoiceRecordingState
    data class Recording(val amplitudeRms: Float) : VoiceRecordingState
    data class Transcribed(val transcript: String) : VoiceRecordingState
    data class Completed(val message: String) : VoiceRecordingState
    data class Error(val error: String) : VoiceRecordingState
    data object PermissionRequired : VoiceRecordingState
}

/**
 * Wrist voice commander.
 *
 * Recognition runs **on the watch** and only the transcript crosses the Data Layer.
 * The original plan streamed 16 kHz PCM over a `ChannelClient`, but the host's
 * `SpatialVoiceService` wraps Android's `SpeechRecognizer`, which offers no way to
 * feed it an external buffer — the audio had nowhere to land. Every LLM step still
 * happens on the paired host, so "no inference on the watch" continues to hold, and
 * a transcript is a few hundred bytes rather than ~160 KB for a five-second utterance.
 */
@Singleton
class WearVoiceCapture @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageClientRepo: WearMessageClientRepository,
) {

    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var recognizer: SpeechRecognizer? = null

    private val _recordingState = MutableStateFlow<VoiceRecordingState>(VoiceRecordingState.Idle)
    val recordingState: StateFlow<VoiceRecordingState> = _recordingState.asStateFlow()

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Must be called on the main thread — `SpeechRecognizer` requires it. */
    fun startCapture() {
        if (!hasMicPermission()) {
            _recordingState.value = VoiceRecordingState.PermissionRequired
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _recordingState.value = VoiceRecordingState.Error("Speech recognition unavailable on this watch")
            return
        }
        if (recognizer != null) return

        _recordingState.value = VoiceRecordingState.Connecting
        val newRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = newRecognizer
        newRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _recordingState.value = VoiceRecordingState.Recording(amplitudeRms = 0f)
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Normalize the recognizer's dB scale (roughly -2f..10f) for the mic pulse.
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                _recordingState.value = VoiceRecordingState.Recording(amplitudeRms = normalized)
            }

            override fun onResults(results: Bundle?) {
                val transcript = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                teardown()
                if (transcript.isBlank()) {
                    _recordingState.value = VoiceRecordingState.Error("Didn't catch that")
                } else {
                    _recordingState.value = VoiceRecordingState.Transcribed(transcript)
                    sendTranscript(transcript)
                }
            }

            override fun onError(error: Int) {
                teardown()
                _recordingState.value = VoiceRecordingState.Error(describeError(error))
            }

            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onEndOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        runCatching { newRecognizer.startListening(intent) }.onFailure {
            teardown()
            _recordingState.value = VoiceRecordingState.Error(it.message ?: "Voice capture failed")
        }
    }

    private fun sendTranscript(transcript: String) {
        scope.launch {
            val feedback = withContext(Dispatchers.IO) {
                runCatching {
                    val node = messageClientRepo.getConnectedHostNode()
                        ?: error("No connected host device")
                    val payload = WearProtocolCodec.encodeVoiceQuery(
                        WearVoiceQuery(transcript = transcript, spokenAtEpochMs = System.currentTimeMillis()),
                    )
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, WearProtocolPaths.PATH_VOICE_QUERY, payload)
                        .await()
                    "Sent to ${node.displayName}"
                }.getOrElse {
                    Timber.w(it, "WearVoiceCapture: failed to relay transcript")
                    null
                }
            }
            _recordingState.value = if (feedback != null) {
                VoiceRecordingState.Completed(feedback)
            } else {
                VoiceRecordingState.Error("No host reachable")
            }
        }
    }

    fun stopCapture() {
        runCatching { recognizer?.stopListening() }
        teardown()
        _recordingState.value = VoiceRecordingState.Idle
    }

    private fun teardown() {
        runCatching {
            recognizer?.cancel()
            recognizer?.destroy()
        }
        recognizer = null
    }

    private fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network unavailable"
        else -> "Voice capture failed"
    }
}
