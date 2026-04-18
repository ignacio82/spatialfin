package dev.jdtech.jellyfin.player.local.presentation

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlayerTrackSignaturesTest {

    // -----------------------------------------------------------------
    // subtitle()
    // -----------------------------------------------------------------

    @Test
    fun `subtitle signature is stable for identical inputs`() {
        val a = PlayerTrackSignatures.subtitle(
            language = "en",
            label = "Full Dialogue",
            roleFlags = 0,
            selectionFlags = C.SELECTION_FLAG_DEFAULT,
            mimeType = "application/x-subrip",
        )
        val b = PlayerTrackSignatures.subtitle(
            language = "en",
            label = "Full Dialogue",
            roleFlags = 0,
            selectionFlags = C.SELECTION_FLAG_DEFAULT,
            mimeType = "application/x-subrip",
        )
        assertEquals(a, b)
    }

    @Test
    fun `subtitle signature is case-insensitive on language and label`() {
        val lower = PlayerTrackSignatures.subtitle(
            language = "en",
            label = "full dialogue",
            roleFlags = 0,
            selectionFlags = 0,
            mimeType = "text/vtt",
        )
        val upper = PlayerTrackSignatures.subtitle(
            language = "EN",
            label = "FULL DIALOGUE",
            roleFlags = 0,
            selectionFlags = 0,
            mimeType = "TEXT/VTT",
        )
        assertEquals(lower, upper)
    }

    @Test
    fun `subtitle signature trims surrounding whitespace`() {
        val padded = PlayerTrackSignatures.subtitle(
            language = "  en  ",
            label = "  Default  ",
            roleFlags = 0,
            selectionFlags = 0,
            mimeType = "  text/vtt  ",
        )
        val tight = PlayerTrackSignatures.subtitle(
            language = "en",
            label = "Default",
            roleFlags = 0,
            selectionFlags = 0,
            mimeType = "text/vtt",
        )
        assertEquals(tight, padded)
    }

    @Test
    fun `null and missing fields are normalized to empty`() {
        val nulls = PlayerTrackSignatures.subtitle(
            language = null,
            label = null,
            roleFlags = 0,
            selectionFlags = 0,
            mimeType = null,
        )
        val empties = PlayerTrackSignatures.subtitle(
            language = "",
            label = "",
            roleFlags = 0,
            selectionFlags = 0,
            mimeType = "",
        )
        assertEquals(nulls, empties)
    }

    @Test
    fun `forced and default subtitle tracks have distinct signatures`() {
        val default = PlayerTrackSignatures.subtitle(
            language = "en",
            label = "English",
            roleFlags = 0,
            selectionFlags = C.SELECTION_FLAG_DEFAULT,
            mimeType = "text/vtt",
        )
        val forced = PlayerTrackSignatures.subtitle(
            language = "en",
            label = "English",
            roleFlags = 0,
            selectionFlags = C.SELECTION_FLAG_FORCED,
            mimeType = "text/vtt",
        )
        assertNotEquals(default, forced)
    }

    @Test
    fun `different languages produce different signatures`() {
        val en = PlayerTrackSignatures.subtitle(
            language = "en",
            label = "Default",
            roleFlags = 0,
            selectionFlags = 0,
            mimeType = "text/vtt",
        )
        val ja = PlayerTrackSignatures.subtitle(
            language = "ja",
            label = "Default",
            roleFlags = 0,
            selectionFlags = 0,
            mimeType = "text/vtt",
        )
        assertNotEquals(en, ja)
    }

    // -----------------------------------------------------------------
    // audio()
    // -----------------------------------------------------------------

    @Test
    fun `audio signature includes codec`() {
        // Same language + label + flags + mime, different codec — must distinguish
        // (Stereo AAC vs 5.1 EAC3 should be remembered as separate user picks).
        val aac = PlayerTrackSignatures.audio(
            language = "en",
            label = "English",
            roleFlags = 0,
            selectionFlags = 0,
            mimeType = "audio/mp4a-latm",
            codecs = "mp4a.40.2",
        )
        val eac3 = PlayerTrackSignatures.audio(
            language = "en",
            label = "English",
            roleFlags = 0,
            selectionFlags = 0,
            mimeType = "audio/mp4a-latm",
            codecs = "ec-3",
        )
        assertNotEquals(aac, eac3)
    }

    @Test
    fun `audio signature null codec is treated as empty`() {
        val nullCodec = PlayerTrackSignatures.audio(
            language = "en",
            label = "English",
            roleFlags = 0,
            selectionFlags = 0,
            mimeType = "audio/mp4a-latm",
            codecs = null,
        )
        val emptyCodec = PlayerTrackSignatures.audio(
            language = "en",
            label = "English",
            roleFlags = 0,
            selectionFlags = 0,
            mimeType = "audio/mp4a-latm",
            codecs = "",
        )
        assertEquals(nullCodec, emptyCodec)
    }
}
