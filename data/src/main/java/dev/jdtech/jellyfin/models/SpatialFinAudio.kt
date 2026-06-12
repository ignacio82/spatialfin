package dev.jdtech.jellyfin.models

import android.net.Uri
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.LyricDto
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.PlayAccess

data class SpatialFinAudioTrack(
    override val id: UUID,
    override val name: String,
    override val originalTitle: String?,
    override val overview: String,
    override val played: Boolean,
    override val favorite: Boolean,
    override val canPlay: Boolean,
    override val canDownload: Boolean,
    override val sources: List<SpatialFinSource>,
    override val runtimeTicks: Long,
    override val playbackPositionTicks: Long,
    override val unplayedItemCount: Int? = null,
    override val images: SpatialFinImages,
    override val chapters: List<SpatialFinChapter>,
    override val ratings: List<Rating> = emptyList(),
    val album: String?,
    val albumId: UUID?,
    val albumArtist: String?,
    val artists: List<String>,
    val artistItems: List<AudioContributor>,
    val discNumber: Int?,
    val trackNumber: Int?,
    val playlistItemId: String?,
    val mediaSourceId: String?,
    val container: String?,
    val hasLyrics: Boolean,
    val isAudiobook: Boolean,
) : SpatialFinItem

data class SpatialFinMusicAlbum(
    override val id: UUID,
    override val name: String,
    override val originalTitle: String?,
    override val overview: String,
    override val played: Boolean,
    override val favorite: Boolean,
    override val canPlay: Boolean,
    override val canDownload: Boolean,
    override val sources: List<SpatialFinSource> = emptyList(),
    override val runtimeTicks: Long,
    override val playbackPositionTicks: Long,
    override val unplayedItemCount: Int?,
    override val images: SpatialFinImages,
    override val chapters: List<SpatialFinChapter> = emptyList(),
    override val ratings: List<Rating> = emptyList(),
    val albumArtist: String?,
    val albumArtists: List<AudioContributor>,
    val artists: List<String>,
    val productionYear: Int?,
    val trackCount: Int?,
) : SpatialFinItem

data class SpatialFinMusicArtist(
    override val id: UUID,
    override val name: String,
    override val originalTitle: String?,
    override val overview: String,
    override val played: Boolean,
    override val favorite: Boolean,
    override val canPlay: Boolean,
    override val canDownload: Boolean,
    override val sources: List<SpatialFinSource> = emptyList(),
    override val runtimeTicks: Long = 0L,
    override val playbackPositionTicks: Long = 0L,
    override val unplayedItemCount: Int?,
    override val images: SpatialFinImages,
    override val chapters: List<SpatialFinChapter> = emptyList(),
    override val ratings: List<Rating> = emptyList(),
    val albumCount: Int?,
    val songCount: Int?,
) : SpatialFinItem

data class SpatialFinPlaylist(
    override val id: UUID,
    override val name: String,
    override val originalTitle: String?,
    override val overview: String,
    override val played: Boolean,
    override val favorite: Boolean,
    override val canPlay: Boolean,
    override val canDownload: Boolean,
    override val sources: List<SpatialFinSource> = emptyList(),
    override val runtimeTicks: Long,
    override val playbackPositionTicks: Long = 0L,
    override val unplayedItemCount: Int?,
    override val images: SpatialFinImages,
    override val chapters: List<SpatialFinChapter> = emptyList(),
    override val ratings: List<Rating> = emptyList(),
    val itemCount: Int?,
) : SpatialFinItem

data class SpatialFinAudioBook(
    override val id: UUID,
    override val name: String,
    override val originalTitle: String?,
    override val overview: String,
    override val played: Boolean,
    override val favorite: Boolean,
    override val canPlay: Boolean,
    override val canDownload: Boolean,
    override val sources: List<SpatialFinSource> = emptyList(),
    override val runtimeTicks: Long,
    override val playbackPositionTicks: Long,
    override val unplayedItemCount: Int?,
    override val images: SpatialFinImages,
    override val chapters: List<SpatialFinChapter> = emptyList(),
    override val ratings: List<Rating> = emptyList(),
    val author: String?,
    val trackCount: Int?,
    val synthetic: Boolean,
    val tracks: List<SpatialFinAudioTrack> = emptyList(),
) : SpatialFinItem

data class AudioContributor(val id: UUID?, val name: String)

data class SpatialFinLyrics(
    val itemId: UUID,
    val lines: List<SpatialFinLyricLine>,
) {
    val plainText: String = lines.joinToString("\n") { it.text }
}

data class SpatialFinLyricLine(
    val text: String,
    val startMs: Long?,
)

data class AudioQueueItem(
    val id: UUID,
    val title: String,
    val subtitle: String?,
    val album: String?,
    val artwork: Uri?,
    val durationMs: Long,
    val resumePositionMs: Long,
    val mediaSourceId: String?,
    val chapters: List<SpatialFinChapter>,
    val track: SpatialFinAudioTrack,
)

suspend fun BaseItemDto.toSpatialFinAudioTrack(
    jellyfinRepository: JellyfinRepository,
    forceAudiobook: Boolean = false,
): SpatialFinAudioTrack {
    val sources = mediaSources.orEmpty().map {
        it.toAudioSource(jellyfinRepository, id, includePath = false)
    }
    return SpatialFinAudioTrack(
        id = id,
        name = name.orEmpty(),
        originalTitle = originalTitle,
        overview = overview.orEmpty(),
        played = userData?.played == true,
        favorite = userData?.isFavorite == true,
        canPlay = playAccess != PlayAccess.NONE,
        canDownload = canDownload == true,
        sources = sources,
        runtimeTicks = runTimeTicks ?: 0L,
        playbackPositionTicks = userData?.playbackPositionTicks ?: 0L,
        images = toSpatialFinAudioImages(jellyfinRepository),
        chapters = toSpatialFinChapters(jellyfinRepository, id),
        ratings = toSpatialFinRatings(),
        album = album,
        albumId = albumId,
        albumArtist = albumArtist,
        artists = artists.orEmpty(),
        artistItems = artistItems.orEmpty().map { AudioContributor(it.id, it.name.orEmpty()) },
        discNumber = parentIndexNumber,
        trackNumber = indexNumber,
        playlistItemId = playlistItemId,
        mediaSourceId = sources.firstOrNull()?.id ?: mediaSources.orEmpty().firstOrNull()?.id,
        container = container ?: mediaSources.orEmpty().firstOrNull()?.container,
        hasLyrics = hasLyrics == true,
        isAudiobook = forceAudiobook || type == BaseItemKind.AUDIO_BOOK || mediaType?.serialName == "AudioBook",
    )
}

fun BaseItemDto.toSpatialFinMusicAlbum(
    jellyfinRepository: JellyfinRepository,
): SpatialFinMusicAlbum =
    SpatialFinMusicAlbum(
        id = id,
        name = name.orEmpty(),
        originalTitle = originalTitle,
        overview = overview.orEmpty(),
        played = userData?.played == true,
        favorite = userData?.isFavorite == true,
        canPlay = true,
        canDownload = canDownload == true,
        runtimeTicks = cumulativeRunTimeTicks ?: runTimeTicks ?: 0L,
        playbackPositionTicks = userData?.playbackPositionTicks ?: 0L,
        unplayedItemCount = userData?.unplayedItemCount,
        images = toSpatialFinAudioImages(jellyfinRepository),
        ratings = toSpatialFinRatings(),
        albumArtist = albumArtist,
        albumArtists = albumArtists.orEmpty().map { AudioContributor(it.id, it.name.orEmpty()) },
        artists = artists.orEmpty(),
        productionYear = productionYear,
        trackCount = songCount ?: childCount,
    )

fun BaseItemDto.toSpatialFinMusicArtist(
    jellyfinRepository: JellyfinRepository,
): SpatialFinMusicArtist =
    SpatialFinMusicArtist(
        id = id,
        name = name.orEmpty(),
        originalTitle = originalTitle,
        overview = overview.orEmpty(),
        played = userData?.played == true,
        favorite = userData?.isFavorite == true,
        canPlay = true,
        canDownload = false,
        unplayedItemCount = userData?.unplayedItemCount,
        images = toSpatialFinAudioImages(jellyfinRepository),
        ratings = toSpatialFinRatings(),
        albumCount = albumCount,
        songCount = songCount,
    )

fun BaseItemDto.toSpatialFinPlaylist(
    jellyfinRepository: JellyfinRepository,
): SpatialFinPlaylist =
    SpatialFinPlaylist(
        id = id,
        name = name.orEmpty(),
        originalTitle = originalTitle,
        overview = overview.orEmpty(),
        played = userData?.played == true,
        favorite = userData?.isFavorite == true,
        canPlay = true,
        canDownload = false,
        runtimeTicks = cumulativeRunTimeTicks ?: runTimeTicks ?: 0L,
        unplayedItemCount = childCount ?: recursiveItemCount,
        images = toSpatialFinAudioImages(jellyfinRepository),
        ratings = toSpatialFinRatings(),
        itemCount = childCount ?: recursiveItemCount,
    )

fun BaseItemDto.toSpatialFinAudioBook(
    jellyfinRepository: JellyfinRepository,
): SpatialFinAudioBook =
    SpatialFinAudioBook(
        id = id,
        name = name.orEmpty(),
        originalTitle = originalTitle,
        overview = overview.orEmpty(),
        played = userData?.played == true,
        favorite = userData?.isFavorite == true,
        canPlay = playAccess != PlayAccess.NONE || type == BaseItemKind.FOLDER,
        canDownload = canDownload == true,
        runtimeTicks = cumulativeRunTimeTicks ?: runTimeTicks ?: 0L,
        playbackPositionTicks = userData?.playbackPositionTicks ?: 0L,
        unplayedItemCount = userData?.unplayedItemCount ?: childCount,
        images = toSpatialFinAudioImages(jellyfinRepository),
        chapters = toSpatialFinChapters(jellyfinRepository, id),
        ratings = toSpatialFinRatings(),
        author = artists?.firstOrNull() ?: people?.firstOrNull()?.name,
        trackCount = songCount ?: childCount,
        synthetic = false,
    )

fun List<SpatialFinAudioTrack>.groupLooseAudioBooks(): List<SpatialFinAudioBook> =
    filter { it.albumId != null || !it.album.isNullOrBlank() }
        .groupBy { it.albumId?.toString() ?: it.album.orEmpty().lowercase() }
        .values
        .mapNotNull { tracks ->
            val ordered = tracks.sortedForAlbumPlayback()
            val first = ordered.firstOrNull() ?: return@mapNotNull null
            val stableKey = first.albumId?.toString() ?: first.album.orEmpty()
            SpatialFinAudioBook(
                id = first.albumId ?: first.id,
                name = first.album?.takeIf { it.isNotBlank() } ?: first.name,
                originalTitle = null,
                overview = first.albumArtist.orEmpty(),
                played = ordered.all { it.played },
                favorite = ordered.any { it.favorite },
                canPlay = ordered.any { it.canPlay },
                canDownload = false,
                runtimeTicks = ordered.sumOf { it.runtimeTicks },
                playbackPositionTicks = ordered.firstOrNull { it.playbackPositionTicks > 0L }?.playbackPositionTicks ?: 0L,
                unplayedItemCount = ordered.count { !it.played }.takeIf { it > 0 },
                images = first.images,
                chapters = emptyList(),
                ratings = emptyList(),
                author = first.albumArtist ?: first.artists.firstOrNull(),
                trackCount = ordered.size,
                synthetic = true,
                tracks = ordered,
            )
        }
        .sortedBy { it.name.lowercase() }

fun List<SpatialFinAudioTrack>.sortedForAlbumPlayback(): List<SpatialFinAudioTrack> =
    sortedWith(
        compareBy<SpatialFinAudioTrack>(
            { it.discNumber ?: Int.MAX_VALUE },
            { it.trackNumber ?: Int.MAX_VALUE },
            { it.name.lowercase() },
        )
    )

fun List<SpatialFinAudioTrack>.sortedForPlaylistPlayback(): List<SpatialFinAudioTrack> =
    sortedWith(
        compareBy<SpatialFinAudioTrack>(
            { it.trackNumber ?: Int.MAX_VALUE },
            { it.name.lowercase() },
        )
    )

fun SpatialFinAudioTrack.toAudioQueueItem(): AudioQueueItem =
    AudioQueueItem(
        id = id,
        title = name,
        subtitle = artists.joinToString(", ").ifBlank { albumArtist },
        album = album,
        artwork = images.primary,
        durationMs = runtimeTicks / 10_000L,
        resumePositionMs = playbackPositionTicks / 10_000L,
        mediaSourceId = mediaSourceId,
        chapters = chapters,
        track = this,
    )

fun List<SpatialFinAudioTrack>.toAudioQueueItems(): List<AudioQueueItem> =
    map { it.toAudioQueueItem() }

fun LyricDto.toSpatialFinLyrics(itemId: UUID): SpatialFinLyrics =
    SpatialFinLyrics(
        itemId = itemId,
        lines =
            lyrics.mapNotNull { line ->
                val text = line.text.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                // LyricDto.start is in Jellyfin ticks (100 ns), not milliseconds.
                SpatialFinLyricLine(text = text, startMs = line.start?.let { it / 10_000L })
            },
    )

private suspend fun MediaSourceInfo.toAudioSource(
    jellyfinRepository: JellyfinRepository,
    itemId: UUID,
    includePath: Boolean,
): SpatialFinSource =
    toSpatialFinSource(jellyfinRepository, itemId, includePath)

private fun BaseItemDto.toSpatialFinAudioImages(
    jellyfinRepository: JellyfinRepository,
): SpatialFinImages {
    val standard = toSpatialFinImages(jellyfinRepository)
    if (standard.primary != null) return standard

    val baseUrl = Uri.parse(jellyfinRepository.getBaseUrl())
    val albumPrimary =
        if (albumId != null && !albumPrimaryImageTag.isNullOrBlank()) {
            baseUrl
                .buildUpon()
                .appendEncodedPath("items/$albumId/Images/${ImageType.PRIMARY}")
                .appendQueryParameter("tag", albumPrimaryImageTag)
                .build()
        } else {
            null
        }
    val parentPrimary =
        if (parentPrimaryImageItemId != null && !parentPrimaryImageTag.isNullOrBlank()) {
            baseUrl
                .buildUpon()
                .appendEncodedPath("items/$parentPrimaryImageItemId/Images/${ImageType.PRIMARY}")
                .appendQueryParameter("tag", parentPrimaryImageTag)
                .build()
        } else {
            null
        }
    return standard.copy(primary = albumPrimary ?: parentPrimary)
}
