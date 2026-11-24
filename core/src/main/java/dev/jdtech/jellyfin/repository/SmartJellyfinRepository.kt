package dev.jdtech.jellyfin.repository

import androidx.paging.PagingData
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.models.SpatialFinAudioBook
import dev.jdtech.jellyfin.models.SpatialFinAudioTrack
import dev.jdtech.jellyfin.models.SpatialFinCollection
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinLyrics
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinMusicAlbum
import dev.jdtech.jellyfin.models.SpatialFinMusicArtist
import dev.jdtech.jellyfin.models.SpatialFinPerson
import dev.jdtech.jellyfin.models.SpatialFinPlaylist
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.SpatialFinSegment
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.SyncPlayGroup
import dev.jdtech.jellyfin.offline.ServerConnectionMonitor
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionMode
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
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

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SmartJellyfinRepository
@Inject
constructor(
    private val onlineRepository: JellyfinRepositoryImpl,
    private val offlineRepository: JellyfinRepositoryOfflineImpl,
    private val connectionMonitor: ServerConnectionMonitor,
    private val jellyfinApi: JellyfinApi,
    private val sessionActivationCoordinator: SessionActivationCoordinator,
) : JellyfinRepository {

    private data class RealtimeKey(
        val serverId: String?,
        val serverAddress: String?,
        val userId: UUID?,
        val connectionEpoch: Long,
    )

    private fun shouldUseOffline(): Boolean =
        connectionMonitor.shouldUseOfflineRepository() ||
            jellyfinApi.userId == null ||
            jellyfinApi.api.accessToken.isNullOrBlank()

    private fun isEpochMismatchedNoUser(throwable: Throwable, expectedEpoch: Long): Boolean =
        throwable is IllegalStateException &&
            throwable.message == "No active Jellyfin user" &&
            (sessionActivationCoordinator.sessionEpoch.get() != expectedEpoch ||
                sessionActivationCoordinator.isActivating.get())

    private fun realtimeKeyFlow(): Flow<RealtimeKey?> =
        combine(
            connectionMonitor.state,
            sessionActivationCoordinator.sessionState,
        ) { conn, session ->
            val offline =
                conn.effectiveOfflineMode ||
                    session.sessionMode != SessionMode.SERVER_AUTHENTICATED ||
                    session.activeUserId == null ||
                    session.activeServerAddress.isNullOrBlank()
            if (offline) {
                null
            } else {
                RealtimeKey(
                    serverId = session.activeServerId,
                    serverAddress = session.activeServerAddress,
                    userId = session.activeUserId,
                    connectionEpoch = session.connectionEpoch,
                )
            }
        }.distinctUntilChanged()

    private fun <T> observeRealtime(
        online: () -> Flow<T>,
        offline: () -> Flow<T>,
    ): Flow<T> =
        realtimeKeyFlow().flatMapLatest { key ->
            if (key == null) offline() else online()
        }

    override suspend fun getPublicSystemInfo(): PublicSystemInfo =
        runOnlineOnly { onlineRepository.getPublicSystemInfo() }

    override suspend fun getUserViews(): List<BaseItemDto> =
        runWithFallback(
            online = { onlineRepository.getUserViews() },
            offline = { offlineRepository.getUserViews() },
        )

    override suspend fun getEpisode(itemId: UUID): SpatialFinEpisode =
        runWithFallback(
            online = { onlineRepository.getEpisode(itemId) },
            offline = { offlineRepository.getEpisode(itemId) },
        )

    override suspend fun getMovie(itemId: UUID): SpatialFinMovie =
        runWithFallback(
            online = { onlineRepository.getMovie(itemId) },
            offline = { offlineRepository.getMovie(itemId) },
        )

    override suspend fun getShow(itemId: UUID): SpatialFinShow =
        runWithFallback(
            online = { onlineRepository.getShow(itemId) },
            offline = { offlineRepository.getShow(itemId) },
        )

    override suspend fun getSeason(itemId: UUID): SpatialFinSeason =
        runWithFallback(
            online = { onlineRepository.getSeason(itemId) },
            offline = { offlineRepository.getSeason(itemId) },
        )

    override suspend fun getLibraries(): List<SpatialFinCollection> =
        runWithFallback(
            online = { onlineRepository.getLibraries() },
            offline = { offlineRepository.getLibraries() },
        )

    override suspend fun getItem(itemId: UUID): SpatialFinItem? =
        runWithFallback(
            online = { onlineRepository.getItem(itemId) },
            offline = { offlineRepository.getItem(itemId) },
        )

    override suspend fun getItems(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
        startIndex: Int?,
        limit: Int?,
    ): List<SpatialFinItem> =
        runWithFallback(
            online = {
                onlineRepository.getItems(
                    parentId = parentId,
                    includeTypes = includeTypes,
                    recursive = recursive,
                    sortBy = sortBy,
                    sortOrder = sortOrder,
                    startIndex = startIndex,
                    limit = limit,
                )
            },
            offline = {
                offlineRepository.getItems(
                    parentId = parentId,
                    includeTypes = includeTypes,
                    recursive = recursive,
                    sortBy = sortBy,
                    sortOrder = sortOrder,
                    startIndex = startIndex,
                    limit = limit,
                )
            },
        )

    override suspend fun getItemsPaging(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
    ): Flow<PagingData<SpatialFinItem>> {
        return if (shouldUseOffline()) {
            offlineRepository.getItemsPaging(parentId, includeTypes, recursive, sortBy, sortOrder)
        } else {
            onlineRepository.getItemsPaging(parentId, includeTypes, recursive, sortBy, sortOrder)
        }
    }

    override suspend fun getPerson(personId: UUID): SpatialFinPerson =
        runWithFallback(
            online = { onlineRepository.getPerson(personId) },
            offline = { offlineRepository.getPerson(personId) },
        )

    override suspend fun getPersonItems(
        personIds: List<UUID>,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
    ): List<SpatialFinItem> =
        runWithFallback(
            online = { onlineRepository.getPersonItems(personIds, includeTypes, recursive) },
            offline = { offlineRepository.getPersonItems(personIds, includeTypes, recursive) },
        )

    override suspend fun getFavoriteItems(): List<SpatialFinItem> =
        runWithFallback(
            online = { onlineRepository.getFavoriteItems() },
            offline = { offlineRepository.getFavoriteItems() },
        )

    override suspend fun getSearchItems(query: String): List<SpatialFinItem> =
        runWithFallback(
            online = { onlineRepository.getSearchItems(query) },
            offline = { offlineRepository.getSearchItems(query) },
        )

    override suspend fun getAudioAlbums(
        parentId: UUID?,
        artistId: UUID?,
        limit: Int?,
    ): List<SpatialFinMusicAlbum> =
        runWithFallback(
            online = { onlineRepository.getAudioAlbums(parentId, artistId, limit) },
            offline = { offlineRepository.getAudioAlbums(parentId, artistId, limit) },
        )

    override suspend fun getAudioAlbumTracks(albumId: UUID): List<SpatialFinAudioTrack> =
        runWithFallback(
            online = { onlineRepository.getAudioAlbumTracks(albumId) },
            offline = { offlineRepository.getAudioAlbumTracks(albumId) },
        )

    override suspend fun getAudioArtists(
        parentId: UUID?,
        limit: Int?,
    ): List<SpatialFinMusicArtist> =
        runWithFallback(
            online = { onlineRepository.getAudioArtists(parentId, limit) },
            offline = { offlineRepository.getAudioArtists(parentId, limit) },
        )

    override suspend fun getAudioArtistTracks(
        parentId: UUID?,
        artistId: UUID,
        limit: Int?,
    ): List<SpatialFinAudioTrack> =
        runWithFallback(
            online = { onlineRepository.getAudioArtistTracks(parentId, artistId, limit) },
            offline = { offlineRepository.getAudioArtistTracks(parentId, artistId, limit) },
        )

    override suspend fun getAudioSongs(
        parentId: UUID?,
        limit: Int?,
    ): List<SpatialFinAudioTrack> =
        runWithFallback(
            online = { onlineRepository.getAudioSongs(parentId, limit) },
            offline = { offlineRepository.getAudioSongs(parentId, limit) },
        )

    override suspend fun getAudioPlaylists(
        parentId: UUID?,
        limit: Int?,
    ): List<SpatialFinPlaylist> =
        runWithFallback(
            online = { onlineRepository.getAudioPlaylists(parentId, limit) },
            offline = { offlineRepository.getAudioPlaylists(parentId, limit) },
        )

    override suspend fun getAudioPlaylistTracks(playlistId: UUID): List<SpatialFinAudioTrack> =
        runWithFallback(
            online = { onlineRepository.getAudioPlaylistTracks(playlistId) },
            offline = { offlineRepository.getAudioPlaylistTracks(playlistId) },
        )

    override suspend fun getAudioBooks(
        parentId: UUID?,
        limit: Int?,
    ): List<SpatialFinAudioBook> =
        runWithFallback(
            online = { onlineRepository.getAudioBooks(parentId, limit) },
            offline = { offlineRepository.getAudioBooks(parentId, limit) },
        )

    override suspend fun getAudioBookTracks(bookId: UUID): List<SpatialFinAudioTrack> =
        runWithFallback(
            online = { onlineRepository.getAudioBookTracks(bookId) },
            offline = { offlineRepository.getAudioBookTracks(bookId) },
        )

    override suspend fun getAudioSearchItems(
        query: String,
        parentId: UUID?,
        limit: Int?,
    ): List<SpatialFinItem> =
        runWithFallback(
            online = { onlineRepository.getAudioSearchItems(query, parentId, limit) },
            offline = { offlineRepository.getAudioSearchItems(query, parentId, limit) },
        )

    override suspend fun getAudioTrack(itemId: UUID): SpatialFinAudioTrack? =
        runWithFallback(
            online = { onlineRepository.getAudioTrack(itemId) },
            offline = { offlineRepository.getAudioTrack(itemId) },
        )

    override suspend fun getAudioLyrics(itemId: UUID): SpatialFinLyrics? =
        runWithFallback(
            online = { onlineRepository.getAudioLyrics(itemId) },
            offline = { offlineRepository.getAudioLyrics(itemId) },
        )

    override suspend fun getSuggestions(): List<SpatialFinItem> =
        runWithFallback(
            online = { onlineRepository.getSuggestions() },
            offline = { offlineRepository.getSuggestions() },
        )

    override suspend fun getResumeItems(): List<SpatialFinItem> =
        runWithFallback(
            online = { onlineRepository.getResumeItems() },
            offline = { offlineRepository.getResumeItems() },
        )

    override suspend fun getLatestMedia(parentId: UUID): List<SpatialFinItem> =
        runWithFallback(
            online = { onlineRepository.getLatestMedia(parentId) },
            offline = { offlineRepository.getLatestMedia(parentId) },
        )

    override suspend fun getSimilarItems(itemId: UUID, limit: Int): List<SpatialFinItem> =
        runWithFallback(
            online = { onlineRepository.getSimilarItems(itemId, limit) },
            offline = { offlineRepository.getSimilarItems(itemId, limit) },
        )

    override suspend fun getSeasons(seriesId: UUID, offline: Boolean): List<SpatialFinSeason> =
        if (offline || shouldUseOffline()) {
            offlineRepository.getSeasons(seriesId, true)
        } else {
            runWithFallback(
                online = { onlineRepository.getSeasons(seriesId, false) },
                offline = { offlineRepository.getSeasons(seriesId, true) },
            )
        }

    override suspend fun getNextUp(seriesId: UUID?): List<SpatialFinEpisode> =
        runWithFallback(
            online = { onlineRepository.getNextUp(seriesId) },
            offline = { offlineRepository.getNextUp(seriesId) },
        )

    override suspend fun getEpisodes(
        seriesId: UUID,
        seasonId: UUID,
        fields: List<ItemFields>?,
        startItemId: UUID?,
        limit: Int?,
        offline: Boolean,
    ): List<SpatialFinEpisode> =
        if (offline || shouldUseOffline()) {
            offlineRepository.getEpisodes(seriesId, seasonId, fields, startItemId, limit, true)
        } else {
            runWithFallback(
                online = {
                    onlineRepository.getEpisodes(
                        seriesId,
                        seasonId,
                        fields,
                        startItemId,
                        limit,
                        false,
                    )
                },
                offline = {
                    offlineRepository.getEpisodes(
                        seriesId,
                        seasonId,
                        fields,
                        startItemId,
                        limit,
                        true,
                    )
                },
            )
        }

    override suspend fun getMediaSources(
        itemId: UUID,
        includePath: Boolean,
        maxBitrate: Long?,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        mediaSourceId: String?,
    ): List<SpatialFinSource> =
        runWithFallback(
            online = {
                onlineRepository.getMediaSources(
                    itemId, includePath, maxBitrate, audioStreamIndex, subtitleStreamIndex, mediaSourceId,
                )
            },
            offline = {
                offlineRepository.getMediaSources(
                    itemId, includePath, maxBitrate, audioStreamIndex, subtitleStreamIndex, mediaSourceId,
                )
            },
        )

    override suspend fun getStreamUrl(
        itemId: UUID,
        mediaSourceId: String,
        audioStreamIndex: Int?,
    ): String =
        runOnlineOnly { onlineRepository.getStreamUrl(itemId, mediaSourceId, audioStreamIndex) }

    override suspend fun getAudioStreamUrl(
        itemId: UUID,
        mediaSourceId: String?,
    ): String =
        runOnlineOnly { onlineRepository.getAudioStreamUrl(itemId, mediaSourceId) }

    override suspend fun getAudioTranscodeStreamUrl(
        itemId: UUID,
        mediaSourceId: String,
        allowedAudioCodecs: List<String>,
        startPositionMs: Long,
        audioStreamIndex: Int?,
    ): String =
        runOnlineOnly {
            onlineRepository.getAudioTranscodeStreamUrl(
                itemId,
                mediaSourceId,
                allowedAudioCodecs,
                startPositionMs,
                audioStreamIndex,
            )
        }

    override fun getUniversalAudioStreamUrl(
        itemId: UUID,
        mediaSourceId: String?,
        startPositionTicks: Long,
    ): String =
        if (shouldUseOffline()) {
            offlineRepository.getUniversalAudioStreamUrl(itemId, mediaSourceId, startPositionTicks)
        } else {
            onlineRepository.getUniversalAudioStreamUrl(itemId, mediaSourceId, startPositionTicks)
        }

    override suspend fun getMediaAttachment(
        itemId: UUID,
        mediaSourceId: String,
        attachmentIndex: Int,
    ): ByteArray? =
        runOnlineOnly { onlineRepository.getMediaAttachment(itemId, mediaSourceId, attachmentIndex) }

    override suspend fun getSegments(itemId: UUID): List<SpatialFinSegment> =
        runWithFallback(
            online = { onlineRepository.getSegments(itemId) },
            offline = { offlineRepository.getSegments(itemId) },
        )

    override suspend fun getTrickplayData(itemId: UUID, width: Int, index: Int): ByteArray? =
        runWithFallback(
            online = { onlineRepository.getTrickplayData(itemId, width, index) },
            offline = { offlineRepository.getTrickplayData(itemId, width, index) },
        )

    override suspend fun getSyncPlayGroups(): List<SyncPlayGroup> =
        runOnlineOnly { onlineRepository.getSyncPlayGroups() }

    override suspend fun createSyncPlayGroup(name: String): SyncPlayGroup =
        runOnlineOnly { onlineRepository.createSyncPlayGroup(name) }

    override suspend fun joinSyncPlayGroup(groupId: UUID) {
        runOnlineOnly { onlineRepository.joinSyncPlayGroup(groupId) }
    }

    override suspend fun leaveSyncPlayGroup() {
        runWrite(
            online = { onlineRepository.leaveSyncPlayGroup() },
            offline = { offlineRepository.leaveSyncPlayGroup() },
        )
    }

    override suspend fun setSyncPlayQueue(
        itemIds: List<UUID>,
        playingItemIndex: Int,
        startPositionTicks: Long,
    ) {
        runOnlineOnly {
            onlineRepository.setSyncPlayQueue(itemIds, playingItemIndex, startPositionTicks)
        }
    }

    override suspend fun pauseSyncPlay() {
        runOnlineOnly { onlineRepository.pauseSyncPlay() }
    }

    override suspend fun unpauseSyncPlay() {
        runOnlineOnly { onlineRepository.unpauseSyncPlay() }
    }

    override suspend fun seekSyncPlay(positionTicks: Long) {
        runOnlineOnly { onlineRepository.seekSyncPlay(positionTicks) }
    }

    override suspend fun stopSyncPlay() {
        runOnlineOnly { onlineRepository.stopSyncPlay() }
    }

    override suspend fun nextSyncPlayItem(playlistItemId: UUID) {
        runOnlineOnly { onlineRepository.nextSyncPlayItem(playlistItemId) }
    }

    override suspend fun previousSyncPlayItem(playlistItemId: UUID) {
        runOnlineOnly { onlineRepository.previousSyncPlayItem(playlistItemId) }
    }

    override fun observePlayStateMessages(): Flow<PlaystateMessage> =
        observeRealtime(
            online = { onlineRepository.observePlayStateMessages() },
            offline = { offlineRepository.observePlayStateMessages() },
        )

    override fun observeSyncPlayCommandMessages(): Flow<SyncPlayCommandMessage> =
        observeRealtime(
            online = { onlineRepository.observeSyncPlayCommandMessages() },
            offline = { offlineRepository.observeSyncPlayCommandMessages() },
        )

    override fun observeSyncPlayGroupUpdates(): Flow<SyncPlayGroupUpdateMessage> =
        observeRealtime(
            online = { onlineRepository.observeSyncPlayGroupUpdates() },
            offline = { offlineRepository.observeSyncPlayGroupUpdates() },
        )

    override fun observeGeneralCommandMessages(): Flow<GeneralCommandMessage> =
        observeRealtime(
            online = { onlineRepository.observeGeneralCommandMessages() },
            offline = { offlineRepository.observeGeneralCommandMessages() },
        )

    override fun observeSessions(): Flow<List<org.jellyfin.sdk.model.api.SessionInfoDto>> =
        observeRealtime(
            online = { onlineRepository.observeSessions() },
            offline = { offlineRepository.observeSessions() },
        )

    override suspend fun getSessions(): List<org.jellyfin.sdk.model.api.SessionInfoDto> =
        runWithFallback(
            online = { onlineRepository.getSessions() },
            offline = { offlineRepository.getSessions() },
        )

    override suspend fun sendPlaystateCommand(
        sessionId: String,
        command: org.jellyfin.sdk.model.api.PlaystateCommand,
        seekPositionTicks: Long?,
    ) {
        runWithFallback(
            online = { onlineRepository.sendPlaystateCommand(sessionId, command, seekPositionTicks) },
            offline = { offlineRepository.sendPlaystateCommand(sessionId, command, seekPositionTicks) },
        )
    }

    override suspend fun sendGeneralCommand(
        sessionId: String,
        command: org.jellyfin.sdk.model.api.GeneralCommandType,
        arguments: Map<String, String>?,
    ) {
        runWithFallback(
            online = { onlineRepository.sendGeneralCommand(sessionId, command, arguments) },
            offline = { offlineRepository.sendGeneralCommand(sessionId, command, arguments) },
        )
    }

    override fun observeRealtimeEvents(): Flow<JellyfinRealtimeEvent> =
        observeRealtime(
            online = { onlineRepository.observeRealtimeEvents() },
            offline = { offlineRepository.observeRealtimeEvents() },
        )

    override fun observeSocketState(): Flow<SocketApiState> =
        observeRealtime(
            online = { onlineRepository.observeSocketState() },
            offline = { offlineRepository.observeSocketState() },
        )

    override suspend fun postCapabilities() {
        runWrite(
            online = { onlineRepository.postCapabilities() },
            offline = { offlineRepository.postCapabilities() },
        )
    }

    override suspend fun postPlaybackStart(itemId: UUID) {
        runWrite(
            online = { onlineRepository.postPlaybackStart(itemId) },
            offline = { offlineRepository.postPlaybackStart(itemId) },
        )
    }

    override suspend fun postPlaybackStop(
        itemId: UUID,
        positionTicks: Long,
        playedPercentage: Int,
        markedPlayed: Boolean,
    ) {
        runWrite(
            online = { onlineRepository.postPlaybackStop(itemId, positionTicks, playedPercentage, markedPlayed) },
            offline = {
                offlineRepository.postPlaybackStop(itemId, positionTicks, playedPercentage, markedPlayed)
            },
        )
    }

    override suspend fun postPlaybackProgress(itemId: UUID, positionTicks: Long, isPaused: Boolean) {
        runWrite(
            online = { onlineRepository.postPlaybackProgress(itemId, positionTicks, isPaused) },
            offline = { offlineRepository.postPlaybackProgress(itemId, positionTicks, isPaused) },
        )
    }

    override suspend fun markAsFavorite(itemId: UUID) {
        runWrite(
            online = { onlineRepository.markAsFavorite(itemId) },
            offline = { offlineRepository.markAsFavorite(itemId) },
        )
    }

    override suspend fun unmarkAsFavorite(itemId: UUID) {
        runWrite(
            online = { onlineRepository.unmarkAsFavorite(itemId) },
            offline = { offlineRepository.unmarkAsFavorite(itemId) },
        )
    }

    override suspend fun markAsPlayed(itemId: UUID) {
        runWrite(
            online = { onlineRepository.markAsPlayed(itemId) },
            offline = { offlineRepository.markAsPlayed(itemId) },
        )
    }

    override suspend fun markAsUnplayed(itemId: UUID) {
        runWrite(
            online = { onlineRepository.markAsUnplayed(itemId) },
            offline = { offlineRepository.markAsUnplayed(itemId) },
        )
    }

    override suspend fun refreshItemMetadata(itemId: UUID) {
        runWrite(
            online = { onlineRepository.refreshItemMetadata(itemId) },
            offline = { offlineRepository.refreshItemMetadata(itemId) },
        )
    }

    override suspend fun getItemProviderIds(itemId: UUID): Map<String, String> =
        runWithFallback(
            online = { onlineRepository.getItemProviderIds(itemId) },
            offline = { offlineRepository.getItemProviderIds(itemId) },
        )

    override suspend fun setItemProviderId(
        itemId: UUID,
        providerKey: String,
        value: String?,
    ): Boolean =
        runWithFallback(
            online = { onlineRepository.setItemProviderId(itemId, providerKey, value) },
            offline = { offlineRepository.setItemProviderId(itemId, providerKey, value) },
        )

    override suspend fun deleteItem(itemId: UUID): Boolean =
        runWithFallback(
            online = { onlineRepository.deleteItem(itemId) },
            offline = { offlineRepository.deleteItem(itemId) },
        )

    override fun getBaseUrl(): String = onlineRepository.getBaseUrl()

    override suspend fun updateDeviceName(name: String) {
        runWrite(
            online = { onlineRepository.updateDeviceName(name) },
            offline = { offlineRepository.updateDeviceName(name) },
        )
    }

    override suspend fun getUserConfiguration(): UserConfiguration? =
        runWithFallback(
            online = { onlineRepository.getUserConfiguration() },
            offline = { offlineRepository.getUserConfiguration() },
        )

    override suspend fun getDownloads(): List<SpatialFinItem> =
        runWithFallback(
            online = { onlineRepository.getDownloads() },
            offline = { offlineRepository.getDownloads() },
        )

    override fun getUserId(): UUID =
        if (shouldUseOffline()) offlineRepository.getUserId() else onlineRepository.getUserId()

    private suspend fun <T> runWithFallback(
        online: suspend () -> T,
        offline: suspend () -> T,
    ): T = fetchWithFallback(online, offline).value

    private suspend fun <T> fetchWithFallback(
        online: suspend () -> T,
        offline: suspend () -> T,
    ): Fetched<T> {
        if (shouldUseOffline()) {
            return Fetched(offline(), isOffline = true)
        }
        val expectedEpoch = sessionActivationCoordinator.sessionEpoch.get()
        return try {
            Fetched(
                online().also { connectionMonitor.markServerAccessible(expectedEpoch) },
                isOffline = false,
            )
        } catch (throwable: Throwable) {
            if (isEpochMismatchedNoUser(throwable, expectedEpoch)) {
                return Fetched(offline(), isOffline = true)
            }
            if (!connectionMonitor.isConnectionFailure(throwable)) throw throwable
            connectionMonitor.markServerInaccessible(expectedEpoch)
            Fetched(offline(), isOffline = true)
        }
    }

    suspend fun fetchUserViews(): Fetched<List<BaseItemDto>> =
        fetchWithFallback(
            online = { onlineRepository.getUserViews() },
            offline = { offlineRepository.getUserViews() },
        )

    suspend fun fetchLibraries(): Fetched<List<SpatialFinCollection>> =
        fetchWithFallback(
            online = { onlineRepository.getLibraries() },
            offline = { offlineRepository.getLibraries() },
        )

    suspend fun fetchSuggestions(): Fetched<List<SpatialFinItem>> =
        fetchWithFallback(
            online = { onlineRepository.getSuggestions() },
            offline = { offlineRepository.getSuggestions() },
        )

    suspend fun fetchResumeItems(): Fetched<List<SpatialFinItem>> =
        fetchWithFallback(
            online = { onlineRepository.getResumeItems() },
            offline = { offlineRepository.getResumeItems() },
        )

    suspend fun fetchLatestMedia(parentId: UUID): Fetched<List<SpatialFinItem>> =
        fetchWithFallback(
            online = { onlineRepository.getLatestMedia(parentId) },
            offline = { offlineRepository.getLatestMedia(parentId) },
        )

    suspend fun fetchItems(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
        startIndex: Int? = null,
        limit: Int? = null,
    ): Fetched<List<SpatialFinItem>> =
        fetchWithFallback(
            online = {
                onlineRepository.getItems(
                    parentId = parentId,
                    includeTypes = includeTypes,
                    recursive = recursive,
                    sortBy = sortBy,
                    sortOrder = sortOrder,
                    startIndex = startIndex,
                    limit = limit,
                )
            },
            offline = {
                offlineRepository.getItems(
                    parentId = parentId,
                    includeTypes = includeTypes,
                    recursive = recursive,
                    sortBy = sortBy,
                    sortOrder = sortOrder,
                    startIndex = startIndex,
                    limit = limit,
                )
            },
        )

    private suspend fun <T> runOnlineOnly(online: suspend () -> T): T {
        if (shouldUseOffline()) {
            throw IllegalStateException("Server is currently unavailable")
        }
        val expectedEpoch = sessionActivationCoordinator.sessionEpoch.get()
        return try {
            online().also { connectionMonitor.markServerAccessible(expectedEpoch) }
        } catch (throwable: Throwable) {
            if (isEpochMismatchedNoUser(throwable, expectedEpoch)) {
                throw IllegalStateException("Server is currently unavailable", throwable)
            }
            if (connectionMonitor.isConnectionFailure(throwable)) {
                connectionMonitor.markServerInaccessible(expectedEpoch)
            }
            throw throwable
        }
    }

    private suspend fun runWrite(
        online: suspend () -> Unit,
        offline: suspend () -> Unit,
    ) {
        val expectedEpoch = sessionActivationCoordinator.sessionEpoch.get()
        if (shouldUseOffline()) {
            if (sessionActivationCoordinator.sessionEpoch.get() == expectedEpoch &&
                !sessionActivationCoordinator.isActivating.get()
            ) {
                offline()
            }
            return
        }
        try {
            online()
            connectionMonitor.markServerAccessible(expectedEpoch)
            if (sessionActivationCoordinator.sessionEpoch.get() == expectedEpoch &&
                !sessionActivationCoordinator.isActivating.get()
            ) {
                offline()
            }
        } catch (throwable: Throwable) {
            if (isEpochMismatchedNoUser(throwable, expectedEpoch)) {
                return
            }
            if (!connectionMonitor.isConnectionFailure(throwable)) throw throwable
            connectionMonitor.markServerInaccessible(expectedEpoch)
            if (sessionActivationCoordinator.sessionEpoch.get() == expectedEpoch &&
                !sessionActivationCoordinator.isActivating.get()
            ) {
                offline()
            }
        }
    }

    override suspend fun searchRemoteSubtitles(
        itemId: UUID,
        language: String,
    ): List<org.jellyfin.sdk.model.api.RemoteSubtitleInfo> {
        return runWithFallback(
            online = { onlineRepository.searchRemoteSubtitles(itemId, language) },
            offline = { offlineRepository.searchRemoteSubtitles(itemId, language) },
        )
    }

    override suspend fun downloadRemoteSubtitles(itemId: UUID, subtitleId: String) {
        runWithFallback(
            online = { onlineRepository.downloadRemoteSubtitles(itemId, subtitleId) },
            offline = { offlineRepository.downloadRemoteSubtitles(itemId, subtitleId) },
        )
    }

    override fun getAccessToken(): String? = onlineRepository.getAccessToken()

    override fun getDeviceId(): String? = onlineRepository.getDeviceId()
}
