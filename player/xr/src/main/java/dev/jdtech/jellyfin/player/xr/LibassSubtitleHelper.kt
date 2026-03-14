package dev.jdtech.jellyfin.player.xr

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import timber.log.Timber

object LibassSubtitleHelper {

    /**
     * Determine if the current content should use libass rendering.
     * Returns true if:
     *   1. libass native library is available
     *   2. Content has ASS/SSA subtitle tracks (not SRT/VTT/PGS)
     *   3. Content is anime (genre tag) OR subtitle track is ASS/SSA
     */
    fun shouldUseLibass(
        player: Player,
        genres: List<String>,
        preference: String,
    ): Boolean {
        if (!LibassRenderer.isAvailable()) {
            Timber.w("subtitle: useLibass=false — native libass library not loaded")
            return false
        }
        if (preference == "never") {
            Timber.i("subtitle: useLibass=false — pref=never")
            return false
        }

        val textGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (textGroups.isEmpty()) {
            Timber.i("subtitle: useLibass=false — no text tracks in media")
            return false
        }

        // Log all text tracks for diagnosis
        textGroups.forEachIndexed { gi, group ->
            (0 until group.length).forEach { i ->
                val fmt = group.getTrackFormat(i)
                Timber.i("subtitle: track[%d/%d] mime=%s lang=%s supported=%b selected=%b",
                    gi, i, fmt.sampleMimeType, fmt.language, group.isSupported, group.isSelected)
            }
        }

        // Check for ASS/SSA tracks with raw bytes (subtitle transcoding disabled)
        val hasRawAssTrack = textGroups.any { group ->
            group.isSupported &&
            (0 until group.length).any { i ->
                val mime = group.getTrackFormat(i).sampleMimeType
                mime == MimeTypes.TEXT_SSA || mime == "text/x-ssa"
            }
        }

        if (!hasRawAssTrack) {
            // Check if subtitle transcoding ate the ASS format — happens when
            // experimentalParseSubtitlesDuringExtraction is still true on the MediaSourceFactory.
            val hasTranscodedTrack = textGroups.any { group ->
                (0 until group.length).any { i ->
                    group.getTrackFormat(i).sampleMimeType == "application/x-media3-cues"
                }
            }
            if (hasTranscodedTrack) {
                Timber.e("subtitle: useLibass=false — tracks show application/x-media3-cues, " +
                    "meaning Media3 subtitle transcoding is still active. " +
                    "Ensure experimentalParseSubtitlesDuringExtraction(false) is set on MediaSourceFactory.")
            } else {
                Timber.i("subtitle: useLibass=false — no ASS/SSA track found (pref=%s)", preference)
            }
            return false
        }

        val result = if (preference == "auto") {
            genres.isEmpty() || genres.any {
                it.equals("Anime", ignoreCase = true) ||
                it.equals("Animation", ignoreCase = true)
            }
        } else {
            true // "always"
        }
        Timber.i("subtitle: useLibass=%b — ASS track found, pref=%s genres=%s", result, preference, genres)
        return result
    }
}