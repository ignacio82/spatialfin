package dev.jdtech.jellyfin.repository

import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDeviceProfileTest {

    @Test
    fun `default device profile advertises specific supported video and audio codecs`() {
        val profile = createPlaybackDeviceProfile(bitrate = 40_000_000L, forceDirectPlay = false)

        assertEquals("SpatialFin Android", profile.name)
        assertEquals(40_000_000, profile.maxStaticBitrate)
        assertEquals(40_000_000, profile.maxStreamingBitrate)

        // Must have direct play profiles for video and audio
        val videoProfiles = profile.directPlayProfiles.filter { it.type == DlnaProfileType.VIDEO }
        val audioProfiles = profile.directPlayProfiles.filter { it.type == DlnaProfileType.AUDIO }

        assertFalse(videoProfiles.isEmpty())
        assertFalse(audioProfiles.isEmpty())

        // Video profiles must explicitly restrict videoCodec and audioCodec so Jellyfin knows when to transcode
        for (vp in videoProfiles) {
            assertNotNull("videoCodec must not be null for capability-aware profile", vp.videoCodec)
            assertNotNull("audioCodec must not be null for capability-aware profile", vp.audioCodec)

            val vCodecs = vp.videoCodec!!.split(",")
            assertTrue(vCodecs.contains("h264") || vCodecs.contains("avc"))
            assertTrue(vCodecs.contains("hevc") || vCodecs.contains("h265"))

            val aCodecs = vp.audioCodec!!.split(",")
            assertTrue(aCodecs.contains("aac"))
            assertTrue(aCodecs.contains("mp3"))
        }

        // Must include transcoding profiles for video (HLS) and audio
        val hlsTranscoding = profile.transcodingProfiles.firstOrNull {
            it.type == DlnaProfileType.VIDEO && it.protocol == MediaStreamProtocol.HLS
        }
        assertNotNull("Must include HLS video transcoding profile", hlsTranscoding)
        assertEquals("ts", hlsTranscoding?.container)
        assertTrue(hlsTranscoding?.videoCodec?.contains("h264") == true)
        assertTrue(hlsTranscoding?.audioCodec?.contains("aac") == true)

        // Subtitle profiles must be included
        assertFalse(profile.subtitleProfiles.isEmpty())
    }

    @Test
    fun `forceDirectPlay creates unrestricted catch-all direct play profiles`() {
        val profile = createPlaybackDeviceProfile(bitrate = 1_000_000_000L, forceDirectPlay = true)

        val videoProfile = profile.directPlayProfiles.firstOrNull { it.type == DlnaProfileType.VIDEO }
        assertNotNull(videoProfile)
        assertEquals("", videoProfile!!.container)
        assertNull(videoProfile.videoCodec)
        assertNull(videoProfile.audioCodec)
    }

    @Test
    fun `passthrough mode widens the advertised audio codec list`() {
        // AUTO on a JVM (no probe-able audio route) must not invent bitstream support.
        val auto = createPlaybackDeviceProfile(
            bitrate = 40_000_000L,
            passthroughMode = AudioPassthroughMode.AUTO,
        )
        val autoCodecs = auto.directPlayProfiles
            .first { it.type == DlnaProfileType.VIDEO }
            .audioCodec!!.split(",")
        // `ac3` is in the *decoder* baseline, so it is not a passthrough signal. `truehd` and
        // `dts` have no baseline decoder, so their presence could only come from passthrough.
        assertFalse("AUTO must not claim truehd without a route that accepts it", autoCodecs.contains("truehd"))
        assertFalse("AUTO must not claim ac4 without a route that accepts it", autoCodecs.contains("ac4"))

        // FORCE advertises the bitstream set on top of whatever can be decoded locally.
        val forced = createPlaybackDeviceProfile(
            bitrate = 40_000_000L,
            passthroughMode = AudioPassthroughMode.FORCE,
        )
        val forcedCodecs = forced.directPlayProfiles
            .first { it.type == DlnaProfileType.VIDEO }
            .audioCodec!!.split(",")
        assertTrue(forcedCodecs.containsAll(listOf("ac3", "eac3", "dts", "truehd")))
        // The decoder-derived baseline must survive the merge.
        assertTrue(forcedCodecs.contains("aac"))
        assertTrue(forcedCodecs.contains("mp3"))
    }

    @Test
    fun `forceDirectPlay ignores the passthrough mode because it restricts nothing`() {
        val profile = createPlaybackDeviceProfile(
            bitrate = 1_000_000_000L,
            forceDirectPlay = true,
            passthroughMode = AudioPassthroughMode.OFF,
        )
        val videoProfile = profile.directPlayProfiles.first { it.type == DlnaProfileType.VIDEO }
        assertNull(videoProfile.audioCodec)
    }

    @Test
    fun `bundled software decoders widen the advertised audio codec list`() {
        try {
            SoftwareAudioDecoders.install { it in setOf("audio/vnd.dts", "audio/true-hd") }
            val profile = createPlaybackDeviceProfile(
                bitrate = 40_000_000L,
                passthroughMode = AudioPassthroughMode.AUTO,
            )
            val codecs = profile.directPlayProfiles
                .first { it.type == DlnaProfileType.VIDEO }
                .audioCodec!!.split(",")
            // No passthrough route on the JVM — these can only have come from the registry.
            assertTrue(codecs.contains("dts"))
            assertTrue(codecs.contains("truehd"))
            // A local DTS decoder means the DTS Express guard would only force a pointless
            // server transcode, so it must not be emitted.
            assertTrue(profile.codecProfiles.isEmpty())
        } finally {
            SoftwareAudioDecoders.resetForTest()
        }
    }

    @Test
    fun `forceDirectPlay never emits a codec profile`() {
        // The catch-all profile restricts nothing by design; a DTS condition on top of it would
        // contradict the setting the user explicitly asked for.
        val profile = createPlaybackDeviceProfile(
            bitrate = 1_000_000_000L,
            forceDirectPlay = true,
            passthroughMode = AudioPassthroughMode.FORCE,
        )
        assertTrue(profile.codecProfiles.isEmpty())
    }

    @Test
    fun `baseline video codecs do not contain unsupported legacy codecs like vc1`() {
        val baseline = AndroidCodecDetector.BASELINE_VIDEO_CODECS
        assertFalse("Baseline must not claim direct play for vc1 without hardware decoder", baseline.contains("vc1"))
        assertFalse("Baseline must not claim direct play for wmv3", baseline.contains("wmv3"))
        assertFalse("Baseline must not claim direct play for prores", baseline.contains("prores"))
        assertTrue(baseline.contains("h264"))
        assertTrue(baseline.contains("hevc"))
    }
}
