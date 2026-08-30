package dev.jdtech.jellyfin.player.local.domain

import androidx.media3.common.C
import androidx.media3.common.Tracks
import dev.jdtech.jellyfin.models.MediaStreamLanguage
import dev.jdtech.jellyfin.models.SpatialFinMediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import java.util.Locale

/**
 * One selectable audio track backed by a Jellyfin stream index rather than by
 * a track in the delivered container.
 */
data class ServerAudioTrack(
    /** Jellyfin `MediaStreams` index — what `AudioStreamIndex` must be set to. */
    val streamIndex: Int,
    val label: String,
    val detail: String?,
    val isSelected: Boolean,
)

/**
 * The audio tracks to offer when the player's own track selector cannot do the
 * job, or an empty list when it can.
 *
 * Jellyfin encodes exactly ONE audio stream into a transcoded HLS playlist —
 * whichever `AudioStreamIndex` the PlaybackInfo request named. ExoPlayer
 * therefore sees a single audio track and `trackSelectionParameters` has
 * nothing to switch to, even though the source file has several languages.
 * The only way to change audio is to ask the server for a different stream and
 * rebuild playback, which is what [ServerAudioTrack.streamIndex] feeds.
 *
 * Returns empty when the container carries every audio stream itself
 * (direct play): local switching is instant and seekless, and is always
 * preferable to a round trip through the server.
 *
 * @param activeStreamIndex the stream Jellyfin says it delivered. The delivered
 *   track's own metadata cannot be trusted to identify it — that is precisely
 *   the case where a language tag is missing.
 */
fun serverSideAudioTracks(
    audioTrackGroups: List<Tracks.Group>,
    mediaStreams: List<SpatialFinMediaStream>,
    activeStreamIndex: Int?,
): List<ServerAudioTrack> {
    val audioStreams = mediaStreams.filter { it.type == MediaStreamType.AUDIO && it.index != null }
    if (audioStreams.size <= 1) return emptyList()
    // Every stream present as its own track: the player can switch locally.
    if (audioTrackGroups.size >= audioStreams.size) return emptyList()

    val fallbackSelection = activeStreamIndex
        ?: audioStreams.firstOrNull { it.isDefault }?.index
        ?: audioStreams.first().index

    return audioStreams.map { stream ->
        ServerAudioTrack(
            streamIndex = stream.index!!,
            label = audioTrackLabel(stream),
            detail = audioTrackDetail(stream),
            isSelected = stream.index == fallbackSelection,
        )
    }
}

/**
 * Display name for [stream], preferring Jellyfin's own `displayTitle` — it is
 * already assembled for humans ("English - Dolby Digital Plus - 5.1") and is
 * populated even when the container omitted the language tag.
 */
fun audioTrackLabel(stream: SpatialFinMediaStream): String {
    stream.displayTitle?.takeIf { it.isNotBlank() }?.let { return it }
    val language = MediaStreamLanguage.displayCode(stream) ?: "Unknown"
    val title = stream.title.takeIf { it.isNotBlank() }
    return listOfNotNull(language, title).joinToString(" - ")
}

/** Secondary line: codec and channel layout, when they add anything. */
fun audioTrackDetail(stream: SpatialFinMediaStream): String? =
    listOfNotNull(
        stream.codec.takeIf { it.isNotBlank() }?.uppercase(Locale.US),
        stream.channelLayout?.takeIf { it.isNotBlank() },
    ).joinToString(" · ").takeIf { it.isNotBlank() }

/** Convenience for callers holding a whole `Tracks` object. */
fun Tracks.audioGroups(): List<Tracks.Group> =
    groups.filter { it.type == C.TRACK_TYPE_AUDIO }
