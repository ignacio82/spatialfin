package dev.jdtech.jellyfin.player.xr.voice

import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.player.session.voice.VoiceScreenContext
import dev.jdtech.jellyfin.player.session.voice.XrPlayerAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicVoiceGrammarTest {

    private fun home(maActive: Boolean = false, track: String? = null) = PlayerStateSnapshot(
        screenContext = VoiceScreenContext.HOME,
        maActive = maActive,
        maCurrentTrack = track,
    )

    private fun player(maActive: Boolean = true) = PlayerStateSnapshot(
        screenContext = VoiceScreenContext.PLAYER,
        maActive = maActive,
    )

    // --- Music-qualified phrases: work whenever music could be the surface -----

    @Test
    fun `next song maps to MusicNext even before MA reports active`() {
        assertEquals(XrPlayerAction.MusicNext, MusicVoiceGrammar.match("next song", home(maActive = false)))
    }

    @Test
    fun `skip this song maps to MusicNext`() {
        assertEquals(XrPlayerAction.MusicNext, MusicVoiceGrammar.match("skip this song", home(maActive = true)))
    }

    @Test
    fun `previous track maps to MusicPrevious`() {
        assertEquals(XrPlayerAction.MusicPrevious, MusicVoiceGrammar.match("previous track", home()))
    }

    @Test
    fun `pause the music maps to MusicPause`() {
        assertEquals(XrPlayerAction.MusicPause, MusicVoiceGrammar.match("pause the music", home()))
    }

    @Test
    fun `dont stop the music is not a pause`() {
        assertNull(MusicVoiceGrammar.match("don t stop the music", home(maActive = true)))
    }

    @Test
    fun `turn the music up maps to a positive volume delta`() {
        assertEquals(
            XrPlayerAction.MusicAdjustVolume(delta = 0.1f),
            MusicVoiceGrammar.match("turn the music up", home()),
        )
    }

    // --- Now-playing query ----------------------------------------------------

    @Test
    fun `what song is this asks for now playing`() {
        assertEquals(
            XrPlayerAction.MusicReportNowPlaying,
            MusicVoiceGrammar.match("what song is this", home()),
        )
    }

    @Test
    fun `whats playing is now-playing only when music is active`() {
        assertEquals(
            XrPlayerAction.MusicReportNowPlaying,
            MusicVoiceGrammar.match("what s playing", home(maActive = true)),
        )
        assertNull(MusicVoiceGrammar.match("what s playing", home(maActive = false)))
    }

    // --- Bare verbs: only when MA is the active HOME surface -------------------

    @Test
    fun `bare pause routes to music when MA active in home`() {
        assertEquals(XrPlayerAction.MusicPause, MusicVoiceGrammar.match("pause", home(maActive = true)))
    }

    @Test
    fun `bare play resumes music when MA active in home`() {
        assertEquals(XrPlayerAction.MusicResume, MusicVoiceGrammar.match("play", home(maActive = true)))
    }

    @Test
    fun `bare next routes to music when MA active in home`() {
        assertEquals(XrPlayerAction.MusicNext, MusicVoiceGrammar.match("next", home(maActive = true)))
    }

    @Test
    fun `bare volume up routes to music when MA active in home`() {
        assertEquals(
            XrPlayerAction.MusicAdjustVolume(delta = 0.1f),
            MusicVoiceGrammar.match("volume up", home(maActive = true)),
        )
    }

    @Test
    fun `volume to 30 percent is an absolute set`() {
        assertEquals(
            XrPlayerAction.MusicAdjustVolume(percentage = 0.3f),
            MusicVoiceGrammar.match("set volume to 30 percent", home(maActive = true)),
        )
    }

    @Test
    fun `bare pause does nothing when MA not active`() {
        assertNull(MusicVoiceGrammar.match("pause", home(maActive = false)))
    }

    // --- Surface gating -------------------------------------------------------

    @Test
    fun `player screen is never captured by music grammar`() {
        assertNull(MusicVoiceGrammar.match("next song", player()))
        assertNull(MusicVoiceGrammar.match("pause", player()))
        assertNull(MusicVoiceGrammar.match("what song is this", player()))
    }

    @Test
    fun `play with a query is left for the play-music search path`() {
        assertNull(MusicVoiceGrammar.match("play music by adele", home(maActive = true)))
    }
}
