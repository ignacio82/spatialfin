package dev.jdtech.jellyfin.models

import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.io.File
import java.util.UUID
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo

data class SpatialFinSource(
    val id: String,
    val name: String,
    val type: SpatialFinSourceType,
    val path: String,
    val size: Long,
    val mediaStreams: List<SpatialFinMediaStream>,
    val mediaAttachments: List<SpatialFinMediaAttachment> = emptyList(),
    val downloadId: Long? = null,
    val supportsDirectPlay: Boolean = true,
    val bitrate: Int? = null,
    /**
     * Absolute HLS master playlist URL Jellyfin generated when it rejected
     * direct play (e.g. because the source bitrate exceeds the cap). Null for
     * local / direct-play sources.
     */
    val transcodingUrl: String? = null,
)

suspend fun MediaSourceInfo.toSpatialFinSource(
    jellyfinRepository: JellyfinRepository,
    itemId: UUID,
    includePath: Boolean = false,
): SpatialFinSource {
    // If Jellyfin rejected direct play (e.g. source bitrate > user's cap) it
    // returns an HLS master playlist URL in `transcodingUrl`. Prefix with the
    // server base so the URL is absolute and ExoPlayer's DefaultMediaSourceFactory
    // picks up HlsMediaSource automatically from the .m3u8 path.
    val absoluteTranscodingUrl = transcodingUrl
        ?.takeIf { it.isNotBlank() }
        ?.let { rel ->
            if (rel.startsWith("http://") || rel.startsWith("https://")) rel
            else jellyfinRepository.getBaseUrl().trimEnd('/') + "/" + rel.trimStart('/')
        }

    val path =
        when (protocol) {
            MediaProtocol.FILE -> {
                try {
                    if (includePath) {
                        if (supportsDirectPlay || absoluteTranscodingUrl == null) {
                            jellyfinRepository.getStreamUrl(itemId, id.orEmpty())
                        } else {
                            absoluteTranscodingUrl
                        }
                    } else {
                        File(this.path.orEmpty()).name
                    }
                } catch (e: Exception) {
                    ""
                }
            }
            MediaProtocol.HTTP -> this.path.orEmpty()
            else -> ""
        }
    return SpatialFinSource(
        id = id.orEmpty(),
        name = name.orEmpty(),
        type = SpatialFinSourceType.REMOTE,
        path = path,
        size = size ?: 0,
        mediaStreams =
            mediaStreams?.map { it.toSpatialFinMediaStream(jellyfinRepository) } ?: emptyList(),
        mediaAttachments =
            mediaAttachments?.map { it.toSpatialFinMediaAttachment() } ?: emptyList(),
        supportsDirectPlay = supportsDirectPlay,
        bitrate = bitrate,
        transcodingUrl = absoluteTranscodingUrl,
    )
}

fun SpatialFinSourceDto.toSpatialFinSource(serverDatabaseDao: ServerDatabaseDao): SpatialFinSource {
    return SpatialFinSource(
        id = id,
        name = name,
        type = type,
        path = path,
        size = File(path).length(),
        mediaStreams =
            serverDatabaseDao.getMediaStreamsBySourceId(id).map { it.toSpatialFinMediaStream() },
        mediaAttachments = emptyList(),
        downloadId = downloadId,
    )
}

enum class SpatialFinSourceType {
    REMOTE,
    LOCAL,
    NETWORK,
}
