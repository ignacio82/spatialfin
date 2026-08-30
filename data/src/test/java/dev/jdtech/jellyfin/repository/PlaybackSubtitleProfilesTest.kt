package dev.jdtech.jellyfin.repository

import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.SubtitleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackSubtitleProfilesTest {
    @Test
    fun `playback subtitle profiles advertise the exact supported delivery methods`() {
        val expectedFormats = listOf(
            "srt", "subrip", "ass", "ssa", "vtt", "webvtt",
            "mov_text", "tx3g", "mov-text", "ttml", "subviewer", "microdvd",
            "sub", "smi", "pgssub", "pgs", "dvdsub"
        )
        val expectedProfiles = expectedFormats.flatMap { format ->
            listOf(external(format), embedded(format))
        }
        assertEquals(expectedProfiles, createPlaybackSubtitleProfiles())
    }

    private fun external(format: String) =
        SubtitleProfile(format, SubtitleDeliveryMethod.EXTERNAL)

    private fun embedded(format: String) =
        SubtitleProfile(format, SubtitleDeliveryMethod.EMBED)
}
