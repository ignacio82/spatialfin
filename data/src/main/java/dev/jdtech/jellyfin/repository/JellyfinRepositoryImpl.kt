package dev.jdtech.jellyfin.repository

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
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
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.models.toSpatialFinCollection
import dev.jdtech.jellyfin.models.toSpatialFinEpisode
import dev.jdtech.jellyfin.models.toSpatialFinItem
import dev.jdtech.jellyfin.models.toSpatialFinMovie
import dev.jdtech.jellyfin.models.toSpatialFinPerson
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
import org.jellyfin.sdk.model.api.GeneralCommandMessage
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.GroupInfoDto
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
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.SubtitleProfile
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
                )
                .content
                .items
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
                config = PagingConfig(pageSize = 10, enablePlaceholders = false),
                pagingSourceFactory = {
                    ItemsPagingSource(this, parentId, includeTypes, recursive, sortBy, sortOrder)
                },
            )
            .flow
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
            jellyfinApi.itemsApi
                .getItems(
                    jellyfinApi.userId!!,
                    personIds = personIds,
                    includeItemTypes = includeTypes,
                    recursive = recursive,
                )
                .content
                .items
                .mapNotNull { it.toSpatialFinItem(this@JellyfinRepositoryImpl, database) }
        }

    override suspend fun getFavoriteItems(): List<SpatialFinItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.itemsApi
                .getItems(
                    jellyfinApi.userId!!,
                    filters = listOf(ItemFilter.IS_FAVORITE),
                    includeItemTypes =
                        listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE),
                    recursive = true,
                )
                .content
                .items
                .mapNotNull { it.toSpatialFinItem(this@JellyfinRepositoryImpl, database) }
        }

    override suspend fun getSearchItems(query: String): List<SpatialFinItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.itemsApi
                .getItems(
                    jellyfinApi.userId!!,
                    searchTerm = query,
                    includeItemTypes =
                        listOf(
                            BaseItemKind.MOVIE,
                            BaseItemKind.SERIES,
                            BaseItemKind.EPISODE,
                            BaseItemKind.BOX_SET,
                        ),
                    recursive = true,
                )
                .content
                .items
                .mapNotNull { it.toSpatialFinItem(this@JellyfinRepositoryImpl, database) }
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
                .mapNotNull { it.toSpatialFinItem(this@JellyfinRepositoryImpl, database) }
        }

    override suspend fun getResumeItems(): List<SpatialFinItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.itemsApi
                .getResumeItems(
                    jellyfinApi.userId!!,
                    limit = 12,
                    includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE),
                )
                .content
                .items
                .mapNotNull { it.toSpatialFinItem(this@JellyfinRepositoryImpl, database) }
                .let(ResumeFilter::keepResumable)
        }

    override suspend fun getLatestMedia(parentId: UUID): List<SpatialFinItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.userLibraryApi
                .getLatestMedia(jellyfinApi.userId!!, parentId = parentId, limit = 16)
                .content
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
        maxBitrate: Long?
    ): List<SpatialFinSource> =
        withContext(Dispatchers.IO) {
            downloadStorageManager.reconcileItem(itemId, jellyfinApi.userId)
            val bitrate = (maxBitrate ?: appPreferences.getValue(appPreferences.playerMaxBitrate)).let {
                if (it <= 0L) 1_000_000_000L else it
            }
            val sources = mutableListOf<SpatialFinSource>()
            sources.addAll(
                jellyfinApi.mediaInfoApi
                    .getPostedPlaybackInfo(
                        itemId,
                        PlaybackInfoDto(
                            userId = jellyfinApi.userId!!,
                            deviceProfile =
                                DeviceProfile(
                                    name = "Direct play all",
                                    maxStaticBitrate = bitrate.toInt(),
                                    maxStreamingBitrate = bitrate.toInt(),
                                    codecProfiles = emptyList(),
                                    containerProfiles = emptyList(),
                                    // A single catch-all VIDEO profile tells Jellyfin this device
                                    // can direct-play any container (MKV, MP4, TS, …) including
                                    // HDR10, HDR10+, and Dolby Vision. Without at least one entry
                                    // Jellyfin treats the device as supporting nothing and may
                                    // return a transcoded (SDR) URL for HTTP-protocol sources.
                                    directPlayProfiles = listOf(
                                        // Empty container = all containers. Tells Jellyfin this
                                        // device can direct-play MKV, MP4, TS, etc., including
                                        // HDR10, HDR10+, and Dolby Vision streams.
                                        DirectPlayProfile(
                                            container = "",
                                            type = DlnaProfileType.VIDEO,
                                        ),
                                    ),
                                    // One HLS-over-TS transcoding profile. Used only when Jellyfin
                                    // decides the source can't be direct-played within the current
                                    // bitrate cap — it then returns an .m3u8 URL in
                                    // MediaSourceInfo.transcodingUrl and the client picks that up.
                                    transcodingProfiles = listOf(
                                        TranscodingProfile(
                                            container = "ts",
                                            type = DlnaProfileType.VIDEO,
                                            videoCodec = "h264,hevc",
                                            audioCodec = "aac,mp3,ac3,eac3",
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
                                    subtitleProfiles =
                                        listOf(
                                            SubtitleProfile("srt", SubtitleDeliveryMethod.EXTERNAL),
                                            SubtitleProfile("ass", SubtitleDeliveryMethod.EXTERNAL),
                                        ),
                                ),
                            maxStreamingBitrate = bitrate.toInt(),
                        ),
                    )
                    .content
                    .mediaSources
                    .map { it.toSpatialFinSource(this@JellyfinRepositoryImpl, itemId, includePath) }
            )
            sources.addAll(database.getSources(itemId).map { it.toSpatialFinSource(database) })
            sources
        }

    override suspend fun getStreamUrl(itemId: UUID, mediaSourceId: String): String =
        withContext(Dispatchers.IO) {
            try {
                jellyfinApi.videosApi.getVideoStreamUrl(
                    itemId,
                    static = true,
                    mediaSourceId = mediaSourceId,
                )
            } catch (e: Exception) {
                Timber.e(e)
                ""
            }
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
                playableMediaTypes = listOf(MediaType.VIDEO),
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
    ) {
        Timber.d("Sending stop $itemId")
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

    override suspend fun deleteItem(itemId: UUID): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { jellyfinApi.libraryApi.deleteItem(itemId) }.isSuccess
        }

    override fun getBaseUrl() = jellyfinApi.api.baseUrl.orEmpty()

    override fun getAccessToken(): String? = jellyfinApi.api.accessToken

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
            runCatching { UUID.fromString(id) }
                .onFailure { Timber.w(it, "Ignoring non-UUID realtime item id %s", id) }
                .getOrNull()
        }.toSet()
}
