package dev.spatialfin.unified

import dev.jdtech.jellyfin.player.xr.voice.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeVoicePolicyTest {

    // -----------------------------------------------------------------
    // isVoiceTurnBusy
    // -----------------------------------------------------------------

    @Test
    fun `idle with no flags is not busy`() {
        assertFalse(
            HomeVoicePolicy.isVoiceTurnBusy(
                voiceState = VoiceState.IDLE,
                isTtsSpeaking = false,
                isJobActive = false,
                assistantSpeechPendingStart = false,
                assistantSpeechStarted = false,
            )
        )
    }

    @Test
    fun `non-idle state is busy regardless of other flags`() {
        for (state in listOf(VoiceState.LISTENING, VoiceState.PROCESSING, VoiceState.ERROR)) {
            assertTrue(
                "expected busy for state=$state",
                HomeVoicePolicy.isVoiceTurnBusy(
                    voiceState = state,
                    isTtsSpeaking = false,
                    isJobActive = false,
                    assistantSpeechPendingStart = false,
                    assistantSpeechStarted = false,
                )
            )
        }
    }

    @Test
    fun `tts speaking marks turn as busy even when recognizer is idle`() {
        assertTrue(
            HomeVoicePolicy.isVoiceTurnBusy(
                voiceState = VoiceState.IDLE,
                isTtsSpeaking = true,
                isJobActive = false,
                assistantSpeechPendingStart = false,
                assistantSpeechStarted = false,
            )
        )
    }

    @Test
    fun `active background job marks turn as busy`() {
        assertTrue(
            HomeVoicePolicy.isVoiceTurnBusy(
                voiceState = VoiceState.IDLE,
                isTtsSpeaking = false,
                isJobActive = true,
                assistantSpeechPendingStart = false,
                assistantSpeechStarted = false,
            )
        )
    }

    @Test
    fun `pending or started assistant speech marks turn as busy`() {
        assertTrue(
            HomeVoicePolicy.isVoiceTurnBusy(
                voiceState = VoiceState.IDLE,
                isTtsSpeaking = false,
                isJobActive = false,
                assistantSpeechPendingStart = true,
                assistantSpeechStarted = false,
            )
        )
        assertTrue(
            HomeVoicePolicy.isVoiceTurnBusy(
                voiceState = VoiceState.IDLE,
                isTtsSpeaking = false,
                isJobActive = false,
                assistantSpeechPendingStart = false,
                assistantSpeechStarted = true,
            )
        )
    }

    // -----------------------------------------------------------------
    // decideRequest
    // -----------------------------------------------------------------

    @Test
    fun `request decision is busy when in flight`() {
        assertEquals(
            HomeVoicePolicy.RequestDecision.Busy,
            HomeVoicePolicy.decideRequest(
                isBusy = true,
                isRecognitionAvailable = true,
                hasAudioPermission = true,
            )
        )
    }

    @Test
    fun `busy takes precedence over missing permission and unavailable recognizer`() {
        assertEquals(
            HomeVoicePolicy.RequestDecision.Busy,
            HomeVoicePolicy.decideRequest(
                isBusy = true,
                isRecognitionAvailable = false,
                hasAudioPermission = false,
            )
        )
    }

    @Test
    fun `unavailable recognizer reported when not busy`() {
        assertEquals(
            HomeVoicePolicy.RequestDecision.SpeechRecognitionUnavailable,
            HomeVoicePolicy.decideRequest(
                isBusy = false,
                isRecognitionAvailable = false,
                hasAudioPermission = true,
            )
        )
    }

    @Test
    fun `missing audio permission reported when recognizer is available`() {
        assertEquals(
            HomeVoicePolicy.RequestDecision.NeedsAudioPermission,
            HomeVoicePolicy.decideRequest(
                isBusy = false,
                isRecognitionAvailable = true,
                hasAudioPermission = false,
            )
        )
    }

    @Test
    fun `proceed when idle, recognizer ready, and permission granted`() {
        assertEquals(
            HomeVoicePolicy.RequestDecision.Proceed,
            HomeVoicePolicy.decideRequest(
                isBusy = false,
                isRecognitionAvailable = true,
                hasAudioPermission = true,
            )
        )
    }

    // -----------------------------------------------------------------
    // shouldResumeFollowUpAfterInterrupt
    // -----------------------------------------------------------------

    @Test
    fun `does not resume when no follow-up was pending`() {
        assertFalse(
            HomeVoicePolicy.shouldResumeFollowUpAfterInterrupt(
                followUpPending = false,
                isTtsSpeaking = true,
                assistantSpeechPendingStart = true,
                assistantSpeechStarted = true,
            )
        )
    }

    @Test
    fun `does not resume when follow-up is pending but no speech was happening`() {
        assertFalse(
            HomeVoicePolicy.shouldResumeFollowUpAfterInterrupt(
                followUpPending = true,
                isTtsSpeaking = false,
                assistantSpeechPendingStart = false,
                assistantSpeechStarted = false,
            )
        )
    }

    @Test
    fun `resumes when follow-up was pending and speech was in any phase`() {
        assertTrue(
            HomeVoicePolicy.shouldResumeFollowUpAfterInterrupt(
                followUpPending = true,
                isTtsSpeaking = true,
                assistantSpeechPendingStart = false,
                assistantSpeechStarted = false,
            )
        )
        assertTrue(
            HomeVoicePolicy.shouldResumeFollowUpAfterInterrupt(
                followUpPending = true,
                isTtsSpeaking = false,
                assistantSpeechPendingStart = true,
                assistantSpeechStarted = false,
            )
        )
        assertTrue(
            HomeVoicePolicy.shouldResumeFollowUpAfterInterrupt(
                followUpPending = true,
                isTtsSpeaking = false,
                assistantSpeechPendingStart = false,
                assistantSpeechStarted = true,
            )
        )
    }

    // -----------------------------------------------------------------
    // feedbackTimeoutMs
    // -----------------------------------------------------------------

    @Test
    fun `in-progress indicator gets the long timeout`() {
        assertEquals(60_000L, HomeVoicePolicy.feedbackTimeoutMs("…"))
    }

    @Test
    fun `short feedback clamps to the minimum`() {
        // "Ok" * 55ms = 110ms, but min floor is 4000ms
        assertEquals(4_000L, HomeVoicePolicy.feedbackTimeoutMs("Ok"))
    }

    @Test
    fun `long feedback clamps to the maximum`() {
        val longText = "a".repeat(500) // 500 * 55 = 27_500, ceiling is 10_000
        assertEquals(10_000L, HomeVoicePolicy.feedbackTimeoutMs(longText))
    }

    @Test
    fun `medium feedback scales with length`() {
        // 100 chars * 55 = 5500ms (between 4000 and 10000)
        assertEquals(5_500L, HomeVoicePolicy.feedbackTimeoutMs("a".repeat(100)))
    }
}
