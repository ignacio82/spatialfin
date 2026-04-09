package dev.jdtech.jellyfin.player.xr.voice

import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.player.session.voice.VoiceScreenContext
import dev.jdtech.jellyfin.player.session.voice.XrPlayerAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceReplayCommandLibraryTest {
    @Test
    fun `recommendation utterance maps to chat query`() {
        val action =
            VoiceReplayCommandLibrary.match(
                transcript = "recommend something for me to watch",
                normalized = "recommend something for me to watch",
                playerState = PlayerStateSnapshot(screenContext = VoiceScreenContext.HOME),
            )

        assertEquals(
            XrPlayerAction.ChatQuery("recommend something for me to watch"),
            action,
        )
    }

    @Test
    fun `follow up filter uses prior recommendation context`() {
        val action =
            VoiceReplayCommandLibrary.match(
                transcript = "shorter",
                normalized = "shorter",
                playerState = PlayerStateSnapshot(lastRecommendationCount = 3),
            )

        assertEquals(XrPlayerAction.ChatQuery("shorter"), action)
    }

    @Test
    fun `ordinal playback command selects first option`() {
        val action =
            VoiceReplayCommandLibrary.match(
                transcript = "play the first one",
                normalized = "play the first one",
                playerState = PlayerStateSnapshot(),
            )

        assertEquals(XrPlayerAction.SelectOption(0), action)
    }

    @Test
    fun `home library phrase routes home`() {
        val action =
            VoiceReplayCommandLibrary.match(
                transcript = "go to the library",
                normalized = "go to the library",
                playerState = PlayerStateSnapshot(screenContext = VoiceScreenContext.HOME),
            )

        assertTrue(action is XrPlayerAction.GoHome)
    }
}
