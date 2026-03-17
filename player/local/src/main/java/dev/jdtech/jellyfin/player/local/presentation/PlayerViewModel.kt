package dev.jdtech.jellyfin.player.local.presentation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.SpatialFinSegment
import dev.jdtech.jellyfin.models.SpatialFinSegmentType
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.models.SyncPlayGroup
import dev.jdtech.jellyfin.models.toSyncPlayGroup
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter
import dev.jdtech.jellyfin.player.core.domain.models.PlayerContentSource
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.core.domain.models.PlayerPerson
import dev.jdtech.jellyfin.player.core.domain.models.Trickplay
import dev.jdtech.jellyfin.player.local.R
import dev.jdtech.jellyfin.player.local.domain.PlaylistManager
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.repository.LocalMediaRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.Constants
import java.util.UUID
import javax.inject.Inject
import kotlin.math.ceil
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.sockets.SocketApiState
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.GroupStateType
import org.jellyfin.sdk.model.api.PlaystateCommand
import org.jellyfin.sdk.model.api.SyncPlayCommandMessage
import org.jellyfin.sdk.model.api.SyncPlayGroupDoesNotExistUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupJoinedUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupLeftUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupUpdateMessage
import org.jellyfin.sdk.model.api.SyncPlayNotInGroupUpdate
import org.jellyfin.sdk.model.api.SyncPlayPlayQueueUpdate
import org.jellyfin.sdk.model.api.SyncPlayQueueItem
import org.jellyfin.sdk.model.api.SyncPlayStateUpdate
import timber.log.Timber

@HiltViewModel
class PlayerViewModel
@Inject
constructor(
    private val application: Application,
    private val playlistManager: PlaylistManager,
    private val repository: JellyfinRepository,
    private val localMediaRepository: LocalMediaRepository,
    val appPreferences: AppPreferences,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel(), Player.Listener {
    private val _playerFlow: MutableStateFlow<Player>
    val playerFlow: kotlinx.coroutines.flow.StateFlow<Player>
    var player: Player
        get() = _playerFlow.value
        private set(value) {
            _playerFlow.value = value
        }

    private val _uiState =
        MutableStateFlow(
            UiState(
                currentItemTitle = "",
                currentItemId = null,
                currentItemKind = null,
                currentSegment = null,
                currentSkipButtonStringRes = R.string.player_controls_skip_intro,
                currentTrickplay = null,
                currentChapters = emptyList(),
                currentPeople = emptyList(),
                currentOverview = "",
                currentGenres = emptyList(),
                currentSeriesName = null,
                currentSeasonNumber = null,
                currentEpisodeNumber = null,
                currentProductionYear = null,
                nextEpisode = null,
                fileLoaded = false,
            )
        )
    val uiState = _uiState.asStateFlow()

    data class SyncPlayUiState(
        val isLoading: Boolean = false,
        val activeGroup: SyncPlayGroup? = null,
        val availableGroups: List<SyncPlayGroup> = emptyList(),
        val statusMessage: String? = null,
    )

    private val _syncPlayState = MutableStateFlow(SyncPlayUiState())
    val syncPlayState = _syncPlayState.asStateFlow()

    private val eventsChannel = Channel<PlayerEvents>()
    val eventsChannelFlow = eventsChannel.receiveAsFlow()

    data class UiState(
        val currentItemTitle: String,
        val currentItemId: String? = null,
        val currentItemKind: String? = null,
        val currentSegment: SpatialFinSegment?,        val currentSkipButtonStringRes: Int,
        val currentTrickplay: Trickplay?,
        val currentChapters: List<PlayerChapter>,
        val currentPeople: List<PlayerPerson>,
        val currentOverview: String,
        val currentGenres: List<String> = emptyList(),
        val currentSeriesName: String? = null,
        val currentSeasonNumber: Int? = null,
        val currentEpisodeNumber: Int? = null,
        val currentProductionYear: Int? = null,
        val storySoFarContext: String? = null,
        /** Next episode in the playlist, or null for movies / last episode of a season. */
        val nextEpisode: PlayerItem?,
        val fileLoaded: Boolean,
    )

    private var items: MutableList<PlayerItem> = mutableListOf()

    private val trackSelector = DefaultTrackSelector(application)
    var playWhenReady = true
    private var currentMediaItemIndex = savedStateHandle["mediaItemIndex"] ?: 0
    private var playbackPosition: Long = savedStateHandle["position"] ?: 0
    private var currentMediaItemSegments: List<SpatialFinSegment> = emptyList()

    // Segments preferences
    var segmentsSkipButton: Boolean = false
    private var segmentsSkipButtonTypes: Set<String> = emptySet()
    var segmentsSkipButtonDuration: Long = 0L
    var segmentsAutoSkip: Boolean = false
    private var segmentsAutoSkipTypes: Set<String> = emptySet()
    private var segmentsAutoSkipMode: String = "always"

    var playbackSpeed: Float = 1f

    var isInPictureInPictureMode: Boolean = false

    private val audioAttributes =
        AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(C.USAGE_MEDIA)
            .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_AUTO)
            .build()

    private var activeSyncPlayGroupId: UUID? = null
    private var currentSyncPlaylistItemId: UUID? = null
    private var suppressSyncUntilMs: Long = 0L
    private var isSocketConnected = false

    init {
        segmentsSkipButton = appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButton)
        segmentsSkipButtonTypes =
            appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButtonType)
        segmentsSkipButtonDuration =
            appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButtonDuration)
        segmentsAutoSkip = appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkip)
        segmentsAutoSkipTypes =
            appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkipType)
        segmentsAutoSkipMode =
            appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkipMode)

        trackSelector.setParameters(
            trackSelector
                .buildUponParameters()
                .setTunnelingEnabled(true)
                .setPreferredAudioLanguage(
                    appPreferences.getValue(appPreferences.preferredAudioLanguage)
                )
                .setPreferredTextLanguage(
                    appPreferences.getValue(appPreferences.preferredSubtitleLanguage)
                )
        )

        val renderersFactory =
            DefaultRenderersFactory(application)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                // Allow fallback to the HDR10 base-layer decoder when the primary Dolby Vision
                // decoder fails, rather than surfacing an error to the user.
                .setEnableDecoderFallback(true)

        val initialPlayer = ExoPlayer.Builder(application, renderersFactory)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(application)
                .experimentalParseSubtitlesDuringExtraction(true))
            .setAudioAttributes(audioAttributes, false)
            .setTrackSelector(trackSelector)
            .setSeekBackIncrementMs(
                appPreferences.getValue(appPreferences.playerSeekBackInc)
            )
            .setSeekForwardIncrementMs(
                appPreferences.getValue(appPreferences.playerSeekForwardInc)
            )
            .setPauseAtEndOfMediaItems(true)
            .build()
        _playerFlow = MutableStateFlow(initialPlayer)
        playerFlow = _playerFlow.asStateFlow()
        
        // Add comprehensive logging for network, buffering, and dropped frames for ExoPlayer
        (player as? ExoPlayer)?.addAnalyticsListener(EventLogger(trackSelector))

        viewModelScope.launch {
            repository.observePlayStateMessages().collect { message ->
                message.data?.let { request ->
                    handlePlayStateMessage(request.command, request.seekPositionTicks)
                }
            }
        }
        viewModelScope.launch {
            repository.observeSyncPlayCommandMessages().collect(::handleSyncPlayCommandMessage)
        }
        viewModelScope.launch {
            repository.observeSyncPlayGroupUpdates().collect(::handleSyncPlayGroupUpdate)
        }
        viewModelScope.launch {
            repository.observeSocketState().collectLatest(::handleSocketState)
        }
    }

    private var currentItemKind: String? = null

    fun replacePlayer(newPlayer: Player) {
        Timber.i(
            "Replacing player instance old=%s new=%s currentMediaId=%s",
            player.javaClass.simpleName,
            newPlayer.javaClass.simpleName,
            player.currentMediaItem?.mediaId,
        )
        player.removeListener(this)
        player.release()
        player = newPlayer
        (player as? ExoPlayer)?.addAnalyticsListener(EventLogger(trackSelector))
    }

    fun refreshSyncPlayGroups() {
        viewModelScope.launch {
            _syncPlayState.update { it.copy(isLoading = true, statusMessage = null) }
            runCatching { repository.getSyncPlayGroups() }
                .onSuccess { groups ->
                    _syncPlayState.update {
                        it.copy(
                            isLoading = false,
                            availableGroups = groups,
                            activeGroup = groups.firstOrNull { group -> group.id == activeSyncPlayGroupId } ?: it.activeGroup,
                        )
                    }
                }
                .onFailure { error ->
                    Timber.w(error, "Failed to refresh SyncPlay groups")
                    _syncPlayState.update {
                        it.copy(
                            isLoading = false,
                            statusMessage = error.localizedMessage ?: "Unable to load SyncPlay groups",
                        )
                    }
                }
        }
    }

    fun createSyncPlayGroup() {
        val currentItem = currentPlayerItem()
        if (currentItem?.contentSource != PlayerContentSource.JELLYFIN) {
            _syncPlayState.update {
                it.copy(statusMessage = "SyncPlay is only available for Jellyfin playback")
            }
            return
        }

        val groupName = uiState.value.currentItemTitle.ifBlank { currentItem.name }.take(60)
        viewModelScope.launch {
            _syncPlayState.update { it.copy(isLoading = true, statusMessage = null) }
            runCatching {
                    val group = repository.createSyncPlayGroup(groupName)
                    repository.setSyncPlayQueue(
                        itemIds = listOf(currentItem.itemId),
                        playingItemIndex = 0,
                        startPositionTicks = player.currentPosition.coerceAtLeast(0L) * 10_000L,
                    )
                    group
                }
                .onSuccess { group ->
                    activeSyncPlayGroupId = group.id
                    currentSyncPlaylistItemId = null
                    _syncPlayState.update {
                        it.copy(
                            isLoading = false,
                            activeGroup = group,
                            statusMessage = "Created SyncPlay group: ${group.name}",
                        )
                    }
                    refreshSyncPlayGroups()
                }
                .onFailure { error ->
                    Timber.w(error, "Failed to create SyncPlay group")
                    _syncPlayState.update {
                        it.copy(
                            isLoading = false,
                            statusMessage = error.localizedMessage ?: "Unable to create SyncPlay group",
                        )
                    }
                }
        }
    }

    fun joinSyncPlayGroup(groupId: UUID) {
        val currentItem = currentPlayerItem()
        if (currentItem?.contentSource != PlayerContentSource.JELLYFIN) {
            _syncPlayState.update {
                it.copy(statusMessage = "SyncPlay is only available for Jellyfin playback")
            }
            return
        }

        viewModelScope.launch {
            _syncPlayState.update { it.copy(isLoading = true, statusMessage = null) }
            runCatching {
                    repository.joinSyncPlayGroup(groupId)
                    repository.getSyncPlayGroups().firstOrNull { it.id == groupId }
                }
                .onSuccess { group ->
                    activeSyncPlayGroupId = groupId
                    currentSyncPlaylistItemId = null
                    _syncPlayState.update {
                        it.copy(
                            isLoading = false,
                            activeGroup = group ?: it.activeGroup,
                            statusMessage = "Joined SyncPlay group",
                        )
                    }
                    refreshSyncPlayGroups()
                }
                .onFailure { error ->
                    Timber.w(error, "Failed to join SyncPlay group")
                    _syncPlayState.update {
                        it.copy(
                            isLoading = false,
                            statusMessage = error.localizedMessage ?: "Unable to join SyncPlay group",
                        )
                    }
                }
        }
    }

    fun leaveSyncPlayGroup() {
        viewModelScope.launch {
            runCatching { repository.leaveSyncPlayGroup() }
                .onFailure { Timber.w(it, "Failed to leave SyncPlay group") }
            activeSyncPlayGroupId = null
            currentSyncPlaylistItemId = null
            _syncPlayState.update {
                it.copy(activeGroup = null, statusMessage = "Left SyncPlay group")
            }
            refreshSyncPlayGroups()
        }
    }

    fun initializePlayer(
        itemId: UUID,
        itemKind: String,
        startFromBeginning: Boolean,
        mediaSourceIndex: Int? = null,
        maxBitrate: Long? = null,
        autoPlay: Boolean = true,
    ) {
        currentItemKind = itemKind
        player.removeListener(this)
        player.addListener(this)

        viewModelScope.launch {
            val startItem =
                try {
                    playlistManager.getInitialItem(
                        itemId = itemId,
                        itemKind = BaseItemKind.fromName(itemKind),
                        mediaSourceIndex = mediaSourceIndex,
                        maxBitrate = maxBitrate,
                        startFromBeginning = startFromBeginning,
                    )
                } catch (e: Exception) {
                    Timber.e(e)
                    Toast.makeText(application, e.localizedMessage, Toast.LENGTH_LONG).show()
                    null
                }

            if (startItem == null) {
                Timber.e("No start item, stopping player initialization")
                return@launch
            }

            items = listOfNotNull(startItem).toMutableList()
            currentMediaItemIndex = items.indexOf(startItem)

            val mediaItems = mutableListOf<MediaItem>()
            try {
                for (item in items) {
                    mediaItems.add(item.toMediaItem())
                }
            } catch (e: Exception) {
                Timber.e(e)
            }

            val startPosition =
                if (playbackPosition == 0L) {
                    items.getOrNull(currentMediaItemIndex)?.playbackPosition ?: C.TIME_UNSET
                } else {
                    playbackPosition
                }

            player.setMediaItems(mediaItems, 0, startPosition)
            player.prepare()
            if (autoPlay) {
                player.play()
            } else {
                player.pause()
            }
        }
    }

    fun changeQuality(itemId: UUID, itemKind: String, newMaxBitrate: Long) {
        val currentPosition = player.currentPosition
        playbackPosition = currentPosition
        appPreferences.setValue(appPreferences.playerMaxBitrate, newMaxBitrate)
        initializePlayer(
            itemId = itemId,
            itemKind = itemKind,
            startFromBeginning = false,
            maxBitrate = newMaxBitrate
        )
    }

    fun changeSource(itemId: UUID, itemKind: String, newSourceIndex: Int) {
        val currentPosition = player.currentPosition
        playbackPosition = currentPosition
        initializePlayer(
            itemId = itemId,
            itemKind = itemKind,
            startFromBeginning = false,
            mediaSourceIndex = newSourceIndex
        )
    }

    fun initializeLocalPlayer(localMediaId: Long, startFromBeginning: Boolean) {
        player.removeListener(this)
        player.addListener(this)

        viewModelScope.launch {
            val startItem =
                try {
                    localMediaRepository.getVideo(localMediaId)?.let { item ->
                        PlayerItem(
                            name = item.name,
                            itemId = item.id,
                            mediaSourceId = "local-$localMediaId",
                            playbackPosition =
                                if (startFromBeginning) 0L else item.playbackPositionTicks / 10000L,
                            mediaSourceUri = item.contentUri.toString(),
                            chapters = emptyList(),
                            people = emptyList(),
                            overview = item.overview,
                            backdropImageUri = null,
                            seriesName = null,
                            contentSource = PlayerContentSource.LOCAL,
                            localMediaId = localMediaId,
                        )
                    }
                } catch (e: Exception) {
                    Timber.e(e)
                    Toast.makeText(application, e.localizedMessage, Toast.LENGTH_LONG).show()
                    null
                }

            if (startItem == null) {
                Timber.e("No local start item, stopping player initialization")
                return@launch
            }

            items = mutableListOf(startItem)
            currentMediaItemIndex = 0

            val startPosition =
                if (playbackPosition == 0L) {
                    startItem.playbackPosition.takeIf { !startFromBeginning } ?: C.TIME_UNSET
                } else {
                    playbackPosition
                }

            player.setMediaItems(listOf(startItem.toMediaItem()), 0, startPosition)
            player.prepare()
            player.play()
        }
    }

    private fun PlayerItem.toMediaItem(): MediaItem {
        val streamUrl = mediaSourceUri
        val mediaSubtitles =
            externalSubtitles.map { externalSubtitle ->
                MediaItem.SubtitleConfiguration.Builder(externalSubtitle.uri)
                    .setLabel(
                        externalSubtitle.title.ifBlank { application.getString(R.string.external) }
                    )
                    .setMimeType(externalSubtitle.mimeType)
                    .setLanguage(externalSubtitle.language)
                    .build()
            }

        Timber.d("Stream url: $streamUrl")
        val mediaItem =
            MediaItem.Builder()
                .setMediaId(itemId.toString())
                .setUri(streamUrl)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(name).build())
                .setSubtitleConfigurations(mediaSubtitles)
                .build()

        return mediaItem
    }

    private fun releasePlayer() {
        val mediaId = player.currentMediaItem?.mediaId
        val position = player.currentPosition
        val duration = player.duration
        val currentItem = currentPlayerItem()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                if (currentItem != null && duration != C.TIME_UNSET) {
                    when (currentItem.contentSource) {
                        PlayerContentSource.JELLYFIN -> {
                            if (mediaId != null) {
                                Timber.d("Sending playback stop")
                                repository.postPlaybackStop(
                                    UUID.fromString(mediaId),
                                    position.times(10000),
                                    position.div(duration.toFloat()).times(100).toInt(),
                                )
                            }
                        }
                        PlayerContentSource.LOCAL -> {
                            currentItem.localMediaId?.let { localMediaId ->
                                localMediaRepository.updatePlaybackState(
                                    mediaStoreId = localMediaId,
                                    positionMs = position,
                                    durationMs = duration,
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e)
            }
        }

        _uiState.update { it.copy(currentTrickplay = null) }
        playWhenReady = false
        playbackPosition = 0L
        currentMediaItemIndex = 0
        player.removeListener(this)
        player.release()
    }

    fun updatePlaybackProgress() {
        Timber.d("Updating playback progress")
        viewModelScope.launch(Dispatchers.Main) {
            savedStateHandle["position"] = player.currentPosition
            currentPlayerItem()?.let { currentItem ->
                try {
                    when (currentItem.contentSource) {
                        PlayerContentSource.JELLYFIN -> {
                            val itemId = UUID.fromString(player.currentMediaItem!!.mediaId)
                            repository.postPlaybackProgress(
                                itemId,
                                player.currentPosition.times(10000),
                                !player.isPlaying,
                            )
                        }
                        PlayerContentSource.LOCAL -> {
                            currentItem.localMediaId?.let { localMediaId ->
                                localMediaRepository.updatePlaybackState(
                                    mediaStoreId = localMediaId,
                                    positionMs = player.currentPosition,
                                    durationMs = player.duration.coerceAtLeast(0L),
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e)
                }
            }
        }
    }

    fun updateCurrentSegment() {
        viewModelScope.launch(Dispatchers.Main) {
            if (currentMediaItemSegments.isEmpty()) {
                return@launch
            }

            val milliSeconds = player.currentPosition

            // Get current segment, - 100 milliseconds to avoid showing button after segment ends
            val currentSegment =
                currentMediaItemSegments.find { segment ->
                    milliSeconds in segment.startTicks..<(segment.endTicks - 100L)
                }

            if (currentSegment == null) {
                // Remove button if not pressed and there is no current segment
                if (_uiState.value.currentSegment != null) {
                    _uiState.update { it.copy(currentSegment = null) }
                }
                return@launch
            }

            Timber.tag("SegmentInfo").d("currentSegment: %s", currentSegment)

            if (
                segmentsAutoSkip &&
                    segmentsAutoSkipTypes.contains(currentSegment.type.toString()) &&
                    segmentsAutoSkipMode == Constants.PlayerMediaSegmentsAutoSkip.ALWAYS
            ) {
                // Auto Skip segment
                skipSegment(currentSegment)
            } else if (segmentsSkipButtonTypes.contains(currentSegment.type.toString())) {
                // Skip Button segment
                _uiState.update {
                    it.copy(
                        currentSegment = currentSegment,
                        currentSkipButtonStringRes = getSkipButtonTextStringId(currentSegment),
                    )
                }
            } else {
                _uiState.update { it.copy(currentSegment = null) }
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        Timber.d("Playing MediaItem: ${mediaItem?.mediaId}")
        savedStateHandle["mediaItemIndex"] = player.currentMediaItemIndex
        viewModelScope.launch {
            try {
                items
                    .first { it.itemId.toString() == player.currentMediaItem?.mediaId }
                    .let { item ->
                        val itemTitle =
                            if (item.parentIndexNumber != null && item.indexNumber != null) {
                                if (item.indexNumberEnd == null) {
                                    "S${item.parentIndexNumber}:E${item.indexNumber} - ${item.name}"
                                } else {
                                    "S${item.parentIndexNumber}:E${item.indexNumber}-${item.indexNumberEnd} - ${item.name}"
                                }
                            } else {
                                item.name
                            }
                        _uiState.update {
                            it.copy(
                                currentItemTitle = itemTitle,
                                currentItemId = item.itemId.toString(),
                                currentItemKind = currentItemKind,
                                currentSegment = null,
                                currentChapters = item.chapters,
                                currentPeople = item.people,
                                currentOverview = item.overview,
                                currentGenres = item.genres,
                                currentSeriesName = item.seriesName,
                                currentSeasonNumber = item.parentIndexNumber,
                                currentEpisodeNumber = item.indexNumber,
                                currentProductionYear = item.productionYear,
                                storySoFarContext = playlistManager.getStorySoFarContext(),
                                fileLoaded = false,
                            )
                        }

                        if (item.contentSource == PlayerContentSource.JELLYFIN) {
                            repository.postPlaybackStart(item.itemId)

                            if (segmentsSkipButton || segmentsAutoSkip) {
                                getSegments(item.itemId)
                            }

                            if (appPreferences.getValue(appPreferences.playerTrickplay)) {
                                getTrickplay(item)
                            }
                        } else {
                            currentMediaItemSegments = emptyList()
                        }

                        if (item.contentSource == PlayerContentSource.JELLYFIN) {
                            playlistManager.setCurrentMediaItemIndex(item.itemId)
                        }

                        if (isSyncPlayActive()) {
                            currentSyncPlaylistItemId = item.playlistItemId
                            _uiState.update {
                                it.copy(nextEpisode = items.getOrNull(player.currentMediaItemIndex + 1))
                            }
                        } else if (item.contentSource == PlayerContentSource.JELLYFIN) {
                            val previousItem = playlistManager.getPreviousPlayerItem()
                            if (previousItem != null) {
                                items.add(player.currentMediaItemIndex, previousItem)
                                player.addMediaItem(
                                    player.currentMediaItemIndex,
                                    previousItem.toMediaItem(),
                                )
                            }

                            val nextItem = playlistManager.getNextPlayerItem()
                            // Expose the next episode (if any) so the XR next-up panel can display it.
                            _uiState.update { it.copy(nextEpisode = nextItem) }
                            if (nextItem != null) {
                                items.add(player.currentMediaItemIndex + 1, nextItem)
                                player.addMediaItem(
                                    player.currentMediaItemIndex + 1,
                                    nextItem.toMediaItem(),
                                )
                            }
                        } else {
                            _uiState.update { it.copy(nextEpisode = null) }
                        }

                        applyContentTypeTrackPreferences(item.genres)
                        Timber.tag("PlayerItems").d(items.map { it.indexNumber }.toString())
                    }
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    /**
     * Applies audio and subtitle track preferences based on content type (anime vs non-anime).
     * Anime is detected when the item's genre list contains "Anime" (case-insensitive).
     *
     * Called on every media item transition so preferences take effect immediately when
     * the player moves to a new item, overriding ExoPlayer's global track selector defaults.
     */
    private fun applyContentTypeTrackPreferences(genres: List<String>) {
        val isAnime = genres.any { it.equals("anime", ignoreCase = true) }

        val audioLang: String? = if (isAnime) {
            appPreferences.getValue(appPreferences.animeAudioLanguage)
                ?: appPreferences.getValue(appPreferences.preferredAudioLanguage)
        } else {
            appPreferences.getValue(appPreferences.nonAnimeAudioLanguage)
                ?: appPreferences.getValue(appPreferences.preferredAudioLanguage)
        }

        val disableSubtitles: Boolean
        val subtitleLang: String?
        if (isAnime) {
            disableSubtitles = false
            subtitleLang = appPreferences.getValue(appPreferences.animeSubtitleLanguage)
                ?: appPreferences.getValue(appPreferences.preferredSubtitleLanguage)
        } else {
            disableSubtitles = appPreferences.getValue(appPreferences.nonAnimeSubtitleDisabled)
            subtitleLang = if (disableSubtitles) {
                null
            } else {
                appPreferences.getValue(appPreferences.nonAnimeSubtitleLanguage)
                    ?: appPreferences.getValue(appPreferences.preferredSubtitleLanguage)
            }
        }

        val params = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(audioLang)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, disableSubtitles)
            .setPreferredTextLanguage(if (disableSubtitles) null else subtitleLang)
            .build()
        player.trackSelectionParameters = params

        Timber.d(
            "Content-type track prefs: isAnime=%b audio=%s subtitlesDisabled=%b subtitle=%s",
            isAnime, audioLang, disableSubtitles, subtitleLang,
        )
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        // Report playback stopped for current item and transition to the next one
                        if (
            !playWhenReady &&
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM &&
                player.playbackState == ExoPlayer.STATE_READY
        ) {
            viewModelScope.launch {
                val mediaId = player.currentMediaItem?.mediaId
                val position = player.currentPosition
                val duration = player.duration
                try {
                    when (currentPlayerItem()?.contentSource) {
                        PlayerContentSource.JELLYFIN -> {
                            repository.postPlaybackStop(
                                UUID.fromString(mediaId),
                                position.times(10000),
                                position.div(duration.toFloat()).times(100).toInt(),
                            )
                        }
                        PlayerContentSource.LOCAL -> {
                            currentPlayerItem()?.localMediaId?.let { localMediaId ->
                                localMediaRepository.updatePlaybackState(
                                    mediaStoreId = localMediaId,
                                    positionMs = position,
                                    durationMs = duration,
                                )
                            }
                        }
                        null -> Unit
                    }
                } catch (e: Exception) {
                    Timber.e(e)
                }
                if (isSyncPlayActive() && !shouldSuppressSyncEvents()) {
                    val playlistItemId = currentSyncPlaylistItemId
                    if (playlistItemId != null && player.hasNextMediaItem()) {
                        runCatching { repository.nextSyncPlayItem(playlistItemId) }
                            .onFailure { Timber.w(it, "Failed to request next SyncPlay item") }
                    } else {
                        runCatching { repository.stopSyncPlay() }
                            .onFailure { Timber.w(it, "Failed to stop SyncPlay at queue end") }
                    }
                } else if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.play()
                }
            }
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (
            !isSyncPlayActive() ||
                shouldSuppressSyncEvents() ||
                reason != Player.DISCONTINUITY_REASON_SEEK
        ) {
            return
        }

        viewModelScope.launch {
            runCatching { repository.seekSyncPlay(player.currentPosition.coerceAtLeast(0L) * 10_000L) }
                .onFailure { Timber.w(it, "Failed to push SyncPlay seek") }
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
        var stateString = "UNKNOWN_STATE             -"
        when (state) {
            ExoPlayer.STATE_IDLE -> {
                stateString = "ExoPlayer.STATE_IDLE      -"
            }
            ExoPlayer.STATE_BUFFERING -> {
                stateString = "ExoPlayer.STATE_BUFFERING -"
            }
            ExoPlayer.STATE_READY -> {
                stateString = "ExoPlayer.STATE_READY     -"
                _uiState.update { it.copy(fileLoaded = true) }
            }
            ExoPlayer.STATE_ENDED -> {
                stateString = "ExoPlayer.STATE_ENDED     -"
                eventsChannel.trySend(PlayerEvents.NavigateBack)
            }
        }
        Timber.d(
            "Changed player state to %s playWhenReady=%b isPlaying=%b posMs=%d mediaId=%s error=%s",
            stateString,
            player.playWhenReady,
            player.isPlaying,
            player.currentPosition,
            player.currentMediaItem?.mediaId,
            player.playerError?.errorCodeName,
        )
    }

    override fun onPlayerError(error: PlaybackException) {
        Timber.e(
            error,
            "Player error code=%s name=%s mediaId=%s posMs=%d",
            error.errorCode,
            error.errorCodeName,
            player.currentMediaItem?.mediaId,
            player.currentPosition,
        )
    }

    override fun onTracksChanged(tracks: Tracks) {
        val summary = tracks.groups.joinToString(separator = " | ") { group ->
            val formats = (0 until group.length).joinToString { index ->
                val format = group.getTrackFormat(index)
                "${format.sampleMimeType ?: "unknown"}/${format.language ?: "und"}"
            }
            "type=${group.type} selected=${group.isSelected} supported=${group.isSupported} tracks=[$formats]"
        }
        Timber.i("Player tracks changed: %s", summary.ifBlank { "<none>" })
    }

    override fun onCleared() {
        super.onCleared()
        Timber.d("Clearing Player ViewModel")
        if (isSyncPlayActive()) {
            viewModelScope.launch { runCatching { repository.leaveSyncPlayGroup() } }
        }
        releasePlayer()
    }

    fun switchToTrack(trackType: @C.TrackType Int, index: Int) {
        // Index -1 equals disable track
        if (index == -1) {
            player.trackSelectionParameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(trackType)
                    .setTrackTypeDisabled(trackType, true)
                    .build()
        } else {
            player.trackSelectionParameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(
                        TrackSelectionOverride(
                            player.currentTracks.groups
                                .filter { it.type == trackType && it.isSupported }[index]
                                .mediaTrackGroup,
                            0,
                        )
                    )
                    .setTrackTypeDisabled(trackType, false)
                    .apply {
                        if (trackType == C.TRACK_TYPE_TEXT) {
                            setIgnoredTextSelectionFlags(0)
                        }
                    }
                    .build()
        }
    }

    private fun isSyncPlayActive(): Boolean = activeSyncPlayGroupId != null

    private fun shouldSuppressSyncEvents(): Boolean = SystemClock.elapsedRealtime() < suppressSyncUntilMs

    private inline fun applyRemoteSync(action: () -> Unit) {
        suppressSyncUntilMs = SystemClock.elapsedRealtime() + 1_500L
        action()
    }

    private suspend fun handleSyncPlayCommandMessage(message: SyncPlayCommandMessage) {
        val command = message.data ?: return
        if (command.groupId != activeSyncPlayGroupId) return

        when (command.command) {
            org.jellyfin.sdk.model.api.SendCommandType.PAUSE -> {
                applyRemoteSync { player.pause() }
            }
            org.jellyfin.sdk.model.api.SendCommandType.UNPAUSE -> {
                applyRemoteSync { player.play() }
            }
            org.jellyfin.sdk.model.api.SendCommandType.SEEK -> {
                command.positionTicks?.let { seekToRemotePosition(it) }
            }
            org.jellyfin.sdk.model.api.SendCommandType.STOP -> {
                applyRemoteSync {
                    player.pause()
                    player.seekTo(0)
                }
            }
        }
    }

    private suspend fun handleSyncPlayGroupUpdate(message: SyncPlayGroupUpdateMessage) {
        val update = message.data
        when (update) {
            is SyncPlayGroupJoinedUpdate -> {
                if (activeSyncPlayGroupId == update.groupId) {
                    _syncPlayState.update { it.copy(activeGroup = update.data.toSyncPlayGroup()) }
                }
            }
            is SyncPlayPlayQueueUpdate -> {
                if (activeSyncPlayGroupId != update.groupId) return
                applySyncPlayQueueUpdate(
                    queue = update.data.playlist,
                    playingItemIndex = update.data.playingItemIndex,
                    startPositionTicks = update.data.startPositionTicks,
                    shouldPlay = update.data.isPlaying,
                )
            }
            is SyncPlayStateUpdate -> {
                if (activeSyncPlayGroupId != update.groupId) return
                when (update.data.state) {
                    GroupStateType.PLAYING -> applyRemoteSync { player.play() }
                    GroupStateType.PAUSED,
                    GroupStateType.WAITING -> applyRemoteSync { player.pause() }
                    GroupStateType.IDLE -> Unit
                }
            }
            is SyncPlayGroupLeftUpdate,
            is SyncPlayNotInGroupUpdate,
            is SyncPlayGroupDoesNotExistUpdate -> {
                if (activeSyncPlayGroupId == update.groupId) {
                    activeSyncPlayGroupId = null
                    currentSyncPlaylistItemId = null
                    _syncPlayState.update {
                        it.copy(activeGroup = null, statusMessage = "SyncPlay group ended")
                    }
                    refreshSyncPlayGroups()
                }
            }
            else -> Unit
        }
    }

    private fun handlePlayStateMessage(command: PlaystateCommand, seekPositionTicks: Long?) {
        when (command) {
            PlaystateCommand.PAUSE -> applyRemoteSync { player.pause() }
            PlaystateCommand.UNPAUSE,
            PlaystateCommand.PLAY_PAUSE -> {
                applyRemoteSync {
                    if (player.isPlaying) player.pause() else player.play()
                }
            }
            PlaystateCommand.SEEK -> {
                seekPositionTicks?.let { ticks ->
                    viewModelScope.launch { seekToRemotePosition(ticks) }
                }
            }
            PlaystateCommand.STOP -> {
                applyRemoteSync {
                    player.pause()
                    player.seekTo(0)
                }
            }
            else -> Unit
        }
    }

    private suspend fun seekToRemotePosition(positionTicks: Long) {
        val targetMs = positionTicks / 10_000L
        applyRemoteSync { player.seekTo(targetMs.coerceAtLeast(0L)) }
    }

    private suspend fun handleSocketState(state: SocketApiState) {
        when (state) {
            is SocketApiState.Connected -> {
                val reconnected = !isSocketConnected
                isSocketConnected = true
                if (reconnected && isSyncPlayActive()) {
                    _syncPlayState.update { it.copy(statusMessage = "SyncPlay reconnected") }
                    refreshSyncPlayGroups()
                }
            }
            is SocketApiState.Connecting -> {
                if (isSyncPlayActive()) {
                    _syncPlayState.update { it.copy(statusMessage = "Reconnecting SyncPlay...") }
                }
            }
            is SocketApiState.Disconnected -> {
                isSocketConnected = false
                if (isSyncPlayActive()) {
                    _syncPlayState.update { it.copy(statusMessage = "SyncPlay connection lost") }
                }
            }
        }
    }

    private suspend fun switchToSyncPlayItem(
        itemId: UUID,
        positionTicks: Long,
        shouldPlay: Boolean,
    ) {
        val item = runCatching { repository.getItem(itemId) }
            .onFailure { Timber.w(it, "Failed to resolve SyncPlay item %s", itemId) }
            .getOrNull()

        val itemKind = item?.toPlayerItemKind()
        if (itemKind == null) {
            _syncPlayState.update {
                it.copy(statusMessage = "SyncPlay tried to start an unsupported item")
            }
            return
        }

        playbackPosition = positionTicks / 10_000L
        applyRemoteSync {
            initializePlayer(
                itemId = itemId,
                itemKind = itemKind,
                startFromBeginning = false,
                autoPlay = shouldPlay,
            )
        }
        _syncPlayState.update {
            it.copy(statusMessage = "Synced to ${item.name}")
        }
    }

    private suspend fun applySyncPlayQueueUpdate(
        queue: List<SyncPlayQueueItem>,
        playingItemIndex: Int,
        startPositionTicks: Long,
        shouldPlay: Boolean,
    ) {
        val targetQueueItem = queue.getOrNull(playingItemIndex) ?: return
        val resolvedItems =
            queue.mapNotNull { queueItem ->
                playlistManager.getPlayerItem(
                    itemId = queueItem.itemId,
                    playbackPosition = 0L,
                    playlistItemId = queueItem.playlistItemId,
                )
            }

        if (resolvedItems.isEmpty()) return

        val targetIndex =
            resolvedItems.indexOfFirst { it.playlistItemId == targetQueueItem.playlistItemId }
                .takeIf { it >= 0 } ?: return

        items = resolvedItems.toMutableList()
        currentSyncPlaylistItemId = targetQueueItem.playlistItemId
        _uiState.update { it.copy(nextEpisode = items.getOrNull(targetIndex + 1)) }

        val targetPositionMs = startPositionTicks / 10_000L
        val queueMatches =
            player.mediaItemCount == resolvedItems.size &&
                resolvedItems.indices.all { index ->
                    player.getMediaItemAt(index).mediaId == resolvedItems[index].itemId.toString()
                }

        if (!queueMatches) {
            applyRemoteSync {
                player.setMediaItems(
                    resolvedItems.map { it.toMediaItem() },
                    targetIndex,
                    targetPositionMs,
                )
                player.prepare()
                if (shouldPlay) player.play() else player.pause()
            }
            return
        }

        applyRemoteSync {
            if (player.currentMediaItemIndex != targetIndex) {
                player.seekTo(targetIndex, targetPositionMs)
            } else if (kotlin.math.abs(player.currentPosition - targetPositionMs) > 1_500L) {
                player.seekTo(targetPositionMs)
            }

            if (shouldPlay) {
                player.play()
            } else {
                player.pause()
            }
        }
    }

    fun skipToNextItem() {
        viewModelScope.launch {
            if (isSyncPlayActive() && !shouldSuppressSyncEvents()) {
                currentSyncPlaylistItemId?.let { playlistItemId ->
                    runCatching { repository.nextSyncPlayItem(playlistItemId) }
                        .onFailure { Timber.w(it, "Failed to request next SyncPlay item") }
                }
            } else if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.play()
            }
        }
    }

    fun skipToPreviousItem() {
        viewModelScope.launch {
            if (isSyncPlayActive() && !shouldSuppressSyncEvents()) {
                currentSyncPlaylistItemId?.let { playlistItemId ->
                    runCatching { repository.previousSyncPlayItem(playlistItemId) }
                        .onFailure { Timber.w(it, "Failed to request previous SyncPlay item") }
                }
            } else if (player.hasPreviousMediaItem()) {
                player.seekToPreviousMediaItem()
                player.play()
            }
        }
    }

    private fun SpatialFinItem.toPlayerItemKind(): String? =
        when (this) {
            is SpatialFinMovie -> BaseItemKind.MOVIE.serialName
            is SpatialFinEpisode -> BaseItemKind.EPISODE.serialName
            is SpatialFinSeason -> BaseItemKind.SEASON.serialName
            is SpatialFinShow -> BaseItemKind.SERIES.serialName
            else -> null
        }

    fun selectSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        playbackSpeed = speed
    }

    private suspend fun getSegments(itemId: UUID) {
        try {
            currentMediaItemSegments = repository.getSegments(itemId)
        } catch (e: Exception) {
            currentMediaItemSegments = emptyList()
            Timber.e(e)
        }
    }

    private suspend fun getTrickplay(item: PlayerItem) {
        val trickplayInfo = item.trickplayInfo ?: return
        Timber.d("Trickplay Resolution: ${trickplayInfo.width}")

        withContext(Dispatchers.Default) {
            val maxIndex =
                ceil(
                        trickplayInfo.thumbnailCount
                            .toDouble()
                            .div(trickplayInfo.tileWidth * trickplayInfo.tileHeight)
                    )
                    .toInt()
            val bitmaps = mutableListOf<Bitmap>()

            for (i in 0..maxIndex) {
                repository.getTrickplayData(item.itemId, trickplayInfo.width, i)?.let { byteArray ->
                    val fullBitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                    for (offsetY in
                        0..<trickplayInfo.height * trickplayInfo.tileHeight step
                            trickplayInfo.height) {
                        for (offsetX in
                            0..<trickplayInfo.width * trickplayInfo.tileWidth step
                                trickplayInfo.width) {
                            val bitmap =
                                Bitmap.createBitmap(
                                    fullBitmap,
                                    offsetX,
                                    offsetY,
                                    trickplayInfo.width,
                                    trickplayInfo.height,
                                )
                            bitmaps.add(bitmap)
                        }
                    }
                }
            }

            _uiState.update {
                it.copy(currentTrickplay = Trickplay(trickplayInfo.interval, bitmaps))
            }
        }
    }

    private fun currentPlayerItem(): PlayerItem? {
        return items.firstOrNull { it.itemId.toString() == player.currentMediaItem?.mediaId }
    }

    fun skipSegment(segment: SpatialFinSegment) {
        if (shouldSkipToNextEpisode(segment)) {
            player.seekToNextMediaItem()
        } else {
            player.seekTo(segment.endTicks)
        }
        _uiState.update { it.copy(currentSegment = null) }
    }

    // Check if the outro segment's end time is within n milliseconds of the player's total duration
    private fun shouldSkipToNextEpisode(segment: SpatialFinSegment): Boolean {
        return if (segment.type == SpatialFinSegmentType.OUTRO && player.hasNextMediaItem()) {
            val segmentEndTimeMillis = segment.endTicks
            val playerDurationMillis = player.duration
            val thresholdMillis =
                playerDurationMillis -
                    appPreferences.getValue(appPreferences.playerMediaSegmentsNextEpisodeThreshold)

            segmentEndTimeMillis > thresholdMillis
        } else {
            false
        }
    }

    private fun getSkipButtonTextStringId(segment: SpatialFinSegment): Int {
        return when (shouldSkipToNextEpisode(segment)) {
            true -> R.string.player_controls_next_episode
            false ->
                when (segment.type) {
                    SpatialFinSegmentType.INTRO -> R.string.player_controls_skip_intro
                    SpatialFinSegmentType.OUTRO -> R.string.player_controls_skip_outro
                    SpatialFinSegmentType.RECAP -> R.string.player_controls_skip_recap
                    SpatialFinSegmentType.COMMERCIAL -> R.string.player_controls_skip_commercial
                    SpatialFinSegmentType.PREVIEW -> R.string.player_controls_skip_preview
                    else -> R.string.player_controls_skip_unknown
                }
        }
    }

    /**
     * Get chapters of current item
     *
     * @return list of [PlayerChapter]
     */
    private fun getChapters(): List<PlayerChapter> {
        return uiState.value.currentChapters
    }

    /**
     * Get the index of the current chapter
     *
     * @return the index of the current chapter
     */
    private fun getCurrentChapterIndex(): Int? {
        val chapters = getChapters()

        for (i in chapters.indices.reversed()) {
            if (chapters[i].startPosition < player.currentPosition) {
                return i
            }
        }

        return null
    }

    /**
     * Get the index of the next chapter
     *
     * @return the index of the next chapter
     */
    private fun getNextChapterIndex(): Int? {
        val chapters = getChapters()
        val currentChapterIndex = getCurrentChapterIndex() ?: return null

        return minOf(chapters.size - 1, currentChapterIndex + 1)
    }

    /**
     * Get the index of the previous chapter. Only use this for seeking as it will return the
     * current chapter when player position is more than 5 seconds past the start of the chapter
     *
     * @return the index of the previous chapter
     */
    private fun getPreviousChapterIndex(): Int? {
        val chapters = getChapters()
        val currentChapterIndex = getCurrentChapterIndex() ?: return null

        // Return current chapter when more than 5 seconds past chapter start
        if (player.currentPosition > chapters[currentChapterIndex].startPosition + 5000L) {
            return currentChapterIndex
        }

        return maxOf(0, currentChapterIndex - 1)
    }

    fun isLastChapter(): Boolean =
        getChapters().let { chapters -> getCurrentChapterIndex() == chapters.size - 1 }

    /**
     * Seek to chapter
     *
     * @param [chapterIndex] the index of the chapter to seek to
     * @return the [PlayerChapter] which has been sought to
     */
    private fun seekToChapter(chapterIndex: Int): PlayerChapter? {
        return getChapters().getOrNull(chapterIndex)?.also { chapter ->
            player.seekTo(chapter.startPosition)
        }
    }

    /**
     * Seek to the next chapter
     *
     * @return the [PlayerChapter] which has been sought to
     */
    fun seekToNextChapter(): PlayerChapter? {
        return getNextChapterIndex()?.let { seekToChapter(it) }
    }

    /**
     * Seek to the previous chapter Will seek to start of current chapter if player position is more
     * than 5 seconds past start of chapter
     *
     * @return the [PlayerChapter] which has been sought to
     */
    fun seekToPreviousChapter(): PlayerChapter? {
        return getPreviousChapterIndex()?.let { seekToChapter(it) }
    }

    fun seekToChapterIndex(chapterIndex: Int): PlayerChapter? {
        return seekToChapter(chapterIndex)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        super.onIsPlayingChanged(isPlaying)
        if (isSyncPlayActive() && !shouldSuppressSyncEvents() && player.playbackState == Player.STATE_READY) {
            viewModelScope.launch {
                runCatching {
                        if (isPlaying) {
                            repository.unpauseSyncPlay()
                        } else {
                            repository.pauseSyncPlay()
                        }
                    }
                    .onFailure { Timber.w(it, "Failed to push SyncPlay play state") }
            }
        }
        eventsChannel.trySend(PlayerEvents.IsPlayingChanged(isPlaying))
    }
}

sealed interface PlayerEvents {
    data object NavigateBack : PlayerEvents

    data class IsPlayingChanged(val isPlaying: Boolean) : PlayerEvents
}
