package dev.jdtech.jellyfin.player.beam

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import timber.log.Timber

object LibassSubtitleHelper {
    fun shouldUseLibass(
        player: Player,
        preference: String,
    ): Boolean {
        if (!LibassRenderer.isAvailable()) {
            Timber.w("subtitle: useLibass=false — native libass bridge unavailable")
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

        val hasCompatibleTrack = textGroups.any { group ->
            group.isSupported &&
                (0 until group.length).any { index ->
                    when (group.getTrackFormat(index).sampleMimeType) {
                        MimeTypes.TEXT_SSA,
                        "text/x-ssa",
                        MimeTypes.APPLICATION_SUBRIP,
                        MimeTypes.TEXT_VTT,
                        -> true
                        else -> false
                    }
                }
        }
        if (!hasCompatibleTrack) {
            val hasTranscodedTrack = textGroups.any { group ->
                (0 until group.length).any { index ->
                    group.getTrackFormat(index).sampleMimeType == "application/x-media3-cues"
                }
            }
            if (hasTranscodedTrack) {
                Timber.e("subtitle: useLibass=false — Media3 subtitle transcoding is still active")
            }
            return false
        }

        Timber.i("subtitle: useLibass=true — compatible text track found pref=%s", preference)
        return true
    }
}
