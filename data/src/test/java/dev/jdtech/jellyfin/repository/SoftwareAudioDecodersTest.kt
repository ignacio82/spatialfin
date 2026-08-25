package dev.jdtech.jellyfin.repository

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftwareAudioDecodersTest {

    @After
    fun tearDown() {
        SoftwareAudioDecoders.resetForTest()
    }

    @Test
    fun `no installed probe reports nothing`() {
        // The pre-registry behaviour: the profile falls back to MediaCodecList alone. This is
        // the state in any process where `FfmpegAudioDecoders.install()` has not run, so it
        // must never claim a codec.
        assertTrue(SoftwareAudioDecoders.supportedCodecs().isEmpty())
        assertFalse(SoftwareAudioDecoders.canDecodeDts())
    }

    @Test
    fun `probe results map onto jellyfin codec names`() {
        SoftwareAudioDecoders.install { mimeType ->
            mimeType in setOf("audio/ac3", "audio/eac3", "audio/true-hd")
        }
        val codecs = SoftwareAudioDecoders.supportedCodecs()
        assertEquals(setOf("ac3", "eac3", "truehd", "mlp"), codecs)
        assertFalse("dts was not offered by the probe", codecs.contains("dts"))
        assertFalse(SoftwareAudioDecoders.canDecodeDts())
    }

    @Test
    fun `either dts mime type is enough to claim dts`() {
        SoftwareAudioDecoders.install { it == "audio/vnd.dts.hd" }
        assertTrue(SoftwareAudioDecoders.canDecodeDts())
        assertTrue(SoftwareAudioDecoders.supportedCodecs().containsAll(setOf("dts", "dca")))
    }

    @Test
    fun `the probe is called once per mime type and then cached`() {
        var calls = 0
        SoftwareAudioDecoders.install { calls++; true }
        val first = SoftwareAudioDecoders.supportedCodecs()
        val callsAfterFirst = calls
        val second = SoftwareAudioDecoders.supportedCodecs()
        assertEquals(first, second)
        assertEquals("second read must not re-enter the native library", callsAfterFirst, calls)
    }

    @Test
    fun `a throwing probe degrades to no support instead of failing playback`() {
        SoftwareAudioDecoders.install { error("no native library") }
        assertTrue(SoftwareAudioDecoders.supportedCodecs().isEmpty())
    }
}
