package dev.jdtech.jellyfin.repository

import androidx.paging.PagingData
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
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.offline.ServerConnectionMonitor
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
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

@Singleton
class SmartJellyfinRepository
@Inject
constructor(
    private val onlineRepository: JellyfinRepositoryImpl,
    private val offlineRepository: JellyfinRepositoryOfflineImpl,
    private val connectionMonitor: ServerConnectionMonitor,
) : JellyfinRepository {

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
        return if (connectionMonitor.shouldUseOfflineRepository()) {
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

    override suspend fun getSeasons(seriesId: UUID, offline: Boolean): List<SpatialFinSeason> =
        if (offline || connectionMonitor.shouldUseOfflineRepository()) {
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
        if (offline || connectionMonitor.shouldUseOfflineRepository()) {
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
    ): List<SpatialFinSource> =
        runWithFallback(
            online = { onlineRepository.getMediaSources(itemId, includePath, maxBitrate) },
            offline = { offlineRepository.getMediaSources(itemId, includePath, maxBitrate) },
        )

    override suspend fun getStreamUrl(itemId: UUID, mediaSourceId: String): String =
        runOnlineOnly { onlineRepository.getStreamUrl(itemId, mediaSourceId) }

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
        onlineRepository.observePlayStateMessages()

    override fun observeSyncPlayCommandMessages(): Flow<SyncPlayCommandMessage> =
        onlineRepository.observeSyncPlayCommandMessages()

    override fun observeSyncPlayGroupUpdates(): Flow<SyncPlayGroupUpdateMessage> =
        onlineRepository.observeSyncPlayGroupUpdates()

    override fun observeGeneralCommandMessages(): Flow<GeneralCommandMessage> =
        onlineRepository.observeGeneralCommandMessages()

    override fun observeRealtimeEvents(): Flow<JellyfinRealtimeEvent> =
        onlineRepository.observeRealtimeEvents()

    override fun observeSocketState(): Flow<SocketApiState> =
        onlineRepository.observeSocketState()

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

    override suspend fun postPlaybackStop(itemId: UUID, positionTicks: Long, playedPercentage: Int) {
        runWrite(
            online = { onlineRepository.postPlaybackStop(itemId, positionTicks, playedPercentage) },
            offline = {
                offlineRepository.postPlaybackStop(itemId, positionTicks, playedPercentage)
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

    override fun getUserId(): UUID = onlineRepository.getUserId()

    private suspend fun <T> runWithFallback(
        online: suspend () -> T,
        offline: suspend () -> T,
    ): T {
        if (connectionMonitor.shouldUseOfflineRepository()) return offline()
        return try {
            online().also { connectionMonitor.markServerAccessible() }
        } catch (throwable: Throwable) {
            if (!connectionMonitor.isConnectionFailure(throwable)) throw throwable
            connectionMonitor.markServerInaccessible()
            offline()
        }
    }

    private suspend fun <T> runOnlineOnly(online: suspend () -> T): T {
        if (connectionMonitor.shouldUseOfflineRepository()) {
            throw IllegalStateException("Server is currently unavailable")
        }
        return try {
            online().also { connectionMonitor.markServerAccessible() }
        } catch (throwable: Throwable) {
            if (connectionMonitor.isConnectionFailure(throwable)) {
                connectionMonitor.markServerInaccessible()
            }
            throw throwable
        }
    }

    private suspend fun runWrite(
        online: suspend () -> Unit,
        offline: suspend () -> Unit,
    ) {
        if (connectionMonitor.shouldUseOfflineRepository()) {
            offline()
            return
        }
        try {
            online()
            connectionMonitor.markServerAccessible()
        } catch (throwable: Throwable) {
            if (!connectionMonitor.isConnectionFailure(throwable)) throw throwable
            connectionMonitor.markServerInaccessible()
            offline()
        }
    }

    override suspend fun searchRemoteSubtitles(
        itemId: UUID,
        language: String
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
}
