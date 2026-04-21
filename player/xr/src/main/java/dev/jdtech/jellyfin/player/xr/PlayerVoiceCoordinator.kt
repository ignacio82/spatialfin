package dev.jdtech.jellyfin.player.xr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.media3.common.Player
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.player.xr.voice.RecommendationContext
import dev.jdtech.jellyfin.player.xr.voice.SpatialVoiceService
import dev.jdtech.jellyfin.player.xr.voice.SpatialVoiceSynthesizer
import dev.jdtech.jellyfin.player.xr.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Voice overlay state + TTS/assistant-speech bookkeeping for the XR player.
 *
 * Owns the 14 remembered flags (feedback, conversation history, recommendation context,
 * gesture progress, follow-up window, speech pending/started, etc.) and the four
 * self-contained effects the screen used to inline:
 *   - audio ducking while the mic is open / TTS is speaking
 *   - 4-second auto-dismiss of transient feedback text
 *   - ERROR → IDLE auto-reset after 2 s
 *   - TTS transition state machine (pending → started → playback resume)
 *
 * `requestVoiceCommand` is *not* moved here because its call site depends on ~25
 * screen-local params (`startVoiceCapture(...)` surface). Follow-up auto-start and
 * the pinch detector collector stay in the screen for the same reason. The
 * coordinator still owns the primitives those inline flows call into
 * (`armFollowUpWindow`, `interruptVoiceCommand`, `speakAssistantReply`,
 * `clearFollowUp`, `isVoiceTurnBusy`).
 */
@Stable
internal class PlayerVoiceCoordinator {
    // --- Feedback / UI ---
    var voiceFeedback by mutableStateOf<String?>(null)
    val conversationHistory: SnapshotStateList<Pair<String, String>> = mutableStateListOf()
    var recommendationContext by mutableStateOf<RecommendationContext?>(null)
    var voiceGestureArmingProgress by mutableFloatStateOf(0f)
    var voiceGestureHint by mutableStateOf<String?>(null)
    var shouldStartVoiceCapture by mutableStateOf(false)

    // --- Follow-up window ---
    var followUpPending by mutableStateOf(false)
        private set
    var followUpDeadlineMs by mutableLongStateOf(0L)
        private set

    // --- Misc flags ---
    var characterScanActive by mutableStateOf(false)
    var voiceAssetsRequested by mutableStateOf(false)
    var activeVoiceJob by mutableStateOf<Job?>(null)

    // --- Assistant speech bookkeeping ---
    var resumePlaybackAfterAssistantSpeech by mutableStateOf(false)
        private set
    var assistantSpeechPendingStart by mutableStateOf(false)
        private set
    var assistantSpeechStarted by mutableStateOf(false)
        private set

    fun isVoiceTurnBusy(voiceState: VoiceState, isTtsSpeaking: Boolean): Boolean =
        voiceState != VoiceState.IDLE ||
            activeVoiceJob?.isActive == true ||
            assistantSpeechPendingStart ||
            assistantSpeechStarted ||
            isTtsSpeaking

    fun armFollowUpWindow(reason: String, windowMs: Long) {
        followUpPending = true
        followUpDeadlineMs = System.currentTimeMillis() + windowMs
        voiceGestureHint = "Answer now"
        Timber.i("VOICE: player follow-up armed reason=%s windowMs=%d", reason, windowMs)
    }

    fun clearFollowUp() {
        followUpPending = false
        followUpDeadlineMs = 0L
        voiceGestureHint = null
    }

    fun speakAssistantReply(
        text: String,
        languageHint: String?,
        spokenRepliesEnabled: Boolean,
        assistantVoiceName: String?,
        player: Player,
        tts: SpatialVoiceSynthesizer,
    ) {
        if (!spokenRepliesEnabled || !tts.canSpeak()) {
            Timber.w(
                "VOICE: speakAssistantReply skipped spokenReplies=%b canSpeak=%b chars=%d",
                spokenRepliesEnabled,
                tts.canSpeak(),
                text.length,
            )
            return
        }
        resumePlaybackAfterAssistantSpeech = player.isPlaying
        assistantSpeechPendingStart = true
        assistantSpeechStarted = false
        if (followUpPending) {
            followUpDeadlineMs = 0L
            voiceGestureHint = "Wait for the reply, then answer"
            Timber.i("VOICE: player follow-up scheduled pending spoken reply")
        }
        if (resumePlaybackAfterAssistantSpeech) {
            player.pause()
        }
        tts.speak(text, languageHint, assistantVoiceName)
    }

    /**
     * Cancels the in-flight voice turn. If TTS was speaking with a follow-up pending,
     * caller supplies `onResumeFollowUp` to re-arm listening after a short delay.
     */
    fun interruptVoiceCommand(
        reason: String,
        voiceState: VoiceState,
        isTtsSpeaking: Boolean,
        tts: SpatialVoiceSynthesizer,
        voiceService: SpatialVoiceService,
        followUpListenWindowMs: Long,
        scope: CoroutineScope,
        followUpAutoStartDelayMs: Long,
        onResumeFollowUp: () -> Unit,
    ) {
        if (!isVoiceTurnBusy(voiceState, isTtsSpeaking)) return
        val shouldResumeFollowUp =
            followUpPending &&
                (isTtsSpeaking || assistantSpeechPendingStart || assistantSpeechStarted)
        Timber.i(
            "VOICE: player interrupt requested reason=%s state=%s speaking=%b jobActive=%b resumeFollowUp=%b",
            reason,
            voiceState,
            isTtsSpeaking,
            activeVoiceJob?.isActive == true,
            shouldResumeFollowUp,
        )
        voiceGestureArmingProgress = 0f
        voiceGestureHint = null
        voiceFeedback = null
        assistantSpeechPendingStart = false
        assistantSpeechStarted = false
        resumePlaybackAfterAssistantSpeech = false
        activeVoiceJob?.cancel()
        activeVoiceJob = null
        tts.stop()
        voiceService.cancelListening()
        voiceService.resetState()
        if (shouldResumeFollowUp) {
            armFollowUpWindow("speech-interrupted", followUpListenWindowMs)
            scope.launch {
                delay(followUpAutoStartDelayMs)
                onResumeFollowUp()
            }
        } else {
            clearFollowUp()
        }
    }

    /** Called when the pinch-detector arming progresses but a new gesture hasn't fired yet. */
    fun onGestureIdle(voiceState: VoiceState) {
        voiceGestureArmingProgress = 0f
        if (voiceState != VoiceState.LISTENING) {
            voiceGestureHint = null
        }
    }

    /** Called when a fresh voice turn cancels an in-flight TTS reply. */
    fun cancelPendingSpeech() {
        assistantSpeechPendingStart = false
        assistantSpeechStarted = false
        resumePlaybackAfterAssistantSpeech = false
    }

    /** Internal setters used by the TTS transition effect. */
    internal fun clearAssistantSpeech() {
        assistantSpeechPendingStart = false
        assistantSpeechStarted = false
    }

    internal fun onTtsStarted() {
        assistantSpeechPendingStart = false
        assistantSpeechStarted = true
    }

    internal fun clearResumeFlag(): Boolean {
        val wasResuming = resumePlaybackAfterAssistantSpeech
        resumePlaybackAfterAssistantSpeech = false
        return wasResuming
    }
}

@Composable
internal fun rememberPlayerVoiceCoordinator(
    player: Player,
    voiceService: SpatialVoiceService,
    tts: SpatialVoiceSynthesizer,
    followUpListenWindowMs: Long,
): PlayerVoiceCoordinator {
    val state = remember { PlayerVoiceCoordinator() }
    val voiceState by voiceService.state.collectAsState()
    val isTtsSpeaking by tts.isSpeaking.collectAsState()

    // Audio ducking when listening / TTS is speaking.
    LaunchedEffect(voiceState, isTtsSpeaking) {
        if (voiceState == VoiceState.LISTENING) {
            player.volume = 0.2f
            state.voiceGestureHint = null
        } else if (isTtsSpeaking) {
            player.volume = 0.5f
        } else {
            player.volume = 1.0f
        }
    }

    // Auto-dismiss transient feedback 4 s after TTS finishes.
    LaunchedEffect(state.voiceFeedback, isTtsSpeaking) {
        if (state.voiceFeedback != null && !isTtsSpeaking) {
            delay(4_000L)
            if (!isTtsSpeaking) {
                state.voiceFeedback = null
            }
        }
    }

    // ERROR → IDLE auto-reset after 2 s.
    LaunchedEffect(voiceState) {
        if (voiceState == VoiceState.ERROR) {
            delay(2_000L)
            if (voiceState == VoiceState.ERROR) {
                voiceService.resetState()
            }
        }
    }

    // TTS transition state machine.
    LaunchedEffect(
        isTtsSpeaking,
        state.assistantSpeechPendingStart,
        state.assistantSpeechStarted,
        state.followUpPending,
    ) {
        if (state.assistantSpeechPendingStart && isTtsSpeaking) {
            state.onTtsStarted()
        } else if (state.assistantSpeechPendingStart && !isTtsSpeaking) {
            delay(1_500L)
            if (state.assistantSpeechPendingStart && !isTtsSpeaking) {
                state.clearAssistantSpeech()
                if (state.clearResumeFlag()) {
                    player.play()
                }
                if (state.followUpPending && state.followUpDeadlineMs == 0L) {
                    state.armFollowUpWindow("spoken-reply-did-not-start", followUpListenWindowMs)
                }
            }
        } else if (!isTtsSpeaking && state.assistantSpeechStarted) {
            if (state.clearResumeFlag()) {
                player.play()
            }
            state.clearAssistantSpeech()
            if (state.followUpPending && state.followUpDeadlineMs == 0L) {
                state.armFollowUpWindow("spoken-reply-finished", followUpListenWindowMs)
            }
        }
    }

    return state
}

/** Updates a recommendation-context item snapshot without dropping unrelated state. */
internal fun RecommendationContext.withUpdatedItem(
    updated: SpatialFinItem,
): RecommendationContext =
    if (items.any { it.id == updated.id }) {
        copy(items = items.map { if (it.id == updated.id) updated else it })
    } else {
        this
    }
