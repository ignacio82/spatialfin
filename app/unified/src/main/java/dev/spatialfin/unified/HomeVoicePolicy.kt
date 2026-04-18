package dev.spatialfin.unified

import dev.jdtech.jellyfin.player.xr.voice.VoiceState

/**
 * Pure decision helpers used by [HomeVoiceController]. Extracted so the state
 * machine logic is unit-testable without spinning up Android services.
 */
object HomeVoicePolicy {

    /**
     * A voice turn is "busy" when a recognition / processing / speech cycle is
     * currently in flight. Used to gate new turns and to decide whether an
     * interrupt has anything to act on.
     */
    fun isVoiceTurnBusy(
        voiceState: VoiceState,
        isTtsSpeaking: Boolean,
        isJobActive: Boolean,
        assistantSpeechPendingStart: Boolean,
        assistantSpeechStarted: Boolean,
    ): Boolean {
        return voiceState != VoiceState.IDLE ||
            isJobActive ||
            assistantSpeechPendingStart ||
            assistantSpeechStarted ||
            isTtsSpeaking
    }

    sealed class RequestDecision {
        /** A turn is already in flight; the request should be ignored. */
        data object Busy : RequestDecision()

        /** SpeechRecognizer is not available on this device. */
        data object SpeechRecognitionUnavailable : RequestDecision()

        /** RECORD_AUDIO has not been granted; the caller should prompt for it. */
        data object NeedsAudioPermission : RequestDecision()

        /** Safe to start listening. */
        data object Proceed : RequestDecision()
    }

    fun decideRequest(
        isBusy: Boolean,
        isRecognitionAvailable: Boolean,
        hasAudioPermission: Boolean,
    ): RequestDecision = when {
        isBusy -> RequestDecision.Busy
        !isRecognitionAvailable -> RequestDecision.SpeechRecognitionUnavailable
        !hasAudioPermission -> RequestDecision.NeedsAudioPermission
        else -> RequestDecision.Proceed
    }

    /**
     * After an interrupt, the controller re-arms the follow-up window only if
     * a follow-up was pending AND speech (planned, in-progress, or fully
     * playing) was the thing that got cancelled.
     */
    fun shouldResumeFollowUpAfterInterrupt(
        followUpPending: Boolean,
        isTtsSpeaking: Boolean,
        assistantSpeechPendingStart: Boolean,
        assistantSpeechStarted: Boolean,
    ): Boolean = followUpPending &&
        (isTtsSpeaking || assistantSpeechPendingStart || assistantSpeechStarted)

    /** "…" is the in-progress indicator; give it a long ceiling. */
    private const val IN_PROGRESS_FEEDBACK = "…"
    private const val IN_PROGRESS_FEEDBACK_TIMEOUT_MS = 60_000L
    private const val FEEDBACK_PER_CHAR_MS = 55L
    private const val FEEDBACK_MIN_TIMEOUT_MS = 4_000L
    private const val FEEDBACK_MAX_TIMEOUT_MS = 10_000L

    /**
     * How long to leave a feedback message on screen before auto-clearing.
     * Empty / null callers should not invoke this (no feedback to clear).
     */
    fun feedbackTimeoutMs(feedback: String): Long {
        if (feedback == IN_PROGRESS_FEEDBACK) return IN_PROGRESS_FEEDBACK_TIMEOUT_MS
        return (feedback.length * FEEDBACK_PER_CHAR_MS)
            .coerceIn(FEEDBACK_MIN_TIMEOUT_MS, FEEDBACK_MAX_TIMEOUT_MS)
    }
}
