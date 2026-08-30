package dev.jdtech.jellyfin.player.local.domain

import androidx.media3.common.C
import androidx.media3.common.Tracks
import dev.jdtech.jellyfin.models.SpatialFinMediaStream
import org.jellyfin.sdk.model.api.MediaStreamType

/**
 * Pairs Media3 track groups with the Jellyfin [SpatialFinMediaStream]s they
 * were built from, so the UI can fall back to server metadata when the
 * container's own headers are thin (a missing Matroska language tag, an
 * unlabelled track).
 *
 * The only link between the two lists is ordinal position, and that link
 * holds only while the source is direct-played. Once Jellyfin transcodes it
 * encodes a *single* audio track into the HLS playlist, so the player sees one
 * group where the source has many: mapping group 0 onto stream 0 would then
 * confidently label the delivered track with the first stream in the file,
 * which is exactly the language the user was trying to get away from.
 *
 * So the pairing is only applied when the counts line up. When they don't, no
 * stream is attributed and callers fall back to what the format itself says.
 */
fun List<Tracks.Group>.pairedStreams(
    mediaStreams: List<SpatialFinMediaStream>,
): List<SpatialFinMediaStream?> {
    if (isEmpty()) return emptyList()
    val byType = groupBy { it.type }
    val paired = arrayOfNulls<SpatialFinMediaStream>(size)
    byType.forEach { (trackType, groups) ->
        val streamType = trackType.toMediaStreamType() ?: return@forEach
        val streams = mediaStreams.filter { it.type == streamType }
        if (streams.size != groups.size) return@forEach
        groups.forEachIndexed { ordinal, group ->
            paired[indexOf(group)] = streams[ordinal]
        }
    }
    return paired.toList()
}

/** Single-group convenience for callers that already hold the group list. */
fun Tracks.Group.pairedStream(
    allGroups: List<Tracks.Group>,
    mediaStreams: List<SpatialFinMediaStream>,
): SpatialFinMediaStream? {
    val index = allGroups.indexOf(this).takeIf { it >= 0 } ?: return null
    return allGroups.pairedStreams(mediaStreams).getOrNull(index)
}

internal fun Int.toMediaStreamType(): MediaStreamType? = when (this) {
    C.TRACK_TYPE_AUDIO -> MediaStreamType.AUDIO
    C.TRACK_TYPE_TEXT -> MediaStreamType.SUBTITLE
    C.TRACK_TYPE_VIDEO -> MediaStreamType.VIDEO
    else -> null
}
