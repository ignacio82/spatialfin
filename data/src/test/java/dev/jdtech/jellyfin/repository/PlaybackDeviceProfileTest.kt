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
    fun `baseline video codecs do not contain unsupported legacy codecs like vc1`() {
        val baseline = AndroidCodecDetector.BASELINE_VIDEO_CODECS
        assertFalse("Baseline must not claim direct play for vc1 without hardware decoder", baseline.contains("vc1"))
        assertFalse("Baseline must not claim direct play for wmv3", baseline.contains("wmv3"))
        assertFalse("Baseline must not claim direct play for prores", baseline.contains("prores"))
        assertTrue(baseline.contains("h264"))
        assertTrue(baseline.contains("hevc"))
    }
}
