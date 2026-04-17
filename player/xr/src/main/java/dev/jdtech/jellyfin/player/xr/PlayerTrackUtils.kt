package dev.jdtech.jellyfin.player.xr

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.xr.scenecore.SurfaceEntity
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel

/**
 * Pure helpers for reading track state from a Media3 Player, formatting playback
 * time, and mapping stereo-mode strings. Extracted from SpatialPlayerScreen.kt.
 */

internal fun mapStereoMode(mode: String): SurfaceEntity.StereoMode? = when (mode) {
    "sbs" -> SurfaceEntity.StereoMode.SIDE_BY_SIDE
    "top_bottom" -> SurfaceEntity.StereoMode.TOP_BOTTOM
    "multiview" -> SurfaceEntity.StereoMode.MULTIVIEW_LEFT_PRIMARY
    else -> null
}

internal fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
    else String.format("%d:%02d", minutes, seconds)
}

internal fun currentChapterName(
    uiState: PlayerViewModel.UiState,
    currentPositionMs: Long,
): String? {
    return uiState.currentChapters
        .sortedBy { it.startPosition }
        .lastOrNull { chapter -> currentPositionMs >= chapter.startPosition }
        ?.name
}

internal fun trackNames(player: Player, trackType: @C.TrackType Int): List<String> {
    return player.currentTracks.groups
        .filter { it.type == trackType && it.isSupported }
        .map { group ->
            group.getTrackFormat(0).label
                ?: group.getTrackFormat(0).language
                ?: "Unknown"
        }
}

internal fun selectedTrackName(player: Player, trackType: @C.TrackType Int): String? {
    return player.currentTracks.groups
        .firstOrNull { it.type == trackType && it.isSupported && groupIsSelected(it) }
        ?.let { group ->
            group.getTrackFormat(0).label ?: group.getTrackFormat(0).language
        }
}

internal fun selectedTrackLanguage(player: Player, trackType: @C.TrackType Int): String? {
    return player.currentTracks.groups
        .firstOrNull { it.type == trackType && it.isSupported && groupIsSelected(it) }
        ?.getTrackFormat(0)
        ?.language
        ?.takeUnless { it.equals("und", ignoreCase = true) }
}

internal fun groupIsSelected(group: Tracks.Group): Boolean {
    return (0 until group.length).any { group.isTrackSelected(it) }
}
