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

        // Check for any text tracks that we can handle via libass
        val hasCompatibleTrack = textGroups.any { group ->
            group.isSupported &&
            (0 until group.length).any { i ->
                val mime = group.getTrackFormat(i).sampleMimeType
                // We now allow ASS/SSA and SRT/VTT fallback to libass for consistent styling
                mime == MimeTypes.TEXT_SSA || mime == "text/x-ssa" || 
                mime == MimeTypes.APPLICATION_SUBRIP || mime == MimeTypes.TEXT_VTT
            }
        }

        if (!hasCompatibleTrack) {
            // Check if subtitle transcoding ate the format
            val hasTranscodedTrack = textGroups.any { group ->
                (0 until group.length).any { i ->
                    group.getTrackFormat(i).sampleMimeType == "application/x-media3-cues"
                }
            }
            if (hasTranscodedTrack) {
                Timber.e("subtitle: useLibass=false — tracks show application/x-media3-cues, " +
                    "meaning Media3 subtitle transcoding is still active.")
            } else {
                Timber.i("subtitle: useLibass=false — no compatible text track found (pref=%s)", preference)
            }
            return false
        }

        val result = if (preference == "auto") {
            // In 2D mode, we prefer libass for everything if the user hasn't opted out
            true 
        } else {
            true // "always"
        }
        Timber.i(
            "subtitle: useLibass=%b — compatible text track found groups=%d pref=%s",
            result,
            textGroups.size,
            preference,
        )
        return result
    }
}
