package dev.jdtech.jellyfin.player.xr.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay

/**
 * Drives the TTS pending/started/finished transitions that both
 * `HomeVoiceController` and `PlayerVoiceCoordinator` need after calling
 * `tts.speak(...)`. It handles the gap between "speak() returned" and
 * "isTtsSpeaking flipped true", and arms a follow-up listen window when the
 * reply finishes or never starts speaking.
 *
 * The caller owns the state (pendingStart / started / followUp*) and supplies
 * reducer callbacks so this helper can stay screen-agnostic. Lifted out of the
 * two near-identical LaunchedEffect bodies so a bug fix lands once.
 */
@Composable
fun AssistantSpeechTransitionEffect(
    isTtsSpeaking: Boolean,
    pendingStart: Boolean,
    started: Boolean,
    followUpPending: Boolean,
    followUpDeadlineMs: Long,
    spokenReplyGraceMs: Long,
    onTtsStarted: () -> Unit,
    onTtsSkipped: () -> Unit,
    onTtsFinished: () -> Unit,
    onArmFollowUpWindow: (reason: String) -> Unit,
) {
    LaunchedEffect(isTtsSpeaking, pendingStart, started, followUpPending) {
        if (pendingStart && isTtsSpeaking) {
            onTtsStarted()
        } else if (pendingStart && !isTtsSpeaking) {
            delay(spokenReplyGraceMs)
            if (pendingStart && !isTtsSpeaking) {
                onTtsSkipped()
                if (followUpPending && followUpDeadlineMs == 0L) {
                    onArmFollowUpWindow("spoken-reply-did-not-start")
                }
            }
        } else if (!isTtsSpeaking && started) {
            onTtsFinished()
            if (followUpPending && followUpDeadlineMs == 0L) {
                onArmFollowUpWindow("spoken-reply-finished")
            }
        }
    }
}
