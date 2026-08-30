package dev.jdtech.jellyfin.repository

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.downloads.DownloadStorageManager
import dev.jdtech.jellyfin.models.SpatialFinCollection
import dev.jdtech.jellyfin.models.SpatialFinAudioBook
import dev.jdtech.jellyfin.models.SpatialFinAudioTrack
import dev.jdtech.jellyfin.models.SpatialFinLyrics
import dev.jdtech.jellyfin.models.SpatialFinMusicAlbum
import dev.jdtech.jellyfin.models.SpatialFinMusicArtist
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinPerson
import dev.jdtech.jellyfin.models.SpatialFinPlaylist
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.SpatialFinSegment
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.SyncPlayGroup
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.models.groupLooseAudioBooks
import dev.jdtech.jellyfin.models.sortedForAlbumPlayback
import dev.jdtech.jellyfin.models.sortedForPlaylistPlayback
import dev.jdtech.jellyfin.models.toSpatialFinAudioBook
import dev.jdtech.jellyfin.models.toSpatialFinAudioTrack
import dev.jdtech.jellyfin.models.toSpatialFinCollection
import dev.jdtech.jellyfin.models.toSpatialFinEpisode
import dev.jdtech.jellyfin.models.toSpatialFinItem
import dev.jdtech.jellyfin.models.toSpatialFinLyrics
import dev.jdtech.jellyfin.models.toSpatialFinMusicAlbum
import dev.jdtech.jellyfin.models.toSpatialFinMusicArtist
import dev.jdtech.jellyfin.models.toSpatialFinMovie
import dev.jdtech.jellyfin.models.toSpatialFinPerson
import dev.jdtech.jellyfin.models.toSpatialFinPlaylist
import dev.jdtech.jellyfin.models.toSpatialFinSeason
import dev.jdtech.jellyfin.models.toSpatialFinSegment
import dev.jdtech.jellyfin.models.toSpatialFinSegmentsDto
import dev.jdtech.jellyfin.models.toSpatialFinShow
import dev.jdtech.jellyfin.models.toSpatialFinSource
import dev.jdtech.jellyfin.models.toSyncPlayGroup
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import org.jellyfin.sdk.api.sockets.SocketApiState
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.operations.VideoAttachmentsApi
import org.jellyfin.sdk.api.sockets.subscribeGeneralCommands
import org.jellyfin.sdk.api.sockets.subscribePlayStateCommands
import org.jellyfin.sdk.api.sockets.subscribeSyncPlayCommands
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.DeviceOptionsDto
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.GeneralCommand
import org.jellyfin.sdk.model.api.GeneralCommandMessage
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.GroupInfoDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.LibraryChangedMessage
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlayRequestDto
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.PlaystateCommand
import org.jellyfin.sdk.model.api.PlaystateMessage
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.SeekRequestDto
import org.jellyfin.sdk.model.api.SendCommandType
import org.jellyfin.sdk.model.api.SyncPlayCommandMessage
import org.jellyfin.sdk.model.api.SyncPlayGroupUpdateMessage
import org.jellyfin.sdk.model.api.SortOrder as ItemSortOrder
import org.jellyfin.sdk.model.api.DirectPlayProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.TranscodeSeekInfo
import org.jellyfin.sdk.model.api.TranscodingProfile
import org.jellyfin.sdk.model.api.JoinGroupRequestDto
import org.jellyfin.sdk.model.api.NewGroupRequestDto
import org.jellyfin.sdk.model.api.NextItemRequestDto
import org.jellyfin.sdk.model.api.PreviousItemRequestDto
import org.jellyfin.sdk.model.api.request.GetUniversalAudioStreamRequest
import org.jellyfin.sdk.model.api.UserDataChangeInfo
import org.jellyfin.sdk.model.api.UserDataChangedMessage
import org.jellyfin.sdk.model.api.UserConfiguration
import timber.log.Timber

class JellyfinRepositoryImpl(
    private val context: Context,
    private val jellyfinApi: JellyfinApi,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
    private val downloadStorageManager: DownloadStorageManager,
) : JellyfinRepository {
    override suspend fun getPublicSystemInfo(): PublicSystemInfo =
        withContext(Dispatchers.IO) { jellyfinApi.systemApi.getPublicSystemInfo().content }

    override suspend fun getUserViews(): List<BaseItemDto> =
        withContext(Dispatchers.IO) {
            jellyfinApi.viewsApi.getUserViews(jellyfinApi.userId!!).content.items
        }

    override suspend fun getEpisode(itemId: UUID): SpatialFinEpisode =
        withContext(Dispatchers.IO) {
            downloadStorageManager.reconcileItem(itemId, jellyfinApi.userId)
            jellyfinApi.userLibraryApi
                .getItem(itemId, jellyfinApi.userId!!)
                .content
                .toSpatialFinEpisode(this@JellyfinRepositoryImpl, database)!!
        }

    override suspend fun getMovie(itemId: UUID): SpatialFinMovie =
        withContext(Dispatchers.IO) {
            downloadStorageManager.reconcileItem(itemId, jellyfinApi.userId)
            jellyfinApi.userLibraryApi
                .getItem(itemId, jellyfinApi.userId!!)
                .content
                .toSpatialFinMovie(this@JellyfinRepositoryImpl, database)
        }

    override suspend fun getShow(itemId: UUID): SpatialFinShow =
        withContext(Dispatchers.IO) {
            jellyfinApi.userLibraryApi
                .getItem(itemId, jellyfinApi.userId!!)
                .content
                .toSpatialFinShow(this@JellyfinRepositoryImpl)
        }

    override suspend fun getSeason(itemId: UUID): SpatialFinSeason =
        withContext(Dispatchers.IO) {
            jellyfinApi.userLibraryApi
                .getItem(itemId, jellyfinApi.userId!!)
                .content
                .toSpatialFinSeason(this@JellyfinRepositoryImpl)
        }

    override suspend fun getLibraries(): List<SpatialFinCollection> =
        withContext(Dispatchers.IO) {
            jellyfinApi.itemsApi.getItems(jellyfinApi.userId!!).content.items.mapNotNull {
                it.toSpatialFinCollection(this@JellyfinRepositoryImpl)
            }
        }

    override suspend fun getItem(itemId: UUID): SpatialFinItem? =
        withContext(Dispatchers.IO) {
            jellyfinApi.userLibraryApi
                .getItem(itemId = itemId, userId = jellyfinApi.userId!!)
                .content
                .toSpatialFinItem(this@JellyfinRepositoryImpl)
        }

    override suspend fun getItems(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
        startIndex: Int?,
        limit: Int?,
    ): List<SpatialFinItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.itemsApi
                .getItems(
                    jellyfinApi.userId!!,
                    parentId = parentId,
                    includeItemTypes = includeTypes,
                    recursive = recursive,
                    sortBy = listOf(ItemSortBy.fromName(sortBy.sortString)),
                    sortOrder = listOf(ItemSortOrder.fromName(sortOrder.sortString)),
                    startIndex = startIndex,
                    limit = limit,
                    fields = BROWSE_ITEM_FIELDS,
                )
                .content
                .items
                .let(SeriesFilter::dropEmptyShows)
                .mapNotNull { it.toSpatialFinItem(this@JellyfinRepositoryImpl, database) }
        }

    override suspend fun getItemsPaging(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
    ): Flow<PagingData<SpatialFinItem>> {
        return Pager(
                config = PagingConfig(pageSize = pageSizeForDevice(), enablePlaceholders = false),
                pagingSourceFactory = {
                    ItemsPagingSource(this, parentId, includeTypes, recursive, sortBy, sortOrder)
                },
            )
            .flow
    }

    // Larger screens (TV, XR) show enough items per page that a 10-item fetch
    // forces a round-trip almost immediately; bump them up. Phone/Beam stays
    // smaller to keep the initial-paint cost low.
    private fun pageSizeForDevice(): Int {
        val pm = context.packageManager
        return when {
            pm.hasSystemFeature("android.software.xr.api.spatial") -> 50
            pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) -> 50
            else -> 25
        }
    }

    override suspend fun getPerson(personId: UUID): SpatialFinPerson =
        withContext(Dispatchers.IO) {
            jellyfinApi.userLibraryApi
                .getItem(personId, jellyfinApi.userId!!)
                .content
                .toSpatialFinPerson(this@JellyfinRepositoryImpl)
        }

    override suspend fun getPersonItems(
        personIds: List<UUID>,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
    ): List<SpatialFinItem> =
        withContext(Dispatchers.IO) {
            val userId = jellyfinApi.userId ?: return@withContext emptyList()
            jellyfinApi.itemsApi
                .getItems(
                    userId,
                    personIds = personIds,
                    includeItemTypes = includeTypes,
                    recursive = recursive,
                    fields = BROWSE_ITEM_FIELDS,
                )
                .content
                ?.items
                .let(SeriesFilter::dropEmptyShows)
                .mapNotNull { it.toSpatialFinItem(this@JellyfinRepositoryImpl, database) }
        }

    override suspend fun getFavoriteItems(): List<SpatialFinItem> =
        withContext(Dispatchers.IO) {
            val userId = jellyfinApi.userId ?: return@withContext emptyList()
            jellyfinApi.itemsApi
                .getItems(
                    userId,
                    filters = listOf(ItemFilter.IS_FAVORITE),
                    includeItemTypes =
                        listOf(
                            BaseItemKind.MOVIE,
                            BaseItemKind.SERIES,
                            BaseItemKind.EPISODE,
                            BaseItemKind.VIDEO,
                            BaseItemKind.MUSIC_VIDEO,
                        ),
                    recursive = true,
                    fields = BROWSE_ITEM_FIELDS,
                )
                .content
                ?.items
                .let(SeriesFilter::dropEmptyShows)
                .mapNotNull { it.toSpatialFinItem(this@JellyfinRepositoryImpl, database) }
        }

    override suspend fun getSearchItems(query: String): List<SpatialFinItem> =
        withContext(Dispatchers.IO) {
            val userId = jellyfinApi.userId ?: return@withContext emptyList()
            jellyfinApi.itemsApi
                .getItems(
                    userId,
                    searchTerm = query,
                    includeItemTypes =
                        listOf(
                            BaseItemKind.MOVIE,
                            BaseItemKind.SERIES,
                            BaseItemKind.EPISODE,
                            BaseItemKind.BOX_SET,
                            // Home-video / music-video libraries store playable files as
                            // Video / MusicVideo; without these they are unsearchable.
                            BaseItemKind.VIDEO,
                            BaseItemKind.MUSIC_VIDEO,
                        ),
                    recursive = true,
                    fields = BROWSE_ITEM_FIELDS,
                )
                .content
                ?.items
                .let(SeriesFilter::dropEmptyShows)
                .mapNotNull { it.toSpatialFinItem(this@JellyfinRepositoryImpl, database) }
        }

    override suspend fun getAudioAlbums(
        parentId: UUID?,
        artistId: UUID?,
        limit: Int?,
    ): List<SpatialFinMusicAlbum> =
        withContext(Dispatchers.IO) {
            jellyfinApi.itemsApi
                .getItems(
                    jellyfinApi.userId!!,
                    parentId = parentId,
                    artistIds = artistId?.let(::listOf),
                    includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                    recursive = true,
                    sortBy = listOf(ItemSortBy.SORT_NAME),
                    sortOrder = listOf(ItemSortOrder.ASCENDING),
                    limit = limit,
                    fields = AUDIO_ITEM_FIELDS,
                )
                .content
                .items
                .map { it.toSpatialFinMusicAlbum(this@JellyfinRepositoryImpl) }
        }

    override suspend fun getAudioAlbumTracks(albumId: UUID): List<SpatialFinAudioTrack> =
        withContext(Dispatchers.IO) {
            jellyfinApi.itemsApi
                .getItems(
                    jellyfinApi.userId!!,
                    parentId = albumId,
                    includeItemTypes = listOf(BaseItemKind.AUDIO),
                    // recursive=true + a non-library parentId returns 0 items on Jellyfin
                    // 10.11 (recursive queries only resolve library roots as ancestors).
                    // Albums are flat, so a direct-children query is correct anyway.
                    recursive = false,
                    sortBy = listOf(ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME),
                    sortOrder = listOf(ItemSortOrder.ASCENDING),
                    fields = AUDIO_ITEM_FIELDS,
                )
                .content
                .items
                .map { it.toSpatialFinAudioTrack(this@JellyfinRepositoryImpl) }
                .sortedForAlbumPlayback()
        }

    override suspend fun getAudioArtists(
        parentId: UUID?,
        limit: Int?,
    ): List<SpatialFinMusicArtist> =
        withContext(Dispatchers.IO) {
            // /Artists/AlbumArtists, not Items?includeItemTypes=MusicArtist: the Items
            // query returns folder-backed artist entries whose ids don't match the
            // ArtistItems links on tracks, so opening one finds zero albums/tracks.
            jellyfinApi.artistsApi
                .getAlbumArtists(
                    userId = jellyfinApi.userId!!,
                    parentId = parentId,
                    limit = limit,
                    sortBy = listOf(ItemSortBy.SORT_NAME),
                    sortOrder = listOf(ItemSortOrder.ASCENDING),
                    fields = listOf(ItemFields.ITEM_COUNTS, ItemFields.OVERVIEW),
                )
                .content
                .items
                .map { it.toSpatialFinMusicArtist(this@JellyfinRepositoryImpl) }
        }

    override suspend fun getAudioArtistTracks(
        parentId: UUID?,
        artistId: UUID,
        limit: Int?,
    ): List<SpatialFinAudioTrack> =
        withContext(Dispatchers.IO) {
            jellyfinApi.itemsApi
                .getItems(
                    jellyfinApi.userId!!,
                    parentId = parentId,
                    artistIds = listOf(artistId),
                    includeItemTypes = listOf(BaseItemKind.AUDIO),
                    recursive = true,
                    sortBy = listOf(ItemSortBy.ALBUM, ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME),
                    sortOrder = listOf(ItemSortOrder.ASCENDING),
                    limit = limit,
                    fields = AUDIO_ITEM_FIELDS,
                )
                .content
                .items
                .map { it.toSpatialFinAudioTrack(this@JellyfinRepositoryImpl) }
                .sortedForAlbumPlayback()
        }

    override suspend fun getAudioSongs(parentId: UUID?, limit: Int?): List<SpatialFinAudioTrack> =
        withContext(Dispatchers.IO) {
            jellyfinApi.itemsApi
                .getItems(
                    jellyfinApi.userId!!,
                    parentId = parentId,
                    includeItemTypes = listOf(BaseItemKind.AUDIO),
                    recursive = true,
                    sortBy = listOf(ItemSortBy.SORT_NAME),
                    sortOrder = listOf(ItemSortOrder.ASCENDING),
                    limit = limit,
                    fields = AUDIO_ITEM_FIELDS,
                )
                .content
                .items
                .map { it.toSpatialFinAudioTrack(this@JellyfinRepositoryImpl) }
        }

    override suspend fun getAudioPlaylists(parentId: UUID?, limit: Int?): List<SpatialFinPlaylist> =
        withContext(Dispatchers.IO) {
            jellyfinApi.itemsApi
                .getItems(
                    jellyfinApi.userId!!,
                    parentId = parentId,
                    includeItemTypes = listOf(BaseItemKind.PLAYLIST),
                    recursive = parentId != null,
                    sortBy = listOf(ItemSortBy.SORT_NAME),
                    sortOrder = listOf(ItemSortOrder.ASCENDING),
                    limit = limit,
                    fields = BROWSE_ITEM_FIELDS + ItemFields.OVERVIEW,
                )
                .content
                .items
                .map { it.toSpatialFinPlaylist(this@JellyfinRepositoryImpl) }
        }

    override suspend fun getAudioPlaylistTracks(playlistId: UUID): List<SpatialFinAudioTrack> =
        withContext(Dispatchers.IO) {
            jellyfinApi.playlistsApi
                .getPlaylistItems(
                    playlistId = playlistId,
                    userId = jellyfinApi.userId!!,
                    fields = AUDIO_ITEM_FIELDS,
                    enableImages = true,
                    enableUserData = true,
                    imageTypeLimit = 1,
                    enableImageTypes = listOf(ImageType.PRIMARY),
                )
                .content
                .items
                .filter { it.type == BaseItemKind.AUDIO || it.type == BaseItemKind.AUDIO_BOOK }
                .mapIndexed { index, item ->
                    item.toSpatialFinAudioTrack(this@JellyfinRepositoryImpl, forceAudiobook = item.type == BaseItemKind.AUDIO_BOOK)
                        .copy(trackNumber = index)
                }
                .sortedForPlaylistPlayback()
        }

    override suspend fun getAudioBooks(parentId: UUID?, limit: Int?): List<SpatialFinAudioBook> =
        withContext(Dispatchers.IO) {
            val explicitBooks =
                jellyfinApi.itemsApi
                    .getItems(
                        jellyfinApi.userId!!,
                        parentId = parentId,
                        includeItemTypes = listOf(BaseItemKind.AUDIO_BOOK, BaseItemKind.BOOK, BaseItemKind.FOLDER, BaseItemKind.MUSIC_ALBUM),
                        recursive = false,
                        sortBy = listOf(ItemSortBy.SORT_NAME),
                        sortOrder = listOf(ItemSortOrder.ASCENDING),
                        limit = limit,
                        fields = AUDIO_ITEM_FIELDS + BROWSE_ITEM_FIELDS,
                    )
                    .content
                    .items
                    .filter { it.type != BaseItemKind.FOLDER || it.childCount != null || it.recursiveItemCount != null }
                    .map { it.toSpatialFinAudioBook(this@JellyfinRepositoryImpl) }

            val looseBooks =
                jellyfinApi.itemsApi
                    .getItems(
                        jellyfinApi.userId!!,
                        parentId = parentId,
                        includeItemTypes = listOf(BaseItemKind.AUDIO),
                        recursive = true,
                        sortBy = listOf(ItemSortBy.ALBUM, ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME),
                        sortOrder = listOf(ItemSortOrder.ASCENDING),
                        fields = AUDIO_ITEM_FIELDS,
                    )
                    .content
                    .items
                    .map { it.toSpatialFinAudioTrack(this@JellyfinRepositoryImpl, forceAudiobook = true) }
                    .groupLooseAudioBooks()

            (explicitBooks + looseBooks)
                .distinctBy { it.id }
                .sortedBy { it.name.lowercase() }
                .let { books -> limit?.let(books::take) ?: books }
        }

    override suspend fun getAudioBookTracks(bookId: UUID): List<SpatialFinAudioTrack> =
        withContext(Dispatchers.IO) {
            val item = runCatching {
                jellyfinApi.userLibraryApi.getItem(itemId = bookId, userId = jellyfinApi.userId!!).content
            }.getOrNull()

            when (item?.type) {
                BaseItemKind.AUDIO ->
                    jellyfinApi.itemsApi
                        .getItems(
                            jellyfinApi.userId!!,
                            parentId = item.parentId,
                            includeItemTypes = listOf(BaseItemKind.AUDIO),
                            recursive = false,
                            sortBy = listOf(ItemSortBy.ALBUM, ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME),
                            sortOrder = listOf(ItemSortOrder.ASCENDING),
                            fields = AUDIO_ITEM_FIELDS,
                        )
                        .content
                        .items
                        .filter { candidate ->
                            !item.album.isNullOrBlank() && candidate.album == item.album ||
                                item.albumId != null && candidate.albumId == item.albumId
                        }
                        .map { it.toSpatialFinAudioTrack(this@JellyfinRepositoryImpl, forceAudiobook = true) }
                        .sortedForAlbumPlayback()
                        .ifEmpty { listOf(item.toSpatialFinAudioTrack(this@JellyfinRepositoryImpl, forceAudiobook = true)) }

                BaseItemKind.MUSIC_ALBUM ->
                    getAudioAlbumTracks(bookId).map { it.copy(isAudiobook = true) }

                else ->
                    collectAudioDescendants(bookId)
                        .map { it.toSpatialFinAudioTrack(this@JellyfinRepositoryImpl, forceAudiobook = true) }
                        .sortedForAlbumPlayback()
            }
        }

    /**
     * Depth-first fetch of all audio files under a folder-like item. Needed because
     * recursive=true with a non-library parentId returns 0 items on Jellyfin 10.11,
     * so nested audiobook folders (book/disc subfolders) must be walked client-side.
     */
    private suspend fun collectAudioDescendants(parentId: UUID, depth: Int = 0): List<BaseItemDto> {
        if (depth > 4) return emptyList()
        val children =
            jellyfinApi.itemsApi
                .getItems(
                    jellyfinApi.userId!!,
                    parentId = parentId,
                    recursive = false,
                    sortBy = listOf(ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME),
                    sortOrder = listOf(ItemSortOrder.ASCENDING),
                    fields = AUDIO_ITEM_FIELDS,
                )
                .content
                .items
        return children.flatMap { child ->
            when (child.type) {
                BaseItemKind.AUDIO, BaseItemKind.AUDIO_BOOK -> listOf(child)
                BaseItemKind.FOLDER, BaseItemKind.MUSIC_ALBUM ->
                    collectAudioDescendants(child.id, depth + 1)
                else -> emptyList()
            }
        }
    }

    override suspend fun getAudioSearchItems(
        query: String,
        parentId: UUID?,
        limit: Int?,
    ): List<SpatialFinItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.itemsApi
                .getItems(
                    jellyfinApi.userId!!,
                    parentId = parentId,
                    searchTerm = query,
                    includeItemTypes =
                        listOf(
                            BaseItemKind.AUDIO,
                            BaseItemKind.MUSIC_ALBUM,
                            BaseItemKind.MUSIC_ARTIST,
                            BaseItemKind.PLAYLIST,
                            BaseItemKind.AUDIO_BOOK,
                            BaseItemKind.BOOK,
                        ),
                    recursive = true,
                    sortBy = listOf(ItemSortBy.SORT_NAME),
                    sortOrder = listOf(ItemSortOrder.ASCENDING),
                    limit = limit,
                    fields = AUDIO_ITEM_FIELDS + BROWSE_ITEM_FIELDS,
                )
                .content
                .items
                .mapNotNull { it.toSpatialFinItem(this@JellyfinRepositoryImpl, database) }
        }

    override suspend fun getAudioTrack(itemId: UUID): SpatialFinAudioTrack? =
        withContext(Dispatchers.IO) {
            runCatching {
                jellyfinApi.userLibraryApi
                    .getItem(itemId = itemId, userId = jellyfinApi.userId!!)
                    .content
                    .toSpatialFinAudioTrack(this@JellyfinRepositoryImpl)
            }.getOrNull()
        }

    override suspend fun getAudioLyrics(itemId: UUID): SpatialFinLyrics? =
        withContext(Dispatchers.IO) {
            runCatching {
                jellyfinApi.lyricsApi.getLyrics(itemId).content.toSpatialFinLyrics(itemId)
            }.getOrElse {
                Timber.d(it, "No lyrics available for audio item %s", itemId)
                null
            }
        }

    override suspend fun getSuggestions(): List<SpatialFinItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.suggestionsApi
                .getSuggestions(
                    jellyfinApi.userId!!,
                    limit = 6,
                    type = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                )
                .content
                .items
                .let(SeriesFilter::dropEmptyShows)
                .mapNotNull { it.toSpatialFinItem(this@JellyfinRepositoryImpl, database) }
        }

    override suspend fun getResumeItems(): List<SpatialFinItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.itemsApi
                .getResumeItems(
                    jellyfinApi.userId!!,
                    limit = 12,
                    includeItemTypes =
                        listOf(
                            BaseItemKind.MOVIE,
                            BaseItemKind.EPISODE,
                            BaseItemKind.VIDEO,
                            BaseItemKind.MUSIC_VIDEO,
                        ),
                )
                .content
                .items
                .mapNotNull { it.toSpatialFinItem(this@JellyfinRepositoryImpl, database) }
                .let(ResumeFilter::keepResumable)
        }

    override suspend fun getLatestMedia(parentId: UUID): List<SpatialFinItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.userLibraryApi
                .getLatestMedia(
                    jellyfinApi.userId!!,
                    parentId = parentId,
                    limit = 16,
                    fields = BROWSE_ITEM_FIELDS,
                )
                .content
                .let(SeriesFilter::dropEmptyShows)
                .mapNotNull { it.toSpatialFinItem(this@JellyfinRepositoryImpl, database) }
        }

    override suspend fun getSeasons(seriesId: UUID, offline: Boolean): List<SpatialFinSeason> =
        withContext(Dispatchers.IO) {
            if (!offline) {
                jellyfinApi.showsApi.getSeasons(seriesId, jellyfinApi.userId!!).content.items.map {
                    it.toSpatialFinSeason(this@JellyfinRepositoryImpl)
                }
            } else {
                database.getSeasonsByShowId(seriesId).map {
                    it.toSpatialFinSeason(database, jellyfinApi.userId!!)
                }
            }
        }

    override suspend fun getNextUp(seriesId: UUID?): List<SpatialFinEpisode> =
        withContext(Dispatchers.IO) {
            jellyfinApi.showsApi
                .getNextUp(
                    jellyfinApi.userId!!,
                    limit = 24,
                    seriesId = seriesId,
                    enableResumable = false,
                )
                .content
                .items
                .mapNotNull { it.toSpatialFinEpisode(this@JellyfinRepositoryImpl) }
        }

    override suspend fun getEpisodes(
        seriesId: UUID,
        seasonId: UUID,
        fields: List<ItemFields>?,
        startItemId: UUID?,
        limit: Int?,
        offline: Boolean,
    ): List<SpatialFinEpisode> =
        withContext(Dispatchers.IO) {
            if (!offline) {
                jellyfinApi.showsApi
                    .getEpisodes(
                        seriesId,
                        jellyfinApi.userId!!,
                        seasonId = seasonId,
                        fields = fields,
                        startItemId = startItemId,
                        limit = limit,
                    )
                    .content
                    .items
                    .mapNotNull { it.toSpatialFinEpisode(this@JellyfinRepositoryImpl, database) }
            } else {
                database.getEpisodesBySeasonId(seasonId).map {
                    it.toSpatialFinEpisode(database, jellyfinApi.userId!!)
                }
            }
        }

    override suspend fun getMediaSources(
        itemId: UUID,
        includePath: Boolean,
        maxBitrate: Long?,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        mediaSourceId: String?,
    ): List<SpatialFinSource> =
        withContext(Dispatchers.IO) {
            downloadStorageManager.reconcileItem(itemId, jellyfinApi.userId)
            // Force-direct-play overrides any caller-supplied cap so the server
            // is told the device can direct-play at arbitrary bitrates.
            val forceDirectPlay = appPreferences.getValue(appPreferences.playerForceDirectPlay)
            val bitrate = when {
                forceDirectPlay -> 1_000_000_000L
                else -> (maxBitrate ?: appPreferences.getValue(appPreferences.playerMaxBitrate)).let {
                    if (it <= 0L) 1_000_000_000L else it
                }
            }
            val sources = mutableListOf<SpatialFinSource>()
            val playbackInfo = jellyfinApi.mediaInfoApi
                    .getPostedPlaybackInfo(
                        itemId,
                        PlaybackInfoDto(
                            userId = jellyfinApi.userId!!,
                            deviceProfile = createPlaybackDeviceProfile(
                                bitrate = bitrate,
                                forceDirectPlay = forceDirectPlay,
                                passthroughMode = AudioPassthroughMode.fromPreference(
                                    appPreferences.getValue(appPreferences.playerAudioPassthrough),
                                ),
                            ),
                            maxStreamingBitrate = bitrate.toInt(),
                            // Jellyfin applies AudioStreamIndex/SubtitleStreamIndex only to
                            // the source the request identifies; without MediaSourceId it
                            // drops both and transcodes the container's default track.
                            mediaSourceId = mediaSourceId,
                            audioStreamIndex = audioStreamIndex.takeIf { mediaSourceId != null },
                            subtitleStreamIndex = subtitleStreamIndex.takeIf { mediaSourceId != null },
                        ),
                    )
                    .content
            for (ms in playbackInfo.mediaSources) {
                Timber.i(
                    "getMediaSources: source=%s directPlay=%b directStream=%b transcoding=%b transcodingUrl=%s",
                    ms.id, ms.supportsDirectPlay, ms.supportsDirectStream, ms.supportsTranscoding, ms.transcodingUrl,
                )
            }
            sources.addAll(
                playbackInfo.mediaSources
                    .map {
                        val sf = it.toSpatialFinSource(this@JellyfinRepositoryImpl, itemId, includePath, audioStreamIndex)
                        Timber.i("getMediaSources: result path=%s supportsDirectPlay=%b transcodingUrl=%s", sf.path.take(200), sf.supportsDirectPlay, sf.transcodingUrl?.take(200))
                        sf
                    }
            )
            sources.addAll(database.getSources(itemId).map { it.toSpatialFinSource(database) })
            sources
        }

    override suspend fun getStreamUrl(
        itemId: UUID,
        mediaSourceId: String,
        audioStreamIndex: Int?,
    ): String =
        withContext(Dispatchers.IO) {
            try {
                jellyfinApi.videosApi.getVideoStreamUrl(
                    itemId,
                    static = true,
                    mediaSourceId = mediaSourceId,
                    audioStreamIndex = audioStreamIndex,
                )
            } catch (e: Exception) {
                Timber.e(e)
                ""
            }
        }

    override suspend fun getAudioStreamUrl(
        itemId: UUID,
        mediaSourceId: String?,
    ): String =
        withContext(Dispatchers.IO) {
            try {
                jellyfinApi.audioApi.getAudioStreamUrl(
                    itemId,
                    static = true,
                    mediaSourceId = mediaSourceId,
                )
            } catch (e: Exception) {
                Timber.e(e)
                ""
            }
        }

    override suspend fun getAudioTranscodeStreamUrl(
        itemId: UUID,
        mediaSourceId: String,
        allowedAudioCodecs: List<String>,
        startPositionMs: Long,
        audioStreamIndex: Int?,
    ): String =
        withContext(Dispatchers.IO) {
            try {
                val codecs = allowedAudioCodecs
                    .ifEmpty { listOf("aac") }
                    .joinToString(",")
                // Effectively-unbounded bitrate: the *only* reason Jellyfin should transcode
                // here is that the source audio codec isn't in [codecs] — never a stale
                // bandwidth cap. That cap is exactly what silently degraded audio before
                // (TranscodeReasons=ContainerBitrateExceedsLimit); the user wants best audio.
                val bitrate = 1_000_000_000
                val playbackInfo = jellyfinApi.mediaInfoApi
                    .getPostedPlaybackInfo(
                        itemId,
                        PlaybackInfoDto(
                            userId = jellyfinApi.userId!!,
                            mediaSourceId = mediaSourceId,
                            startTimeTicks = startPositionMs
                                .takeIf { it > 0L }
                                ?.let { it * 10_000L },
                            audioStreamIndex = audioStreamIndex,
                            maxStreamingBitrate = bitrate,
                            // Force the transcode explicitly instead of relying on the device
                            // profile to reject direct-play: a file with a *secondary*
                            // direct-playable audio track (common — e.g. a TrueHD-Atmos main
                            // track plus an AC-3 compatibility track) makes Jellyfin direct-play
                            // the whole container, and the receiver (video disabled) then lands
                            // on the undecodable primary track ⇒ `groups=0` silence. Disabling
                            // direct play / direct stream and forcing a transcode of the
                            // *primary* audio guarantees an HLS stream whose audio the chain
                            // can render. Video is stream-copied (h264/hevc remux into TS) so
                            // only the audio is re-encoded — fast, and never bandwidth-capped.
                            enableDirectPlay = false,
                            enableDirectStream = false,
                            enableTranscoding = true,
                            allowVideoStreamCopy = true,
                            allowAudioStreamCopy = false,
                            deviceProfile =
                                DeviceProfile(
                                    name = "SplitAv audio-compatible",
                                    maxStaticBitrate = bitrate,
                                    maxStreamingBitrate = bitrate,
                                    codecProfiles = emptyList(),
                                    containerProfiles = emptyList(),
                                    // Video direct-plays (copied) for any container; only the
                                    // *audio* direct-play set is constrained. A source whose
                                    // audio codec is outside [codecs] therefore can't be fully
                                    // direct-played → Jellyfin returns an HLS transcodingUrl
                                    // with audio re-encoded to [codecs] and video copied.
                                    directPlayProfiles =
                                        listOf(
                                            DirectPlayProfile(
                                                container = "",
                                                audioCodec = codecs,
                                                type = DlnaProfileType.VIDEO,
                                            ),
                                        ),
                                    transcodingProfiles =
                                        listOf(
                                            TranscodingProfile(
                                                container = "ts",
                                                type = DlnaProfileType.VIDEO,
                                                videoCodec = "h264,hevc",
                                                audioCodec = codecs,
                                                protocol = MediaStreamProtocol.HLS,
                                                estimateContentLength = false,
                                                enableMpegtsM2TsMode = false,
                                                transcodeSeekInfo = TranscodeSeekInfo.AUTO,
                                                copyTimestamps = false,
                                                context = EncodingContext.STREAMING,
                                                enableSubtitlesInManifest = false,
                                                maxAudioChannels = null,
                                                minSegments = 0,
                                                segmentLength = 0,
                                                breakOnNonKeyFrames = false,
                                                conditions = emptyList(),
                                                enableAudioVbrEncoding = true,
                                            ),
                                        ),
                                    subtitleProfiles = emptyList(),
                                ),
                        ),
                    )
                    .content
                val src = playbackInfo.mediaSources
                    .firstOrNull { it.id == mediaSourceId }
                    ?: playbackInfo.mediaSources.firstOrNull()
                val mapped = src?.toSpatialFinSource(
                    this@JellyfinRepositoryImpl,
                    itemId,
                    includePath = true,
                )
                // transcodingUrl is the HLS .m3u8 when Jellyfin chose to transcode; path is
                // the populated stream URL otherwise (source audio already compatible).
                (mapped?.transcodingUrl?.takeIf { it.isNotBlank() }
                    ?: mapped?.path.orEmpty())
            } catch (e: Exception) {
                Timber.e(e, "getAudioTranscodeStreamUrl failed item=%s", itemId)
                ""
            }
        }

    override fun getUniversalAudioStreamUrl(
        itemId: UUID,
        mediaSourceId: String?,
        startPositionTicks: Long,
    ): String {
        return jellyfinApi.universalAudioApi.getUniversalAudioStreamUrl(
            itemId = itemId,
            container = UNIVERSAL_AUDIO_DIRECT_PLAY_CONTAINERS,
            mediaSourceId = mediaSourceId,
            deviceId = jellyfinApi.jellyfin.deviceInfo?.id,
            userId = jellyfinApi.userId!!,
            audioCodec = "aac",
            maxAudioChannels = null,
            transcodingAudioChannels = null,
            maxStreamingBitrate = 320_000,
            audioBitRate = 320_000,
            startTimeTicks = startPositionTicks.takeIf { it > 0L },
            transcodingContainer = "aac",
            transcodingProtocol = MediaStreamProtocol.HTTP,
            maxAudioSampleRate = null,
            maxAudioBitDepth = null,
            enableRemoteMedia = true,
            enableAudioVbrEncoding = true,
            breakOnNonKeyFrames = false,
            enableRedirection = true,
        )
    }

    override suspend fun getMediaAttachment(
        itemId: UUID,
        mediaSourceId: String,
        attachmentIndex: Int,
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                VideoAttachmentsApi(jellyfinApi.api)
                    .getAttachment(itemId, mediaSourceId, attachmentIndex)
                    .content
            }.onFailure {
                Timber.w(
                    it,
                    "subtitle: failed to fetch attachment itemId=%s mediaSourceId=%s index=%d",
                    itemId,
                    mediaSourceId,
                    attachmentIndex,
                )
            }.getOrNull()
        }

    override suspend fun getSegments(itemId: UUID): List<SpatialFinSegment> =
        withContext(Dispatchers.IO) {
            val databaseSegments = database.getSegments(itemId).map { it.toSpatialFinSegment() }

            if (databaseSegments.isNotEmpty()) {
                return@withContext databaseSegments
            }

            try {
                val apiSegments =
                    jellyfinApi.mediaSegmentsApi.getItemSegments(itemId).content.items.map {
                        it.toSpatialFinSegment()
                    }

                apiSegments.forEach { database.insertSegment(it.toSpatialFinSegmentsDto(itemId)) }

                return@withContext apiSegments
            } catch (e: Exception) {
                Timber.e(e)
                return@withContext emptyList()
            }
        }

    override suspend fun getTrickplayData(itemId: UUID, width: Int, index: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                try {
                    val sources = File(context.filesDir, "trickplay/$itemId").listFiles()
                    if (sources != null) {
                        return@withContext File(sources.first(), index.toString()).readBytes()
                    }
                } catch (_: Exception) {}

                return@withContext jellyfinApi.trickplayApi
                    .getTrickplayTileImage(itemId, width, index)
                    .content
            } catch (_: Exception) {
                return@withContext null
            }
        }

    override suspend fun getSyncPlayGroups(): List<SyncPlayGroup> =
        withContext(Dispatchers.IO) {
            jellyfinApi.syncPlayApi.syncPlayGetGroups().content.map(GroupInfoDto::toSyncPlayGroup)
        }

    override suspend fun createSyncPlayGroup(name: String): SyncPlayGroup =
        withContext(Dispatchers.IO) {
            jellyfinApi.syncPlayApi.syncPlayCreateGroup(NewGroupRequestDto(name)).content.toSyncPlayGroup()
        }

    override suspend fun joinSyncPlayGroup(groupId: UUID) {
        withContext(Dispatchers.IO) {
            jellyfinApi.syncPlayApi.syncPlayJoinGroup(JoinGroupRequestDto(groupId))
        }
    }

    override suspend fun leaveSyncPlayGroup() {
        withContext(Dispatchers.IO) {
            jellyfinApi.syncPlayApi.syncPlayLeaveGroup()
        }
    }

    override suspend fun setSyncPlayQueue(
        itemIds: List<UUID>,
        playingItemIndex: Int,
        startPositionTicks: Long,
    ) {
        withContext(Dispatchers.IO) {
            jellyfinApi.syncPlayApi.syncPlaySetNewQueue(
                PlayRequestDto(
                    playingQueue = itemIds,
                    playingItemPosition = playingItemIndex,
                    startPositionTicks = startPositionTicks,
                )
            )
        }
    }

    override suspend fun pauseSyncPlay() {
        withContext(Dispatchers.IO) {
            jellyfinApi.syncPlayApi.syncPlayPause()
        }
    }

    override suspend fun unpauseSyncPlay() {
        withContext(Dispatchers.IO) {
            jellyfinApi.syncPlayApi.syncPlayUnpause()
        }
    }

    override suspend fun seekSyncPlay(positionTicks: Long) {
        withContext(Dispatchers.IO) {
            jellyfinApi.syncPlayApi.syncPlaySeek(SeekRequestDto(positionTicks))
        }
    }

    override suspend fun stopSyncPlay() {
        withContext(Dispatchers.IO) {
            jellyfinApi.syncPlayApi.syncPlayStop()
        }
    }

    override suspend fun nextSyncPlayItem(playlistItemId: UUID) {
        withContext(Dispatchers.IO) {
            jellyfinApi.syncPlayApi.syncPlayNextItem(NextItemRequestDto(playlistItemId))
        }
    }

    override suspend fun previousSyncPlayItem(playlistItemId: UUID) {
        withContext(Dispatchers.IO) {
            jellyfinApi.syncPlayApi.syncPlayPreviousItem(PreviousItemRequestDto(playlistItemId))
        }
    }

    override fun observePlayStateMessages(): Flow<PlaystateMessage> =
        jellyfinApi.api.webSocket.subscribePlayStateCommands(
            setOf(
                PlaystateCommand.PAUSE,
                PlaystateCommand.UNPAUSE,
                PlaystateCommand.PLAY_PAUSE,
                PlaystateCommand.SEEK,
                PlaystateCommand.STOP,
            )
        )

    override fun observeSyncPlayCommandMessages(): Flow<SyncPlayCommandMessage> =
        jellyfinApi.api.webSocket.subscribeSyncPlayCommands(
            setOf(
                SendCommandType.PAUSE,
                SendCommandType.UNPAUSE,
                SendCommandType.SEEK,
                SendCommandType.STOP,
            )
        )

    override fun observeSyncPlayGroupUpdates(): Flow<SyncPlayGroupUpdateMessage> =
        jellyfinApi.api.webSocket.subscribe(SyncPlayGroupUpdateMessage::class)

    override fun observeGeneralCommandMessages(): Flow<GeneralCommandMessage> =
        jellyfinApi.api.webSocket.subscribeGeneralCommands(supportedGeneralCommands().toSet())

    override fun observeSessions(): Flow<List<org.jellyfin.sdk.model.api.SessionInfoDto>> =
        jellyfinApi.api.webSocket.subscribe(org.jellyfin.sdk.model.api.SessionsMessage::class).mapNotNull { it.data?.toList() }

    override suspend fun getSessions(): List<org.jellyfin.sdk.model.api.SessionInfoDto> =
        withContext(Dispatchers.IO) {
            jellyfinApi.sessionApi.getSessions().content.toList()
        }

    override suspend fun sendPlaystateCommand(sessionId: String, command: PlaystateCommand, seekPositionTicks: Long?) {
        withContext(Dispatchers.IO) {
            jellyfinApi.sessionApi.sendPlaystateCommand(
                sessionId = sessionId,
                command = command,
                seekPositionTicks = seekPositionTicks
            )
        }
    }

    override suspend fun sendGeneralCommand(sessionId: String, command: GeneralCommandType, arguments: Map<String, String>?) {
        withContext(Dispatchers.IO) {
            if (arguments.isNullOrEmpty()) {
                jellyfinApi.sessionApi.sendGeneralCommand(
                    sessionId = sessionId,
                    command = command,
                )
            } else {
                jellyfinApi.sessionApi.sendFullGeneralCommand(
                    sessionId = sessionId,
                    data =
                        GeneralCommand(
                            name = command,
                            controllingUserId = jellyfinApi.userId!!,
                            arguments = arguments,
                        ),
                )
            }
        }
    }

    override fun observeRealtimeEvents(): Flow<JellyfinRealtimeEvent> =
        merge(
            jellyfinApi.api.webSocket.subscribe(UserDataChangedMessage::class).mapNotNull { message ->
                val changeInfo = message.data ?: return@mapNotNull null
                persistRemoteUserDataChange(changeInfo)
                JellyfinRealtimeEvent.UserDataChanged(
                    userId = changeInfo.userId,
                    itemIds = changeInfo.userDataList.map { it.itemId }.toSet(),
                )
            },
            jellyfinApi.api.webSocket.subscribe(LibraryChangedMessage::class).mapNotNull { message ->
                val updateInfo = message.data ?: return@mapNotNull null
                if (updateInfo.isEmpty) {
                    null
                } else {
                    JellyfinRealtimeEvent.LibraryChanged(
                        addedItemIds = parseRealtimeItemIds(updateInfo.itemsAdded),
                        updatedItemIds = parseRealtimeItemIds(updateInfo.itemsUpdated),
                        removedItemIds = parseRealtimeItemIds(updateInfo.itemsRemoved),
                    )
                }
            },
        )

    override fun observeSocketState(): Flow<SocketApiState> = jellyfinApi.api.webSocket.state

    override suspend fun postCapabilities() {
        Timber.d("Sending capabilities")
        withContext(Dispatchers.IO) {
            jellyfinApi.sessionApi.postCapabilities(
                playableMediaTypes = listOf(MediaType.VIDEO, MediaType.AUDIO),
                supportedCommands = supportedGeneralCommands(),
                supportsMediaControl = true,
            )
        }
    }

    override suspend fun postPlaybackStart(itemId: UUID) {
        Timber.d("Sending start $itemId")
        withContext(Dispatchers.IO) {
            jellyfinApi.playStateApi.reportPlaybackStart(
                PlaybackStartInfo(
                    itemId = itemId,
                    canSeek = true,
                    isPaused = false,
                    isMuted = false,
                    playMethod = PlayMethod.DIRECT_PLAY,
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                )
            )
        }
    }

    override suspend fun postPlaybackStop(
        itemId: UUID,
        positionTicks: Long,
        playedPercentage: Int,
        markedPlayed: Boolean,
    ) {
        Timber.d("Sending stop $itemId")
        withContext(Dispatchers.IO) {
            when {
                markedPlayed -> {
                    // Played status already committed (e.g. outro reached) — don't downgrade.
                    database.setPlaybackPositionTicks(itemId, jellyfinApi.userId!!, 0)
                    database.setPlayed(jellyfinApi.userId!!, itemId, true)
                }
                playedPercentage < 10 -> {
                    database.setPlaybackPositionTicks(itemId, jellyfinApi.userId!!, 0)
                    database.setPlayed(jellyfinApi.userId!!, itemId, false)
                }
                playedPercentage > 90 -> {
                    database.setPlaybackPositionTicks(itemId, jellyfinApi.userId!!, 0)
                    database.setPlayed(jellyfinApi.userId!!, itemId, true)
                }
                else -> {
                    database.setPlaybackPositionTicks(itemId, jellyfinApi.userId!!, positionTicks)
                    database.setPlayed(jellyfinApi.userId!!, itemId, false)
                }
            }
            try {
                jellyfinApi.playStateApi.reportPlaybackStopped(
                    PlaybackStopInfo(itemId = itemId, positionTicks = positionTicks, failed = false)
                )
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    override suspend fun postPlaybackProgress(
        itemId: UUID,
        positionTicks: Long,
        isPaused: Boolean,
    ) {
        Timber.d("Posting progress of $itemId, position: $positionTicks")
        withContext(Dispatchers.IO) {
            database.setPlaybackPositionTicks(itemId, jellyfinApi.userId!!, positionTicks)
            try {
                jellyfinApi.playStateApi.reportPlaybackProgress(
                    PlaybackProgressInfo(
                        itemId = itemId,
                        canSeek = true,
                        isPaused = isPaused,
                        isMuted = false,
                        playMethod = PlayMethod.DIRECT_PLAY,
                        repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT,
                        positionTicks = positionTicks,
                    )
                )
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    override suspend fun markAsFavorite(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setFavorite(jellyfinApi.userId!!, itemId, true)
            try {
                jellyfinApi.userLibraryApi.markFavoriteItem(itemId)
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    override suspend fun unmarkAsFavorite(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setFavorite(jellyfinApi.userId!!, itemId, false)
            try {
                jellyfinApi.userLibraryApi.unmarkFavoriteItem(itemId)
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    override suspend fun markAsPlayed(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setPlayed(jellyfinApi.userId!!, itemId, true)
            try {
                jellyfinApi.playStateApi.markPlayedItem(itemId)
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    override suspend fun markAsUnplayed(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setPlayed(jellyfinApi.userId!!, itemId, false)
            try {
                jellyfinApi.playStateApi.markUnplayedItem(itemId)
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    override suspend fun refreshItemMetadata(itemId: UUID) {
        withContext(Dispatchers.IO) {
            runCatching {
                jellyfinApi.itemRefreshApi.refreshItem(
                    itemId = itemId,
                    metadataRefreshMode = org.jellyfin.sdk.model.api.MetadataRefreshMode.FULL_REFRESH,
                    imageRefreshMode = org.jellyfin.sdk.model.api.MetadataRefreshMode.FULL_REFRESH,
                    replaceAllMetadata = false,
                    replaceAllImages = false,
                )
            }
        }
    }

    override suspend fun getItemProviderIds(itemId: UUID): Map<String, String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dto = jellyfinApi.userLibraryApi.getItem(itemId, jellyfinApi.userId!!).content
                // Jellyfin SDK exposes providerIds as Map<String, String?>? — filter
                // out nulls and blanks so callers never see an entry they can't
                // display. Keys stay in Jellyfin's canonical casing ("Imdb", "Tmdb")
                // so writes via setItemProviderId round-trip cleanly.
                dto.providerIds.orEmpty().mapNotNull { (key, value) ->
                    val v = value?.trim().orEmpty()
                    if (v.isBlank()) null else key to v
                }.toMap()
            }.getOrElse {
                Timber.w(it, "Failed to read providerIds for %s", itemId)
                emptyMap()
            }
        }

    override suspend fun setItemProviderId(itemId: UUID, providerKey: String, value: String?): Boolean =
        withContext(Dispatchers.IO) {
            // Jellyfin's POST /Items/{id} (ItemUpdateApi.updateItem) returns HTTP
            // 400 when we round-trip a full BaseItemDto — the SDK payload
            // includes read-only fields (Id, ServerId, media source IDs, etc.)
            // that the server rejects. The web UI's "Identify" button doesn't
            // use that endpoint at all; it uses ItemLookupApi, which is what
            // we call here.
            //
            // Flow (mirrors the web UI's Identify dialog):
            //   1. getSeriesRemoteSearchResults / getMovieRemoteSearchResults
            //      with the new IMDb ID — the server queries its metadata
            //      providers and returns hydrated RemoteSearchResults.
            //   2. applySearchCriteria with the first hit — the server writes
            //      the providerIds back, replaces metadata + images, and
            //      triggers the refresh itself (no separate refreshItem call
            //      needed).
            //
            // Episodes aren't supported by the Identify flow — the web UI
            // disables that button on episodes for the same reason. Callers
            // should edit the parent series instead.
            runCatching {
                val item = jellyfinApi.userLibraryApi
                    .getItem(itemId, jellyfinApi.userId!!)
                    .content
                val providerIds = mapOf(providerKey to value.orEmpty())
                Timber.i(
                    "setItemProviderId: item=%s kind=%s key=%s newValue=%s",
                    itemId, item.type, providerKey, value,
                )
                val results = when (item.type) {
                    BaseItemKind.SERIES -> jellyfinApi.itemLookupApi
                        .getSeriesRemoteSearchResults(
                            org.jellyfin.sdk.model.api.SeriesInfoRemoteSearchQuery(
                                searchInfo = org.jellyfin.sdk.model.api.SeriesInfo(
                                    name = item.name,
                                    year = item.productionYear,
                                    providerIds = providerIds,
                                    isAutomated = false,
                                ),
                                itemId = itemId,
                                includeDisabledProviders = false,
                            ),
                        )
                        .content
                    BaseItemKind.MOVIE -> jellyfinApi.itemLookupApi
                        .getMovieRemoteSearchResults(
                            org.jellyfin.sdk.model.api.MovieInfoRemoteSearchQuery(
                                searchInfo = org.jellyfin.sdk.model.api.MovieInfo(
                                    name = item.name,
                                    year = item.productionYear,
                                    providerIds = providerIds,
                                    isAutomated = false,
                                ),
                                itemId = itemId,
                                includeDisabledProviders = false,
                            ),
                        )
                        .content
                    else -> {
                        Timber.w("setItemProviderId: unsupported kind=%s for item=%s", item.type, itemId)
                        return@runCatching false
                    }
                }
                val match = results.firstOrNull { !it.providerIds.isNullOrEmpty() }
                    ?: results.firstOrNull()
                if (match == null) {
                    Timber.w("setItemProviderId: remote search returned no hits for %s", itemId)
                    return@runCatching false
                }
                Timber.i(
                    "setItemProviderId: applying remote result name=%s providerIds=%s",
                    match.name, match.providerIds,
                )
                jellyfinApi.itemLookupApi.applySearchCriteria(
                    itemId = itemId,
                    replaceAllImages = true,
                    data = match,
                )
                Timber.i("setItemProviderId: applySearchCriteria accepted for %s", itemId)
                true
            }.getOrElse {
                Timber.w(it, "setItemProviderId failed for %s (key=%s)", itemId, providerKey)
                false
            }
        }

    override suspend fun deleteItem(itemId: UUID): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { jellyfinApi.libraryApi.deleteItem(itemId) }.isSuccess
        }

    override fun getBaseUrl() = jellyfinApi.api.baseUrl.orEmpty()

    override fun getAccessToken(): String? = jellyfinApi.api.accessToken

    override fun getDeviceId(): String? = jellyfinApi.jellyfin.deviceInfo?.id

    override suspend fun updateDeviceName(name: String) {
        withContext(Dispatchers.IO) {
            jellyfinApi.jellyfin.deviceInfo?.id?.let { id ->
                jellyfinApi.devicesApi.updateDeviceOptions(
                    id,
                    DeviceOptionsDto(0, customName = name),
                )
            }
        }
    }

    override suspend fun getUserConfiguration(): UserConfiguration =
        withContext(Dispatchers.IO) { jellyfinApi.userApi.getCurrentUser().content.configuration!! }

    override suspend fun getDownloads(): List<SpatialFinItem> =
        withContext(Dispatchers.IO) {
            downloadStorageManager.reconcileCurrentServerDownloads(
                appPreferences.getValue(appPreferences.currentServer),
                jellyfinApi.userId,
            )
            val items = mutableListOf<SpatialFinItem>()
            items.addAll(
                database
                    .getMoviesByServerId(appPreferences.getValue(appPreferences.currentServer)!!)
                    .map { it.toSpatialFinMovie(database, jellyfinApi.userId!!) }
            )
            items.addAll(
                database
                    .getShowsByServerId(appPreferences.getValue(appPreferences.currentServer)!!)
                    .map { it.toSpatialFinShow(database, jellyfinApi.userId!!) }
            )
            items
        }

    override fun getUserId(): UUID {
        return jellyfinApi.userId!!
    }

    override suspend fun searchRemoteSubtitles(
        itemId: UUID,
        language: String
    ): List<org.jellyfin.sdk.model.api.RemoteSubtitleInfo> =
        withContext(Dispatchers.IO) {
            jellyfinApi.subtitleApi.searchRemoteSubtitles(
                itemId = itemId,
                language = language,
            ).content.toList()
        }

    override suspend fun downloadRemoteSubtitles(itemId: UUID, subtitleId: String) {
        withContext(Dispatchers.IO) {
            jellyfinApi.subtitleApi.downloadRemoteSubtitles(
                itemId = itemId,
                subtitleId = subtitleId,
            )
        }
    }

    private fun supportedGeneralCommands(): List<GeneralCommandType> =
        listOf(
            GeneralCommandType.VOLUME_UP,
            GeneralCommandType.VOLUME_DOWN,
            GeneralCommandType.TOGGLE_MUTE,
            GeneralCommandType.SET_AUDIO_STREAM_INDEX,
            GeneralCommandType.SET_SUBTITLE_STREAM_INDEX,
            GeneralCommandType.MUTE,
            GeneralCommandType.UNMUTE,
            GeneralCommandType.SET_VOLUME,
            GeneralCommandType.DISPLAY_MESSAGE,
            GeneralCommandType.PLAY,
            GeneralCommandType.PLAY_STATE,
            GeneralCommandType.PLAY_NEXT,
            GeneralCommandType.PLAY_MEDIA_SOURCE,
        )

    private suspend fun persistRemoteUserDataChange(changeInfo: UserDataChangeInfo) {
        if (changeInfo.userId != jellyfinApi.userId) return

        withContext(Dispatchers.IO) {
            changeInfo.userDataList.forEach { userData ->
                val existing = database.getUserDataOrCreateNew(userData.itemId, changeInfo.userId)
                database.insertUserData(
                    existing.copy(
                        played = userData.played,
                        favorite = userData.isFavorite,
                        playbackPositionTicks = userData.playbackPositionTicks,
                        toBeSynced = false,
                    )
                )
            }
        }
    }

    private fun parseRealtimeItemIds(ids: List<String>): Set<UUID> =
        ids.mapNotNull { id ->
            // Jellyfin's websocket emits item ids in the 32-char dashless hex
            // form ("fbae46090958aa929b8afa8ab0b68901") rather than canonical
            // 8-4-4-4-12. Insert dashes so UUID.fromString accepts it, and
            // fall back to the straight parse for any server that emits the
            // canonical form.
            runCatching {
                if (DASHLESS_UUID.matches(id)) {
                    UUID.fromString(
                        "${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-" +
                            "${id.substring(16, 20)}-${id.substring(20, 32)}",
                    )
                } else {
                    UUID.fromString(id)
                }
            }
                .onFailure { Timber.w(it, "Ignoring non-UUID realtime item id %s", id) }
                .getOrNull()
        }.toSet()

    companion object {
        private val DASHLESS_UUID = Regex("^[0-9a-fA-F]{32}$")
        /**
         * Fields every browse/search query needs.
         *
         * `PARENT_ID` is **not** returned by default (verified against Jellyfin
         * 10.11.11) — without it `SpatialFinPhoto.parentId` is always null and the
         * photo viewer can only ever show the single tapped image instead of paging
         * through its folder. `WIDTH`/`HEIGHT` are likewise field-gated. All three are
         * a handful of bytes per item.
         */
        internal val BROWSE_ITEM_FIELDS =
            listOf(
                ItemFields.CHILD_COUNT,
                ItemFields.RECURSIVE_ITEM_COUNT,
                ItemFields.PARENT_ID,
                ItemFields.WIDTH,
                ItemFields.HEIGHT,
            )
        private val AUDIO_ITEM_FIELDS =
            listOf(
                ItemFields.MEDIA_SOURCES,
                ItemFields.MEDIA_STREAMS,
                ItemFields.CHAPTERS,
                ItemFields.OVERVIEW,
                ItemFields.GENRES,
                ItemFields.PARENT_ID,
                ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
                ItemFields.CAN_DOWNLOAD,
            )
        private val UNIVERSAL_AUDIO_DIRECT_PLAY_CONTAINERS =
            listOf("mp3", "aac", "flac", "wav", "ogg", "m4a", "m4b")
    }
}
