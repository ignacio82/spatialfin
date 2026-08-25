package dev.jdtech.jellyfin.repository

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These run on a plain JVM, so every `android.media` call throws `Stub!`. That is the point of
 * the assertions below about AUTO: a device with no probe-able audio route must degrade to
 * "no passthrough", never to "assume passthrough" — the failure mode of guessing wrong is
 * silence on the user's receiver.
 */
class AudioPassthroughCapabilitiesTest {

    @After
    fun tearDown() {
        AudioPassthroughDetector.resetSessionDisableForTest()
        SoftwareAudioDecoders.resetForTest()
    }

    @Test
    fun `preference parsing maps known values and defaults to auto`() {
        assertEquals(AudioPassthroughMode.AUTO, AudioPassthroughMode.fromPreference("auto"))
        assertEquals(AudioPassthroughMode.OFF, AudioPassthroughMode.fromPreference("off"))
        assertEquals(AudioPassthroughMode.FORCE, AudioPassthroughMode.fromPreference("force"))
        assertEquals(AudioPassthroughMode.AUTO, AudioPassthroughMode.fromPreference(null))
        assertEquals(AudioPassthroughMode.AUTO, AudioPassthroughMode.fromPreference(""))
        assertEquals(AudioPassthroughMode.AUTO, AudioPassthroughMode.fromPreference("nonsense"))
        assertEquals(AudioPassthroughMode.FORCE, AudioPassthroughMode.fromPreference("FORCE"))
    }

    @Test
    fun `off advertises nothing`() {
        assertTrue(AudioPassthroughDetector.supportedCodecs(AudioPassthroughMode.OFF).isEmpty())
    }

    @Test
    fun `auto advertises nothing when the route cannot be probed`() {
        assertTrue(AudioPassthroughDetector.supportedCodecs(AudioPassthroughMode.AUTO).isEmpty())
    }

    @Test
    fun `force advertises the full bitstream set without probing the route`() {
        val codecs = AudioPassthroughDetector.supportedCodecs(AudioPassthroughMode.FORCE)
        assertEquals(AudioPassthroughDetector.ALL_BITSTREAM_CODECS, codecs)
        // Names must match ffprobe / Jellyfin `MediaStream.codec` spellings, since that is
        // what a DirectPlayProfile's audioCodec list is compared against server-side.
        assertTrue(codecs.containsAll(setOf("ac3", "eac3", "dts", "truehd", "ac4")))
        assertFalse("DTS-HD is reported as plain `dts` by ffprobe", codecs.contains("dts-hd"))
        assertFalse("E-AC3 JOC is reported as `eac3` by ffprobe", codecs.contains("eac3-joc"))
    }

    @Test
    fun `dts express guard is skipped when dts is not advertised at all`() {
        // No probe-able route on the JVM, so AUTO advertises nothing and there is nothing to guard.
        assertFalse(AudioPassthroughDetector.needsDtsExpressGuard(AudioPassthroughMode.AUTO))
        assertFalse(AudioPassthroughDetector.needsDtsExpressGuard(AudioPassthroughMode.OFF))
    }

    @Test
    fun `force never needs the dts express guard because it claims dts-hd too`() {
        // FORCE advertises `dts`, so the first precondition holds...
        assertTrue(AudioPassthroughDetector.supportedCodecs(AudioPassthroughMode.FORCE).contains("dts"))
        // ...but FORCE also configures the sink for DTS-HD, so no core-only rewrite happens.
        assertFalse(AudioPassthroughDetector.needsDtsExpressGuard(AudioPassthroughMode.FORCE))
    }

    @Test
    fun `a local dts decoder removes the need for the guard`() {
        SoftwareAudioDecoders.install { mimeType -> mimeType.startsWith("audio/vnd.dts") }
        assertTrue(SoftwareAudioDecoders.canDecodeDts())
        for (mode in AudioPassthroughMode.entries) {
            assertFalse(
                "mode $mode must not force a server transcode when DTS decodes locally",
                AudioPassthroughDetector.needsDtsExpressGuard(mode),
            )
        }
    }

    @Test
    fun `session disable overrides every mode including force`() {
        AudioPassthroughDetector.disableForSession()
        assertTrue(AudioPassthroughDetector.isDisabledForSession())
        for (mode in AudioPassthroughMode.entries) {
            assertTrue(
                "mode $mode must advertise nothing after a passthrough failure",
                AudioPassthroughDetector.supportedCodecs(mode).isEmpty(),
            )
        }
    }
}
