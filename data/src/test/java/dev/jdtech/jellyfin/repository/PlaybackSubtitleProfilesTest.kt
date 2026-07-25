package dev.jdtech.jellyfin.repository

import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.SubtitleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackSubtitleProfilesTest {
    @Test
    fun `client-rendered text formats never advertise embedded delivery`() {
        val clientRenderedTextFormats = setOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt")

        val embeddedClientRenderedTextProfiles =
            createPlaybackSubtitleProfiles().filter { profile ->
                profile.format in clientRenderedTextFormats &&
                    profile.method == SubtitleDeliveryMethod.EMBED
            }

        assertFalse(embeddedClientRenderedTextProfiles.isNotEmpty())
    }

    @Test
    fun `playback subtitle profiles advertise the exact supported delivery methods`() {
        assertEquals(
            listOf(
                external("srt"),
                external("subrip"),
                external("ass"),
                external("ssa"),
                external("vtt"),
                external("webvtt"),
                external("sub"),
                embedded("sub"),
                external("smi"),
                embedded("smi"),
                external("pgssub"),
                embedded("pgssub"),
                external("pgs"),
                embedded("pgs"),
                external("dvdsub"),
                embedded("dvdsub"),
            ),
            createPlaybackSubtitleProfiles(),
        )
    }

    private fun external(format: String) =
        SubtitleProfile(format, SubtitleDeliveryMethod.EXTERNAL)

    private fun embedded(format: String) =
        SubtitleProfile(format, SubtitleDeliveryMethod.EMBED)
}
