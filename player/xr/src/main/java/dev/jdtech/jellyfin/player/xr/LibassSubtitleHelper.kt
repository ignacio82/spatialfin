package dev.jdtech.jellyfin.player.xr

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player

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
        if (!LibassRenderer.isAvailable()) return false
        if (preference == "never") return false

        // Check if any selected or available text track is ASS/SSA
        val hasAssTrack = player.currentTracks.groups.any { group ->
            group.type == C.TRACK_TYPE_TEXT &&
            group.isSupported &&
            (0 until group.length).any { i ->
                val format = group.getTrackFormat(i)
                format.sampleMimeType == MimeTypes.TEXT_SSA ||
                format.sampleMimeType == "text/x-ssa"
            }
        }

        if (!hasAssTrack) return false

        return if (preference == "auto") {
            // Only use libass for Anime content when "auto" is selected
            genres.any {
                it.equals("Anime", ignoreCase = true) ||
                it.equals("Animation", ignoreCase = true)
            }
        } else {
            // "always" selected - use libass for any ASS track
            true
        }
    }
}