package dev.jdtech.jellyfin.player.core.splitav

import dev.jdtech.jellyfin.cast.splitav.AudioCodecTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverAudioCodecsTest {

    @Test
    fun `token sets delegate identically to fcast AudioCodecTokens`() {
        assertEquals(AudioCodecTokens.UNIVERSAL_SOFTWARE, ReceiverAudioCodecs.UNIVERSAL_SOFTWARE)
        assertEquals(AudioCodecTokens.ALL_PASSTHROUGH_TOKENS, ReceiverAudioCodecs.ALL_PASSTHROUGH_TOKENS)
        assertEquals(13, ReceiverAudioCodecs.UNIVERSAL_SOFTWARE.size)
        assertEquals(6, ReceiverAudioCodecs.ALL_PASSTHROUGH_TOKENS.size)
        assertTrue(
            ReceiverAudioCodecs.UNIVERSAL_SOFTWARE
                .intersect(ReceiverAudioCodecs.ALL_PASSTHROUGH_TOKENS)
                .isEmpty(),
        )
        assertEquals(
            19,
            (ReceiverAudioCodecs.UNIVERSAL_SOFTWARE + ReceiverAudioCodecs.ALL_PASSTHROUGH_TOKENS).size,
        )
    }

    @Test
    fun `normalize canonicalizes Jellyfin and Media3 codec spellings`() {
        assertEquals("eac3", ReceiverAudioCodecs.normalize(" EC3 "))
        assertEquals("eac3", ReceiverAudioCodecs.normalize("E-AC-3"))
        assertEquals("eac3", ReceiverAudioCodecs.normalize("e-ac3"))
        assertEquals("ac3", ReceiverAudioCodecs.normalize("AC-3"))
        assertEquals("truehd", ReceiverAudioCodecs.normalize("mlp"))
        assertEquals("truehd", ReceiverAudioCodecs.normalize("TrueHD-Atmos"))
        assertEquals("dts", ReceiverAudioCodecs.normalize("DCA"))
        assertEquals("dts-hd", ReceiverAudioCodecs.normalize("DTS-HD MA"))
        assertEquals("dts-hd", ReceiverAudioCodecs.normalize("dts_hd"))
        assertEquals("dts", ReceiverAudioCodecs.normalize("dtse"))
        assertEquals("dts-hd", ReceiverAudioCodecs.normalize("dtsx"))
        assertEquals("", ReceiverAudioCodecs.normalize(null))
    }

    @Test
    fun `canRenderDirect respects universal software codecs and passthrough fallbacks`() {
        assertTrue(ReceiverAudioCodecs.canRenderDirect("aac", null))
        assertTrue(ReceiverAudioCodecs.canRenderDirect("pcm_s24le", emptyList()))
        assertFalse(ReceiverAudioCodecs.canRenderDirect("truehd", null))
        assertFalse(ReceiverAudioCodecs.canRenderDirect("truehd", listOf("eac3", "ac3")))
        assertTrue(ReceiverAudioCodecs.canRenderDirect("truehd", listOf("truehd", "ac3")))
        assertTrue(ReceiverAudioCodecs.canRenderDirect("eac3-joc", listOf("e-ac-3")))
        assertFalse(ReceiverAudioCodecs.canRenderDirect("", listOf("aac")))
    }

    @Test
    fun `preferredTranscodeCodecs orders eac3 before ac3 before aac`() {
        assertEquals(listOf("aac"), ReceiverAudioCodecs.preferredTranscodeCodecs(null))
        assertEquals(listOf("ac3", "aac"), ReceiverAudioCodecs.preferredTranscodeCodecs(listOf("ac-3")))
        assertEquals(
            listOf("eac3", "ac3", "aac"),
            ReceiverAudioCodecs.preferredTranscodeCodecs(listOf("eac3-joc", "ac3")),
        )
    }
}
