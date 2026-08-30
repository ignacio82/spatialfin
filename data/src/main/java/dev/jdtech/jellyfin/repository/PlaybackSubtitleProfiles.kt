package dev.jdtech.jellyfin.repository

import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.SubtitleProfile

/**
 * Subtitle capabilities advertised to Jellyfin for normal video playback.
 *
 * Text formats decoded from a side-loaded URL must be EXTERNAL-only. Advertising EMBED for
 * those formats makes Jellyfin omit MediaStream.deliveryUrl. That bypasses the app's canonical
 * side-load path and drops subtitles entirely whenever the chosen HLS rendition excludes
 * embedded subtitle streams. Local and network-share media still retain the separately tested
 * embedded-track renderer because they do not use this Jellyfin playback profile.
 *
 * The remaining formats intentionally keep their existing delivery methods. In particular,
 * bitmap subtitles need EMBED because the player's external subtitle path is text-oriented,
 * while Media3 can decode supported bitmap tracks from the media container.
 */
internal fun createPlaybackSubtitleProfiles(): List<SubtitleProfile> =
    (CLIENT_RENDERED_TEXT_SUBTITLE_FORMATS + LEGACY_SUBTITLE_FORMATS + BITMAP_SUBTITLE_FORMATS)
        .flatMap(::externalAndEmbeddedProfiles)

private fun externalAndEmbeddedProfiles(format: String): List<SubtitleProfile> =
    listOf(
        SubtitleProfile(format, SubtitleDeliveryMethod.EXTERNAL),
        SubtitleProfile(format, SubtitleDeliveryMethod.EMBED),
    )

private val CLIENT_RENDERED_TEXT_SUBTITLE_FORMATS =
    listOf(
        "srt",
        "subrip",
        "ass",
        "ssa",
        "vtt",
        "webvtt",
        "mov_text",
        "tx3g",
        "mov-text",
        "ttml",
        "subviewer",
        "microdvd",
    )


// These formats are not currently assigned a decoder MIME type by the side-load path.
private val LEGACY_SUBTITLE_FORMATS = listOf("sub", "smi")

private val BITMAP_SUBTITLE_FORMATS = listOf("pgssub", "pgs", "dvdsub")
