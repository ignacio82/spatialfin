package dev.jdtech.jellyfin.repository

import android.content.Context
import androidx.paging.PagingData
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.downloads.DownloadStorageManager
import dev.jdtech.jellyfin.models.SpatialFinCollection
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinPerson
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.SpatialFinSegment
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.SyncPlayGroup
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.models.toSpatialFinEpisode
import dev.jdtech.jellyfin.models.toSpatialFinMovie
import dev.jdtech.jellyfin.models.toSpatialFinSeason
import dev.jdtech.jellyfin.models.toSpatialFinSegment
import dev.jdtech.jellyfin.models.toSpatialFinShow
import dev.jdtech.jellyfin.models.toSpatialFinSource
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.io.File
import java.util.UUID
import dev.jdtech.jellyfin.models.SpatialFinImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.sockets.SocketApiState
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.GeneralCommandMessage
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.PlaystateMessage
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.jellyfin.sdk.model.api.SyncPlayCommandMessage
import org.jellyfin.sdk.model.api.SyncPlayGroupUpdateMessage
import org.jellyfin.sdk.model.api.UserConfiguration

class JellyfinRepositoryOfflineImpl(
    private val context: Context,
    private val jellyfinApi: JellyfinApi,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
    private val downloadStorageManager: DownloadStorageManager,
) : JellyfinRepository {

    override suspend fun getPublicSystemInfo(): PublicSystemInfo {
        throw Exception("System info not available in offline mode")
    }

    override suspend fun getUserViews(): List<BaseItemDto> {
        return emptyList()
    }

    override suspend fun getMovie(itemId: UUID): SpatialFinMovie =
        withContext(Dispatchers.IO) {
            downloadStorageManager.reconcileItem(itemId, jellyfinApi.userId)
            database.getMovie(itemId).toSpatialFinMovie(database, jellyfinApi.userId!!)
        }

    override suspend fun getShow(itemId: UUID): SpatialFinShow =
        withContext(Dispatchers.IO) {
            database.getShow(itemId).toSpatialFinShow(database, jellyfinApi.userId!!)
        }

    override suspend fun getSeason(itemId: UUID): SpatialFinSeason =
        withContext(Dispatchers.IO) {
            database.getSeason(itemId).toSpatialFinSeason(database, jellyfinApi.userId!!)
        }

    override suspend fun getEpisode(itemId: UUID): SpatialFinEpisode =
        withContext(Dispatchers.IO) {
            downloadStorageManager.reconcileItem(itemId, jellyfinApi.userId)
            database.getEpisode(itemId).toSpatialFinEpisode(database, jellyfinApi.userId!!)
        }

    override suspend fun getLibraries(): List<SpatialFinCollection> {
        return emptyList()
    }

    override suspend fun getItem(itemId: UUID): SpatialFinItem? {
        return null
    }

    override suspend fun getItems(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
        startIndex: Int?,
        limit: Int?,
    ): List<SpatialFinItem> {
        return emptyList()
    }

    override suspend fun getItemsPaging(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
    ): Flow<PagingData<SpatialFinItem>> = withContext(Dispatchers.IO) {
        // Collection structure (libraries / parent folders) isn't cached locally,
        // so `parentId` is ignored. We surface every downloaded item the user
        // could conceivably play, filtered by `includeTypes` so callers asking
        // for only movies don't get episodes back.
        val serverId = appPreferences.getValue(appPreferences.currentServer)
            ?: return@withContext flowOf(PagingData.empty())
        val userId = jellyfinApi.userId ?: return@withContext flowOf(PagingData.empty())
        val wantsAll = includeTypes.isNullOrEmpty()
        val items = buildList<SpatialFinItem> {
            if (wantsAll || includeTypes!!.contains(BaseItemKind.MOVIE)) {
                addAll(
                    database.getMoviesByServerId(serverId)
                        .map { it.toSpatialFinMovie(database, userId) }
                        .filter { it.isDownloaded() }
                )
            }
            if (wantsAll || includeTypes!!.contains(BaseItemKind.SERIES)) {
                addAll(
                    database.getShowsByServerId(serverId)
                        .map { it.toSpatialFinShow(database, userId) }
                )
            }
            if (wantsAll || includeTypes!!.contains(BaseItemKind.EPISODE)) {
                addAll(
                    database.getEpisodesByServerId(serverId)
                        .map { it.toSpatialFinEpisode(database, userId) }
                        .filter { it.isDownloaded() }
                )
            }
        }
        flowOf(PagingData.from(items))
    }

    override suspend fun getPerson(personId: UUID): SpatialFinPerson {
        // The local DB does not store People metadata. Return a placeholder so
        // detail screens can render without crashing; they will simply show no
        // biography or filmography until reconnect.
        return SpatialFinPerson(
            id = personId,
            name = "",
            overview = "",
            images = SpatialFinImages(),
        )
    }

    override suspend fun getPersonItems(
        personIds: List<UUID>,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
    ): List<SpatialFinItem> {
        // Person→items mapping isn't cached locally. Empty result is the
        // honest answer offline.
        return emptyList()
    }

    override suspend fun getFavoriteItems(): List<SpatialFinItem> {
        return withContext(Dispatchers.IO) {
            val serverId = appPreferences.getValue(appPreferences.currentServer) ?: return@withContext emptyList()
            val movies =
                database
                    .getMoviesByServerId(serverId)
                    .map { it.toSpatialFinMovie(database, jellyfinApi.userId!!) }
                    .filter { it.favorite && it.isDownloaded() }
            val shows =
                database
                    .getShowsByServerId(serverId)
                    .map { it.toSpatialFinShow(database, jellyfinApi.userId!!) }
                    .filter { it.favorite }
            val episodes =
                database
                    .getEpisodesByServerId(serverId)
                    .map { it.toSpatialFinEpisode(database, jellyfinApi.userId!!) }
                    .filter { it.favorite && it.isDownloaded() }
            movies + shows + episodes
        }
    }

    override suspend fun getSearchItems(query: String): List<SpatialFinItem> {
        return withContext(Dispatchers.IO) {
            val movies =
                database
                    .searchMovies(appPreferences.getValue(appPreferences.currentServer)!!, query)
                    .map { it.toSpatialFinMovie(database, jellyfinApi.userId!!) }
            val shows =
                database
                    .searchShows(appPreferences.getValue(appPreferences.currentServer)!!, query)
                    .map { it.toSpatialFinShow(database, jellyfinApi.userId!!) }
            val episodes =
                database
                    .searchEpisodes(appPreferences.getValue(appPreferences.currentServer)!!, query)
                    .map { it.toSpatialFinEpisode(database, jellyfinApi.userId!!) }
            movies + shows + episodes
        }
    }

    override suspend fun getSuggestions(): List<SpatialFinItem> {
        return emptyList()
    }

    override suspend fun getResumeItems(): List<SpatialFinItem> {
        return withContext(Dispatchers.IO) {
            val movies =
                database
                    .getMoviesByServerId(appPreferences.getValue(appPreferences.currentServer)!!)
                    .map { it.toSpatialFinMovie(database, jellyfinApi.userId!!) }
                    .filter { it.playbackPositionTicks > 0 && !it.played && it.isDownloaded() }
            val episodes =
                database
                    .getEpisodesByServerId(appPreferences.getValue(appPreferences.currentServer)!!)
                    .map { it.toSpatialFinEpisode(database, jellyfinApi.userId!!) }
                    .filter { it.playbackPositionTicks > 0 && !it.played && it.isDownloaded() }
            movies + episodes
        }
    }

    override suspend fun getLatestMedia(parentId: UUID): List<SpatialFinItem> {
        return emptyList()
    }

    override suspend fun getSeasons(seriesId: UUID, offline: Boolean): List<SpatialFinSeason> =
        withContext(Dispatchers.IO) {
            database.getSeasonsByShowId(seriesId).map {
                it.toSpatialFinSeason(database, jellyfinApi.userId!!)
            }
        }

    override suspend fun getNextUp(seriesId: UUID?): List<SpatialFinEpisode> {
        return withContext(Dispatchers.IO) {
            val result = mutableListOf<SpatialFinEpisode>()
            val shows =
                database
                    .getShowsByServerId(appPreferences.getValue(appPreferences.currentServer)!!)
                    .filter { if (seriesId != null) it.id == seriesId else true }
            for (show in shows) {
                val episodes =
                    database.getEpisodesByShowId(show.id).map {
                        it.toSpatialFinEpisode(database, jellyfinApi.userId!!)
                    }.filter { it.isDownloaded() }
                val indexOfLastPlayed = episodes.indexOfLast { it.played }
                if (indexOfLastPlayed == -1) {
                    episodes.firstOrNull()?.let(result::add)
                } else {
                    episodes.getOrNull(indexOfLastPlayed + 1)?.let { result.add(it) }
                }
            }
            result.filter { it.playbackPositionTicks == 0L }
        }
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
            val items =
                database.getEpisodesBySeasonId(seasonId).map {
                    it.toSpatialFinEpisode(database, jellyfinApi.userId!!)
                }
            if (startItemId != null) return@withContext items.dropWhile { it.id != startItemId }
            items
        }

    override suspend fun getMediaSources(
        itemId: UUID,
        includePath: Boolean,
        maxBitrate: Long?
    ): List<SpatialFinSource> =
        withContext(Dispatchers.IO) {
            downloadStorageManager.reconcileItem(itemId, jellyfinApi.userId)
            database.getSources(itemId).map { it.toSpatialFinSource(database) }
        }

    override suspend fun getStreamUrl(itemId: UUID, mediaSourceId: String): String {
        // Downloaded items play from local file URIs through DownloadStorageManager,
        // so this should never be reached offline. If it is, the caller is trying
        // to stream from a server we can't reach — fail loudly instead of returning
        // a misleading URL.
        error("getStreamUrl is not available in offline mode (item=$itemId)")
    }

    override suspend fun getMediaAttachment(
        itemId: UUID,
        mediaSourceId: String,
        attachmentIndex: Int,
    ): ByteArray? = null

    override suspend fun getSegments(itemId: UUID): List<SpatialFinSegment> =
        withContext(Dispatchers.IO) { database.getSegments(itemId).map { it.toSpatialFinSegment() } }

    override suspend fun getTrickplayData(itemId: UUID, width: Int, index: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val sources =
                    File(context.filesDir, "trickplay/$itemId").listFiles()
                        ?: return@withContext null
                File(sources.first(), index.toString()).readBytes()
            } catch (_: Exception) {
                null
            }
        }

    override suspend fun getSyncPlayGroups(): List<SyncPlayGroup> = emptyList()

    override suspend fun createSyncPlayGroup(name: String): SyncPlayGroup {
        throw UnsupportedOperationException("SyncPlay is not available in offline mode")
    }

    override suspend fun joinSyncPlayGroup(groupId: UUID) {
        throw UnsupportedOperationException("SyncPlay is not available in offline mode")
    }

    override suspend fun leaveSyncPlayGroup() = Unit

    override suspend fun setSyncPlayQueue(
        itemIds: List<UUID>,
        playingItemIndex: Int,
        startPositionTicks: Long,
    ) {
        throw UnsupportedOperationException("SyncPlay is not available in offline mode")
    }

    override suspend fun pauseSyncPlay() = Unit

    override suspend fun unpauseSyncPlay() = Unit

    override suspend fun seekSyncPlay(positionTicks: Long) = Unit

    override suspend fun stopSyncPlay() = Unit

    override suspend fun nextSyncPlayItem(playlistItemId: UUID) = Unit

    override suspend fun previousSyncPlayItem(playlistItemId: UUID) = Unit

    override fun observePlayStateMessages(): Flow<PlaystateMessage> = emptyFlow()

    override fun observeSyncPlayCommandMessages(): Flow<SyncPlayCommandMessage> = emptyFlow()

    override fun observeSyncPlayGroupUpdates(): Flow<SyncPlayGroupUpdateMessage> = emptyFlow()

    override fun observeGeneralCommandMessages(): Flow<GeneralCommandMessage> = emptyFlow()

    override fun observeRealtimeEvents(): Flow<JellyfinRealtimeEvent> = emptyFlow()

    override fun observeSocketState(): Flow<SocketApiState> = emptyFlow()

    override suspend fun postCapabilities() {}

    override suspend fun postPlaybackStart(itemId: UUID) {}

    override suspend fun postPlaybackStop(
        itemId: UUID,
        positionTicks: Long,
        playedPercentage: Int,
    ) {
        withContext(Dispatchers.IO) {
            when {
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
            database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
        }
    }

    override suspend fun postPlaybackProgress(
        itemId: UUID,
        positionTicks: Long,
        isPaused: Boolean,
    ) {
        withContext(Dispatchers.IO) {
            database.setPlaybackPositionTicks(itemId, jellyfinApi.userId!!, positionTicks)
            database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
        }
    }

    override suspend fun markAsFavorite(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setFavorite(jellyfinApi.userId!!, itemId, true)
            database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
        }
    }

    override suspend fun unmarkAsFavorite(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setFavorite(jellyfinApi.userId!!, itemId, false)
            database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
        }
    }

    override suspend fun markAsPlayed(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setPlayed(jellyfinApi.userId!!, itemId, true)
            database.setPlaybackPositionTicks(itemId, jellyfinApi.userId!!, 0)
            database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
        }
    }

    override suspend fun markAsUnplayed(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setPlayed(jellyfinApi.userId!!, itemId, false)
            database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
        }
    }

    override fun getBaseUrl(): String {
        return ""
    }

    override suspend fun updateDeviceName(name: String) = Unit

    override suspend fun getUserConfiguration(): UserConfiguration? {
        return null
    }

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
    ): List<org.jellyfin.sdk.model.api.RemoteSubtitleInfo> {
        return emptyList()
    }

    override suspend fun downloadRemoteSubtitles(itemId: UUID, subtitleId: String) {
        // Offline mode, do nothing
    }

    override fun getAccessToken(): String? = null
}
