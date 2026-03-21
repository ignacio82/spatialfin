package dev.jdtech.jellyfin.models

import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

interface SpatialFinItem {
    val id: UUID
    val name: String
    val originalTitle: String?
    val overview: String
    val played: Boolean
    val favorite: Boolean
    val canPlay: Boolean
    val canDownload: Boolean
    val sources: List<SpatialFinSource>
    val runtimeTicks: Long
    val playbackPositionTicks: Long
    val unplayedItemCount: Int?
    val images: SpatialFinImages
    val chapters: List<SpatialFinChapter>
    val ratings: List<Rating>
}

suspend fun BaseItemDto.toSpatialFinItem(
    jellyfinRepository: JellyfinRepository,
    serverDatabase: ServerDatabaseDao? = null,
): SpatialFinItem? {
    return when (type) {
        BaseItemKind.MOVIE -> toSpatialFinMovie(jellyfinRepository, serverDatabase)
        BaseItemKind.EPISODE -> toSpatialFinEpisode(jellyfinRepository)
        BaseItemKind.SEASON -> toSpatialFinSeason(jellyfinRepository)
        BaseItemKind.SERIES -> toSpatialFinShow(jellyfinRepository)
        BaseItemKind.BOX_SET -> toSpatialFinBoxSet(jellyfinRepository)
        BaseItemKind.FOLDER -> toSpatialFinFolder(jellyfinRepository)
        else -> null
    }
}

fun SpatialFinItem.isDownloading(): Boolean {
    return sources
        .filter { it.type == SpatialFinSourceType.LOCAL }
        .any { it.path.endsWith(".download") }
}

fun SpatialFinItem.isDownloaded(): Boolean {
    return sources
        .filter { it.type == SpatialFinSourceType.LOCAL }
        .any { !it.path.endsWith(".download") }
}
