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
        // Home-video / music-video / trailer libraries hand back standalone playable
        // videos rather than Movie items. They carry the same shape (media sources,
        // runtime, images, user data), so reuse the movie mapping instead of dropping
        // them — otherwise a server whose media is not filed under a Movies or TV
        // library renders as an empty app.
        BaseItemKind.MOVIE,
        BaseItemKind.VIDEO,
        BaseItemKind.MUSIC_VIDEO,
        BaseItemKind.TRAILER -> toSpatialFinMovie(jellyfinRepository, serverDatabase)
        BaseItemKind.EPISODE -> toSpatialFinEpisode(jellyfinRepository)
        BaseItemKind.SEASON -> toSpatialFinSeason(jellyfinRepository)
        BaseItemKind.SERIES -> toSpatialFinShow(jellyfinRepository)
        BaseItemKind.BOX_SET -> toSpatialFinBoxSet(jellyfinRepository)
        // A PhotoAlbum is just the folder a set of stills lives in — browse it like
        // any other folder; the photo viewer pages through its contents.
        BaseItemKind.PHOTO -> toSpatialFinPhoto(jellyfinRepository)
        BaseItemKind.PHOTO_ALBUM,
        BaseItemKind.FOLDER -> toSpatialFinFolder(jellyfinRepository)
        BaseItemKind.AUDIO -> toSpatialFinAudioTrack(jellyfinRepository)
        BaseItemKind.MUSIC_ALBUM -> toSpatialFinMusicAlbum(jellyfinRepository)
        BaseItemKind.MUSIC_ARTIST -> toSpatialFinMusicArtist(jellyfinRepository)
        BaseItemKind.PLAYLIST -> toSpatialFinPlaylist(jellyfinRepository)
        BaseItemKind.AUDIO_BOOK,
        BaseItemKind.BOOK -> toSpatialFinAudioBook(jellyfinRepository)
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
