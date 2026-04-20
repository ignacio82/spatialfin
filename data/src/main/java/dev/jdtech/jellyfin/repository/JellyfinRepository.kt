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
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.GeneralCommandMessage
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.PlaystateMessage
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.jellyfin.sdk.model.api.SyncPlayCommandMessage
import org.jellyfin.sdk.model.api.SyncPlayGroupUpdateMessage
import org.jellyfin.sdk.model.api.UserConfiguration
import org.jellyfin.sdk.api.sockets.SocketApiState

interface JellyfinRepository {
    suspend fun getPublicSystemInfo(): PublicSystemInfo

    suspend fun getUserViews(): List<BaseItemDto>

    suspend fun getEpisode(itemId: UUID): SpatialFinEpisode

    suspend fun getMovie(itemId: UUID): SpatialFinMovie

    suspend fun getShow(itemId: UUID): SpatialFinShow

    suspend fun getSeason(itemId: UUID): SpatialFinSeason

    suspend fun getLibraries(): List<SpatialFinCollection>

    suspend fun getItem(itemId: UUID): SpatialFinItem?

    suspend fun getItems(
        parentId: UUID? = null,
        includeTypes: List<BaseItemKind>? = null,
        recursive: Boolean = false,
        sortBy: SortBy = SortBy.defaultValue,
        sortOrder: SortOrder = SortOrder.ASCENDING,
        startIndex: Int? = null,
        limit: Int? = null,
    ): List<SpatialFinItem>

    suspend fun getItemsPaging(
        parentId: UUID? = null,
        includeTypes: List<BaseItemKind>? = null,
        recursive: Boolean = false,
        sortBy: SortBy = SortBy.defaultValue,
        sortOrder: SortOrder = SortOrder.ASCENDING,
    ): Flow<PagingData<SpatialFinItem>>

    suspend fun getPerson(personId: UUID): SpatialFinPerson

    suspend fun getPersonItems(
        personIds: List<UUID>,
        includeTypes: List<BaseItemKind>? = null,
        recursive: Boolean = true,
    ): List<SpatialFinItem>

    suspend fun getFavoriteItems(): List<SpatialFinItem>

    suspend fun getSearchItems(query: String): List<SpatialFinItem>

    suspend fun getSuggestions(): List<SpatialFinItem>

    suspend fun getResumeItems(): List<SpatialFinItem>

    suspend fun getLatestMedia(parentId: UUID): List<SpatialFinItem>

    suspend fun getSeasons(seriesId: UUID, offline: Boolean = false): List<SpatialFinSeason>

    suspend fun getNextUp(seriesId: UUID? = null): List<SpatialFinEpisode>

    suspend fun getEpisodes(
        seriesId: UUID,
        seasonId: UUID,
        fields: List<ItemFields>? = null,
        startItemId: UUID? = null,
        limit: Int? = null,
        offline: Boolean = false,
    ): List<SpatialFinEpisode>

    suspend fun getMediaSources(
        itemId: UUID,
        includePath: Boolean = false,
        maxBitrate: Long? = null
    ): List<SpatialFinSource>

    suspend fun getStreamUrl(itemId: UUID, mediaSourceId: String): String

    suspend fun getMediaAttachment(
        itemId: UUID,
        mediaSourceId: String,
        attachmentIndex: Int,
    ): ByteArray?

    suspend fun getSegments(itemId: UUID): List<SpatialFinSegment>

    suspend fun getTrickplayData(itemId: UUID, width: Int, index: Int): ByteArray?

    suspend fun getSyncPlayGroups(): List<SyncPlayGroup>

    suspend fun createSyncPlayGroup(name: String): SyncPlayGroup

    suspend fun joinSyncPlayGroup(groupId: UUID)

    suspend fun leaveSyncPlayGroup()

    suspend fun setSyncPlayQueue(itemIds: List<UUID>, playingItemIndex: Int, startPositionTicks: Long)

    suspend fun pauseSyncPlay()

    suspend fun unpauseSyncPlay()

    suspend fun seekSyncPlay(positionTicks: Long)

    suspend fun stopSyncPlay()

    suspend fun nextSyncPlayItem(playlistItemId: UUID)

    suspend fun previousSyncPlayItem(playlistItemId: UUID)

    fun observePlayStateMessages(): Flow<PlaystateMessage>

    fun observeSyncPlayCommandMessages(): Flow<SyncPlayCommandMessage>

    fun observeSyncPlayGroupUpdates(): Flow<SyncPlayGroupUpdateMessage>

    fun observeGeneralCommandMessages(): Flow<GeneralCommandMessage>

    fun observeRealtimeEvents(): Flow<JellyfinRealtimeEvent>

    fun observeSocketState(): Flow<SocketApiState>

    suspend fun postCapabilities()

    suspend fun postPlaybackStart(itemId: UUID)

    suspend fun postPlaybackStop(itemId: UUID, positionTicks: Long, playedPercentage: Int)

    suspend fun postPlaybackProgress(itemId: UUID, positionTicks: Long, isPaused: Boolean)

    suspend fun markAsFavorite(itemId: UUID)

    suspend fun searchRemoteSubtitles(itemId: UUID, language: String): List<org.jellyfin.sdk.model.api.RemoteSubtitleInfo>

    suspend fun downloadRemoteSubtitles(itemId: UUID, subtitleId: String)

    suspend fun unmarkAsFavorite(itemId: UUID)

    suspend fun markAsPlayed(itemId: UUID)

    suspend fun markAsUnplayed(itemId: UUID)

    /** Trigger Jellyfin's metadata refresh for an item. */
    suspend fun refreshItemMetadata(itemId: UUID)

    /** Ask Jellyfin to delete an item from the library. Returns true on success. */
    suspend fun deleteItem(itemId: UUID): Boolean

    fun getBaseUrl(): String

    fun getAccessToken(): String?

    suspend fun updateDeviceName(name: String)

    suspend fun getUserConfiguration(): UserConfiguration?

    suspend fun getDownloads(): List<SpatialFinItem>

    fun getUserId(): UUID
}
