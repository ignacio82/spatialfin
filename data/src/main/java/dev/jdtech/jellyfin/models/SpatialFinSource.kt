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
)

suspend fun MediaSourceInfo.toSpatialFinSource(
    jellyfinRepository: JellyfinRepository,
    itemId: UUID,
    includePath: Boolean = false,
): SpatialFinSource {
    val path =
        when (protocol) {
            MediaProtocol.FILE -> {
                try {
                    if (includePath) {
                        jellyfinRepository.getStreamUrl(itemId, id.orEmpty())
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
