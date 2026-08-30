package dev.jdtech.jellyfin.player.xr

import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.scenecore.SurfaceEntity
import dev.jdtech.jellyfin.models.SpatialFinMediaStream
import dev.jdtech.jellyfin.player.core.ProjectionModeDetector
import dev.jdtech.jellyfin.player.local.domain.pairedStreams
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import java.util.Locale

/**
 * Pure helpers for reading track state from a Media3 Player, formatting playback
 * time, and mapping stereo-mode / projection strings. Extracted from
 * SpatialPlayerScreen.kt.
 */

internal fun mapStereoMode(mode: String): SurfaceEntity.StereoMode? = when (mode) {
    "sbs" -> SurfaceEntity.StereoMode.SIDE_BY_SIDE
    "top_bottom" -> SurfaceEntity.StereoMode.TOP_BOTTOM
    "multiview" -> SurfaceEntity.StereoMode.MULTIVIEW_LEFT_PRIMARY
    else -> null
}

/** True for the immersive projections that render onto a hemisphere/sphere. */
internal fun isImmersiveProjection(projection: String): Boolean =
    projection == ProjectionModeDetector.PROJECTION_180 ||
        projection == ProjectionModeDetector.PROJECTION_360

/**
 * Maps the player's `"projection"` string to the SceneCore canvas shape. 180° →
 * front hemisphere, 360° → full sphere (both radius [radiusMeters], head at
 * centre), anything else → the flat [quad].
 */
internal fun mapProjectionShape(
    projection: String,
    radiusMeters: Float,
    quad: FloatSize2d,
): SurfaceEntity.Shape = when (projection) {
    ProjectionModeDetector.PROJECTION_180 -> SurfaceEntity.Shape.Hemisphere(radiusMeters)
    ProjectionModeDetector.PROJECTION_360 -> SurfaceEntity.Shape.Sphere(radiusMeters)
    else -> SurfaceEntity.Shape.Quad(quad)
}

/**
 * Authoritative container-metadata tier: derives a projection from a decoded
 * video [Format]. media3 exposes only the raw `projectionData` bytes (no parsed
 * 180-vs-360 enum), so we treat any spherical metadata as 360° unless the
 * stereo layout is the mesh kind that VR180 rigs emit. Returns null when the
 * container declares no spherical projection, leaving the filename/manual tiers
 * in charge.
 */
internal fun projectionFromFormat(format: Format): String? {
    if (format.projectionData == null) return null
    return if (format.stereoMode == C.STEREO_MODE_STEREO_MESH) {
        ProjectionModeDetector.PROJECTION_180
    } else {
        ProjectionModeDetector.PROJECTION_360
    }
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

private fun visibleGroups(player: Player, trackType: @C.TrackType Int): List<Tracks.Group> =
    player.currentTracks.groups
        .filter { it.type == trackType && (trackType == C.TRACK_TYPE_TEXT || it.isSupported) }

/**
 * Human-readable name for a track, preferring what the container says and
 * falling back to Jellyfin's metadata for the same track when the container
 * carries no label and no usable language tag.
 */
private fun trackLabel(
    group: Tracks.Group,
    stream: SpatialFinMediaStream?,
    trackType: @C.TrackType Int,
): String {
    val format = group.getTrackFormat(0)
    val label = format.label?.takeIf { it.isNotBlank() }
        ?: stream?.title?.takeIf { it.isNotBlank() }
        ?: displayLanguageOf(group, stream)
        ?: stream?.displayTitle?.takeIf { it.isNotBlank() }
        ?: "Unknown"

    if (trackType != C.TRACK_TYPE_TEXT) return label

    val mime = format.sampleMimeType ?: stream?.codec ?: ""
    val suffix = when {
        mime.contains("subrip", ignoreCase = true) -> " (SRT)"
        mime.contains("vtt", ignoreCase = true) -> " (VTT)"
        mime.contains("ssa", ignoreCase = true) || mime.contains("ass", ignoreCase = true) -> " (ASS)"
        else -> ""
    }
    return label + suffix
}

private fun languageCodeOf(group: Tracks.Group, stream: SpatialFinMediaStream?): String? {
    val format = group.getTrackFormat(0)
    return format.language?.takeUnless { it.isBlank() || it.equals("und", ignoreCase = true) }
        ?: stream?.language?.takeUnless { it.isBlank() || it.equals("und", ignoreCase = true) }
}

private fun displayLanguageOf(group: Tracks.Group, stream: SpatialFinMediaStream?): String? =
    languageCodeOf(group, stream)?.let { code ->
        val base = code.split("-").last()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            Locale.of(base).displayLanguage
        } else {
            @Suppress("DEPRECATION") Locale(base).displayLanguage
        }.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
    }

internal fun trackNames(
    player: Player,
    trackType: @C.TrackType Int,
    mediaStreams: List<SpatialFinMediaStream> = emptyList(),
): List<String> {
    val groups = visibleGroups(player, trackType)
    val streams = groups.pairedStreams(mediaStreams)
    return groups.mapIndexed { index, group ->
        trackLabel(group, streams.getOrNull(index), trackType)
    }
}

internal fun selectedTrackName(
    player: Player,
    trackType: @C.TrackType Int,
    mediaStreams: List<SpatialFinMediaStream> = emptyList(),
): String? {
    val groups = visibleGroups(player, trackType)
    val selected = groups.indexOfFirst { groupIsSelected(it) }
    if (selected < 0) return null
    return trackLabel(groups[selected], groups.pairedStreams(mediaStreams).getOrNull(selected), trackType)
}

internal fun selectedTrackLanguage(
    player: Player,
    trackType: @C.TrackType Int,
    mediaStreams: List<SpatialFinMediaStream> = emptyList(),
): String? {
    val groups = visibleGroups(player, trackType)
    val selected = groups.indexOfFirst { groupIsSelected(it) }
    if (selected < 0) return null
    return languageCodeOf(groups[selected], groups.pairedStreams(mediaStreams).getOrNull(selected))
}

internal fun groupIsSelected(group: Tracks.Group): Boolean {
    return (0 until group.length).any { group.isTrackSelected(it) }
}
