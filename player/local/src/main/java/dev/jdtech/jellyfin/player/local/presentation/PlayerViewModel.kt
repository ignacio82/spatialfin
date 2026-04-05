package dev.jdtech.jellyfin.player.local.presentation

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Display
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
import dev.jdtech.jellyfin.models.Rating
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
import dev.jdtech.jellyfin.repository.NetworkMediaRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.Constants
import dev.jdtech.jellyfin.settings.language.LanguageCatalog
import dev.jdtech.jellyfin.settings.language.SeriesLanguageOverride
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
    val repository: JellyfinRepository,
    private val localMediaRepository: LocalMediaRepository,
    private val networkMediaRepository: NetworkMediaRepository,
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
                currentMediaSourceId = null,
                currentSegment = null,
                currentSkipButtonStringRes = R.string.player_controls_skip_intro,
                currentTrickplay = null,
                currentChapters = emptyList(),
                currentPeople = emptyList(),
                currentOverview = "",
                currentGenres = emptyList(),
                currentRatings = emptyList(),
                currentSeriesName = null,
                currentSeasonNumber = null,
                currentEpisodeNumber = null,
                currentProductionYear = null,
                nextEpisode = null,
                fileLoaded = false,
            )
        )
    val uiState = _uiState.asStateFlow()

    sealed interface SubtitleSearchState {
        data object Idle : SubtitleSearchState
        data object Searching : SubtitleSearchState
        data object Downloading : SubtitleSearchState
        data class Success(val options: List<org.jellyfin.sdk.model.api.RemoteSubtitleInfo>) : SubtitleSearchState
        data class Error(val message: String?, val ex: Exception?) : SubtitleSearchState
    }

    private val _subtitleSearchState = MutableStateFlow<SubtitleSearchState>(SubtitleSearchState.Idle)
    val subtitleSearchState = _subtitleSearchState.asStateFlow()

    data class SyncPlayUiState(
        val isLoading: Boolean = false,
        val activeGroup: SyncPlayGroup? = null,
        val availableGroups: List<SyncPlayGroup> = emptyList(),
        val statusMessage: String? = null,
    )

    private val _syncPlayState = MutableStateFlow(SyncPlayUiState())
    val syncPlayState = _syncPlayState.asStateFlow()
    private var lastAutoLanguageSelectionMediaId: String? = null

    private val eventsChannel = Channel<PlayerEvents>()
    val eventsChannelFlow = eventsChannel.receiveAsFlow()

    private val _currentFrameRate = MutableStateFlow(-1f)
    val currentFrameRate = _currentFrameRate.asStateFlow()

    data class UiState(
        val currentItemTitle: String,
        val currentItemId: String? = null,
        val currentItemKind: String? = null,
        val currentMediaSourceId: String? = null,
        val currentSegment: SpatialFinSegment?,        val currentSkipButtonStringRes: Int,
        val currentTrickplay: Trickplay?,
        val currentChapters: List<PlayerChapter>,
        val currentPeople: List<PlayerPerson>,
        val currentOverview: String,
        val currentGenres: List<String> = emptyList(),
        val currentRatings: List<Rating> = emptyList(),
        val currentSeriesName: String? = null,
        val currentSeasonNumber: Int? = null,
        val currentEpisodeNumber: Int? = null,
        val currentProductionYear: Int? = null,
        val currentOfficialRating: String? = null,
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
    private var pendingAutoAdvanceMediaId: String? = null

    init {
        // Log HDR capabilities
        val display = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val displayManager = application.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        } else {
            @Suppress("DEPRECATION")
            (application.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
        }
        
        display?.hdrCapabilities?.let { caps ->
            val types = caps.supportedHdrTypes.joinToString { type ->
                when (type) {
                    android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                    android.view.Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                    android.view.Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                    android.view.Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                    else -> "Unknown ($type)"
                }
            }
            Timber.i("HDR: Supported types: [%s]", types)
            Timber.i("HDR: Max Luminance: %.2f, Min Luminance: %.2f", caps.desiredMaxLuminance, caps.desiredMinLuminance)
        } ?: Timber.w("HDR: No HDR capabilities found for default display")

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
                    appPreferences.getSmartSpokenLanguageCodes(application).firstOrNull()
                )
                .setPreferredTextLanguage(null)
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
        Timber.i(
            "initializePlayer itemId=%s kind=%s startFromBeginning=%b mediaSourceIndex=%s maxBitrate=%s autoPlay=%b currentPlayer=%s",
            itemId,
            itemKind,
            startFromBeginning,
            mediaSourceIndex,
            maxBitrate,
            autoPlay,
            player.javaClass.simpleName,
        )

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

            Timber.i(
                "initializePlayer startItem id=%s mediaSourceId=%s uri=%s source=%s playbackPosition=%d subtitleCount=%d",
                startItem.itemId,
                startItem.mediaSourceId,
                startItem.mediaSourceUri,
                startItem.contentSource,
                startItem.playbackPosition,
                startItem.externalSubtitles.size,
            )

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
            Timber.i(
                "initializePlayer prepared mediaItems=%d startPosition=%d currentIndex=%d",
                mediaItems.size,
                startPosition,
                currentMediaItemIndex,
            )
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
        Timber.i(
            "initializeLocalPlayer mediaStoreId=%d startFromBeginning=%b currentPlayer=%s",
            localMediaId,
            startFromBeginning,
            player.javaClass.simpleName,
        )

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

            Timber.i(
                "initializeLocalPlayer startItem id=%s uri=%s playbackPosition=%d",
                startItem.itemId,
                startItem.mediaSourceUri,
                startItem.playbackPosition,
            )

            items = mutableListOf(startItem)
            currentMediaItemIndex = 0

            val startPosition =
                if (playbackPosition == 0L) {
                    startItem.playbackPosition.takeIf { !startFromBeginning } ?: C.TIME_UNSET
                } else {
                    playbackPosition
                }

            player.setMediaItems(listOf(startItem.toMediaItem()), 0, startPosition)
            Timber.i("initializeLocalPlayer prepared startPosition=%d", startPosition)
            player.prepare()
            player.play()
        }
    }

    fun initializeNetworkPlayer(networkVideoId: String, startFromBeginning: Boolean) {
        player.removeListener(this)
        player.addListener(this)
        Timber.i(
            "initializeNetworkPlayer networkVideoId=%s startFromBeginning=%b currentPlayer=%s",
            networkVideoId,
            startFromBeginning,
            player.javaClass.simpleName,
        )

        viewModelScope.launch {
            val startItem =
                try {
                    networkMediaRepository.getVideo(networkVideoId)?.let { item ->
                        val streamUrl = networkMediaRepository.getStreamUrl(networkVideoId) ?: ""
                        PlayerItem(
                            name = item.name,
                            itemId = item.id,
                            mediaSourceId = "network-$networkVideoId",
                            playbackPosition =
                                if (startFromBeginning) 0L else item.playbackPositionTicks / 10000L,
                            mediaSourceUri = streamUrl,
                            chapters = emptyList(),
                            people = emptyList(),
                            overview = item.overview,
                            backdropImageUri = item.images.backdrop?.toString(),
                            seriesName = null,
                            contentSource = PlayerContentSource.NETWORK,
                            networkVideoId = networkVideoId,
                        )
                    }
                } catch (e: Exception) {
                    Timber.e(e)
                    Toast.makeText(application, e.localizedMessage, Toast.LENGTH_LONG).show()
                    null
                }

            if (startItem == null) {
                Timber.e("No network start item, stopping player initialization")
                return@launch
            }

            Timber.i(
                "initializeNetworkPlayer startItem id=%s uri=%s playbackPosition=%d",
                startItem.itemId,
                startItem.mediaSourceUri,
                startItem.playbackPosition,
            )

            items = mutableListOf(startItem)
            currentMediaItemIndex = 0

            val startPosition =
                if (playbackPosition == 0L) {
                    startItem.playbackPosition.takeIf { !startFromBeginning } ?: C.TIME_UNSET
                } else {
                    playbackPosition
                }

            val mediaItem = startItem.toMediaItem()
            player.setMediaItem(mediaItem, startPosition)

            Timber.i("initializeNetworkPlayer prepared startPosition=%d", startPosition)
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

        Timber.i(
            "Creating media item id=%s source=%s uri=%s subtitles=%d",
            itemId,
            contentSource,
            streamUrl,
            mediaSubtitles.size,
        )
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
                        PlayerContentSource.NETWORK -> {
                            currentItem.networkVideoId?.let { videoId ->
                                networkMediaRepository.updatePlaybackState(
                                    videoId = videoId,
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
                        PlayerContentSource.NETWORK -> {
                            currentItem.networkVideoId?.let { videoId ->
                                networkMediaRepository.updatePlaybackState(
                                    videoId = videoId,
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

    fun skipActiveSegmentForVoice(vararg preferredNames: String): SpatialFinSegment? {
        val currentPositionMs = player.currentPosition
        val activeSegment = currentMediaItemSegments.firstOrNull { segment ->
            currentPositionMs in segment.startTicks..<(segment.endTicks - 100L)
        }
        if (activeSegment == null) {
            Timber.i(
                "VOICE: no active segment at posMs=%d availableSegments=%s",
                currentPositionMs,
                currentMediaItemSegments.joinToString { "${it.type}:${it.startTicks}-${it.endTicks}" },
            )
            return null
        }

        val normalizedRequestedNames = preferredNames.map { it.lowercase() }
        val matchesRequestedType =
            normalizedRequestedNames.isEmpty() ||
                normalizedRequestedNames.any { preferredName ->
                    activeSegment.type.matchesVoiceSegmentName(preferredName)
                }

        if (!matchesRequestedType) {
            Timber.i(
                "VOICE: active segment type=%s did not match requested=%s posMs=%d",
                activeSegment.type,
                normalizedRequestedNames,
                currentPositionMs,
            )
            return null
        }

        Timber.i(
            "VOICE: skipping segment type=%s rangeMs=%d-%d requested=%s",
            activeSegment.type,
            activeSegment.startTicks,
            activeSegment.endTicks,
            normalizedRequestedNames,
        )
        skipSegment(activeSegment)
        return activeSegment
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        Timber.d("Playing MediaItem: ${mediaItem?.mediaId}")
        pendingAutoAdvanceMediaId = null
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
                                currentMediaSourceId = item.mediaSourceId,
                                currentSegment = null,
                                currentChapters = item.chapters,
                                currentPeople = item.people,
                                currentOverview = item.overview,
                                currentGenres = item.genres,
                                currentRatings = item.ratings,
                                currentSeriesName = item.seriesName,
                                currentSeasonNumber = item.parentIndexNumber,
                                currentEpisodeNumber = item.indexNumber,
                                currentProductionYear = item.productionYear,
                                currentOfficialRating = item.officialRating,
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
                            val currentQueueIndex =
                                items.indexOfFirst { queuedItem -> queuedItem.itemId == item.itemId }
                                    .takeIf { it >= 0 } ?: player.currentMediaItemIndex.coerceAtLeast(0)
                            val currentPlayerIndex = player.currentMediaItemIndex.coerceAtLeast(0)
                            val nextItem = playlistManager.getNextPlayerItem()
                            _uiState.update { it.copy(nextEpisode = nextItem) }
                            if (nextItem != null) {
                                items.add((currentQueueIndex + 1).coerceAtMost(items.size), nextItem)
                                player.addMediaItem(
                                    (currentPlayerIndex + 1).coerceAtMost(player.mediaItemCount),
                                    nextItem.toMediaItem(),
                                )
                            }

                            val previousItem = playlistManager.getPreviousPlayerItem()
                            if (previousItem != null) {
                                items.add(currentQueueIndex.coerceAtMost(items.size), previousItem)
                                player.addMediaItem(
                                    currentPlayerIndex.coerceAtMost(player.mediaItemCount),
                                    previousItem.toMediaItem(),
                                )
                            }

                            Timber.i(
                                "Queue around current item current=%s prevAdded=%b nextAdded=%b playerIndex=%d queue=%s",
                                item.itemId,
                                previousItem != null,
                                nextItem != null,
                                player.currentMediaItemIndex,
                                playerQueueDebugString(),
                            )
                        } else {
                            _uiState.update { it.copy(nextEpisode = null) }
                        }

                        lastAutoLanguageSelectionMediaId = null
                        applySmartLanguagePreferences()
                        Timber.tag("PlayerItems").d(items.map { it.indexNumber }.toString())
                    }
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    private fun applySmartLanguagePreferences() {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        if (lastAutoLanguageSelectionMediaId == mediaId) return

        val spokenLanguages = appPreferences.getSmartSpokenLanguageCodes(application)
        val currentItem = currentPlayerItem() ?: return
        val seriesOverride = currentPlayerItem()?.seriesId?.let {
            appPreferences.getSeriesLanguageOverride(it.toString())
        }
        val audioGroups =
            player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
        if (audioGroups.isEmpty()) return
        val subtitleGroups =
            player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }

        val isAnime =
            currentItem.genres.any { genre -> genre.contains("anime", ignoreCase = true) } ||
                (
                    audioGroups.any { group -> groupMatchesLanguage(group, "jpn") } &&
                        subtitleGroups.any { group ->
                            val mime = group.getTrackFormat(0).sampleMimeType.orEmpty()
                            mime == "text/x-ssa" || mime == androidx.media3.common.MimeTypes.TEXT_SSA
                        }
                )
        val preferredAudioForContent =
            if (isAnime) {
                appPreferences.getValue(appPreferences.animeAudioLanguage)
            } else {
                appPreferences.getValue(appPreferences.nonAnimeAudioLanguage)
                    ?: appPreferences.getValue(appPreferences.preferredAudioLanguage)
            }
        val preferredSubtitleForContent =
            if (isAnime) {
                appPreferences.getValue(appPreferences.animeSubtitleLanguage)
                    ?: appPreferences.getValue(appPreferences.preferredSubtitleLanguage)
            } else {
                appPreferences.getValue(appPreferences.nonAnimeSubtitleLanguage)
                    ?: appPreferences.getValue(appPreferences.preferredSubtitleLanguage)
            }
        val defaultSubtitleDisabled = !isAnime && appPreferences.getValue(appPreferences.nonAnimeSubtitleDisabled)

        val inferredOriginalAudio = inferOriginalAudioLanguage(audioGroups, spokenLanguages)
        val seriesOverrideAudio = seriesOverride?.audioLanguageCode
        val seriesOverrideAudioSignature = seriesOverride?.audioTrackSignature
        val selectedAudioGroup =
            when {
                seriesOverrideAudioSignature != null ->
                    audioGroups.firstOrNull { group ->
                        audioTrackSignature(group) == seriesOverrideAudioSignature
                    } ?: audioGroups.firstOrNull {
                        seriesOverrideAudio != null && groupMatchesLanguage(it, seriesOverrideAudio)
                    } ?: audioGroups.first()
                seriesOverrideAudio != null ->
                    audioGroups.firstOrNull {
                        groupMatchesLanguage(it, seriesOverrideAudio)
                    } ?: audioGroups.first()
                !preferredAudioForContent.isNullOrBlank() ->
                    audioGroups.firstOrNull {
                        groupMatchesLanguage(it, preferredAudioForContent)
                    } ?: audioGroups.first()
                appPreferences.getValue(appPreferences.smartPreferOriginalAudio) &&
                    inferredOriginalAudio != null -> {
                audioGroups.firstOrNull {
                    groupMatchesLanguage(it, inferredOriginalAudio)
                } ?: audioGroups.first()
                }
                else ->
                    spokenLanguages.firstNotNullOfOrNull { preferredCode ->
                        audioGroups.firstOrNull { group ->
                            groupMatchesLanguage(group, preferredCode)
                        }
                    } ?: audioGroups.first()
            }

        val selectedAudioLanguage = groupPrimaryLanguage(selectedAudioGroup)
        val audioUnderstood =
            spokenLanguages.any { preferredCode ->
                selectedAudioLanguage != null &&
                    LanguageCatalog.matches(application, selectedAudioLanguage, preferredCode)
            }

        val seriesOverrideSubtitle = seriesOverride?.subtitleLanguageCode
        val seriesOverrideSubtitleSignature = seriesOverride?.subtitleTrackSignature
        val selectedSubtitleGroup =
            if (seriesOverride?.subtitlesEnabled == false) {
                null
            } else if (seriesOverrideSubtitleSignature != null) {
                subtitleGroups.firstOrNull { group ->
                    subtitleTrackSignature(group) == seriesOverrideSubtitleSignature
                } ?: subtitleGroups
                    .filter { group ->
                        seriesOverrideSubtitle != null &&
                            groupMatchesLanguage(group, seriesOverrideSubtitle)
                    }
                    .maxByOrNull { scoreSubtitleGroup(it, preferredLanguageCode = seriesOverrideSubtitle) }
            } else if (seriesOverrideSubtitle != null) {
                subtitleGroups
                    .filter { group -> groupMatchesLanguage(group, seriesOverrideSubtitle) }
                    .maxByOrNull { scoreSubtitleGroup(it, preferredLanguageCode = seriesOverrideSubtitle) }
            } else if (defaultSubtitleDisabled && audioUnderstood) {
                null
            } else if (!preferredSubtitleForContent.isNullOrBlank()) {
                subtitleGroups
                    .filter { group -> groupMatchesLanguage(group, preferredSubtitleForContent) }
                    .maxByOrNull { scoreSubtitleGroup(it, preferredLanguageCode = preferredSubtitleForContent) }
                    ?.takeIf { !audioUnderstood || isAnime }
            } else if (audioUnderstood) {
                null
            } else {
                spokenLanguages.firstNotNullOfOrNull { preferredCode ->
                    subtitleGroups
                        .filter { group -> groupMatchesLanguage(group, preferredCode) }
                        .maxByOrNull { scoreSubtitleGroup(it, preferredLanguageCode = preferredCode) }
                }
            }

        val updatedParameters =
            player.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setPreferredAudioLanguage(selectedAudioLanguage)
                .setPreferredTextLanguage(groupPrimaryLanguage(selectedSubtitleGroup))
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, selectedSubtitleGroup == null)
                .setOverrideForType(
                    TrackSelectionOverride(selectedAudioGroup.mediaTrackGroup, 0)
                )
                .apply {
                    if (selectedSubtitleGroup != null) {
                        setOverrideForType(
                            TrackSelectionOverride(selectedSubtitleGroup.mediaTrackGroup, 0)
                        )
                        setIgnoredTextSelectionFlags(0)
                    }
                }
                .build()

        player.trackSelectionParameters = updatedParameters
        lastAutoLanguageSelectionMediaId = mediaId

        Timber.d(
            "Smart language track prefs: isAnime=%b preferOriginal=%b contentAudio=%s contentSubtitle=%s seriesOverride=%s inferredOriginal=%s spoken=%s selectedAudio=%s subtitlesDisabled=%b selectedSubtitle=%s subtitleSignature=%s",
            isAnime,
            appPreferences.getValue(appPreferences.smartPreferOriginalAudio),
            preferredAudioForContent,
            preferredSubtitleForContent,
            seriesOverride,
            inferredOriginalAudio,
            spokenLanguages.joinToString(","),
            selectedAudioLanguage,
            selectedSubtitleGroup == null,
            groupPrimaryLanguage(selectedSubtitleGroup),
            selectedSubtitleGroup?.let(::subtitleTrackSignature),
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
                        PlayerContentSource.NETWORK -> {
                            currentPlayerItem()?.networkVideoId?.let { networkVideoId ->
                                networkMediaRepository.updatePlaybackState(
                                    videoId = networkVideoId,
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
                val currentMediaId = player.currentMediaItem?.mediaId
                val nextEpisode = _uiState.value.nextEpisode
                val shouldAutoAdvance =
                    if (isSyncPlayActive()) {
                        currentSyncPlaylistItemId != null && nextEpisode != null
                    } else {
                        player.hasNextMediaItem() || nextEpisode != null
                    }

                if (shouldAutoAdvance && pendingAutoAdvanceMediaId != currentMediaId) {
                    pendingAutoAdvanceMediaId = currentMediaId
                    Timber.i(
                        "Playback ended; auto advancing current=%s hasNextMediaItem=%b nextEpisode=%s queue=%s",
                        currentMediaId,
                        player.hasNextMediaItem(),
                        nextEpisode?.itemId,
                        playerQueueDebugString(),
                    )
                    skipToNextItem()
                } else if (!shouldAutoAdvance) {
                    pendingAutoAdvanceMediaId = null
                    Timber.i(
                        "Playback ended with no next item; navigating back current=%s queue=%s",
                        currentMediaId,
                        playerQueueDebugString(),
                    )
                    eventsChannel.trySend(PlayerEvents.NavigateBack)
                }
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

        // Detect frame rate from the active video track for display refresh rate matching.
        val videoGroup = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
        val detectedFrameRate = videoGroup?.let { group ->
            (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { idx ->
                group.getTrackFormat(idx).frameRate
            }
        } ?: -1f
        if (detectedFrameRate > 0f) {
            Timber.i("frame-rate: detected %.4f fps from active video track", detectedFrameRate)
            _currentFrameRate.value = detectedFrameRate
        }

        applySmartLanguagePreferences()
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
        val groups = player.currentTracks.groups.filter { it.type == trackType && it.isSupported }

        // Index -1 equals disable track
        if (index == -1) {
            player.trackSelectionParameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(trackType)
                    .setTrackTypeDisabled(trackType, true)
                    .build()
            persistSeriesLanguageOverride(
                trackType = trackType,
                languageCode = null,
                trackSignature = null,
                enabled = false,
            )
        } else {
            val selectedGroup = groups[index]
            val selectedLanguage = groupPrimaryLanguage(selectedGroup)
            player.trackSelectionParameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(
                        TrackSelectionOverride(
                            selectedGroup.mediaTrackGroup,
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

            if (trackType == C.TRACK_TYPE_AUDIO) {
                persistSeriesLanguageOverride(
                    trackType = trackType,
                    languageCode = selectedLanguage,
                    trackSignature = audioTrackSignature(selectedGroup),
                    enabled = true,
                )
                maybeEnableSubtitleForManualAudioSelection(selectedLanguage)
            } else {
                persistSeriesLanguageOverride(
                    trackType = trackType,
                    languageCode = selectedLanguage,
                    trackSignature = subtitleTrackSignature(selectedGroup),
                    enabled = true,
                )
            }
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
            } else {
                val nextEpisode = _uiState.value.nextEpisode
                if (!player.hasNextMediaItem() && nextEpisode != null) {
                    ensureNextEpisodeQueued(nextEpisode)
                }

                when {
                    player.hasNextMediaItem() -> {
                        Timber.i(
                            "Advancing to queued next item current=%s nextIndex=%d queue=%s",
                            player.currentMediaItem?.mediaId,
                            player.currentMediaItemIndex + 1,
                            playerQueueDebugString(),
                        )
                        player.seekToNextMediaItem()
                        player.play()
                    }
                    nextEpisode != null -> {
                        Timber.w(
                            "Next episode %s was available but not queued; replacing queue to continue autoplay",
                            nextEpisode.itemId,
                        )
                        items = mutableListOf(nextEpisode)
                        player.setMediaItems(listOf(nextEpisode.toMediaItem()), 0, 0L)
                        player.prepare()
                        player.play()
                    }
                    else -> {
                        Timber.i("skipToNextItem ignored: no next episode available")
                    }
                }
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

    private fun SpatialFinSegmentType.matchesVoiceSegmentName(requestedName: String): Boolean {
        val normalized = requestedName.lowercase().trim()
        return when (this) {
            SpatialFinSegmentType.INTRO -> normalized in setOf("intro", "opening", "opening credits")
            SpatialFinSegmentType.RECAP -> normalized in setOf("recap", "previously on")
            SpatialFinSegmentType.PREVIEW -> normalized in setOf("preview", "next preview")
            SpatialFinSegmentType.OUTRO -> normalized in setOf("outro", "ending", "credits")
            SpatialFinSegmentType.COMMERCIAL -> normalized in setOf("commercial", "ad", "ads")
            SpatialFinSegmentType.UNKNOWN -> false
        }
    }

    private fun ensureNextEpisodeQueued(nextEpisode: PlayerItem) {
        val currentMediaId = player.currentMediaItem?.mediaId
        val currentQueueIndex =
            items.indexOfFirst { it.itemId.toString() == currentMediaId }
                .takeIf { it >= 0 } ?: items.lastIndex.coerceAtLeast(0)

        if (items.none { it.itemId == nextEpisode.itemId }) {
            items.add((currentQueueIndex + 1).coerceAtMost(items.size), nextEpisode)
        }

        val alreadyQueued =
            (0 until player.mediaItemCount).any { index ->
                player.getMediaItemAt(index).mediaId == nextEpisode.itemId.toString()
            }
        if (!alreadyQueued) {
            val insertIndex = (player.currentMediaItemIndex + 1).coerceIn(0, player.mediaItemCount)
            player.addMediaItem(insertIndex, nextEpisode.toMediaItem())
            Timber.i(
                "Queued next episode on demand next=%s insertIndex=%d queue=%s",
                nextEpisode.itemId,
                insertIndex,
                playerQueueDebugString(),
            )
        }
    }

    private fun playerQueueDebugString(): String {
        if (player.mediaItemCount == 0) return "[]"
        return (0 until player.mediaItemCount).joinToString(prefix = "[", postfix = "]") { index ->
            val marker = if (index == player.currentMediaItemIndex) "*" else ""
            "$marker${player.getMediaItemAt(index).mediaId}"
        }
    }

    private fun inferOriginalAudioLanguage(
        audioGroups: List<Tracks.Group>,
        spokenLanguages: List<String>,
    ): String? {
        val availableLanguages = audioGroups.mapNotNull { groupPrimaryLanguage(it) }.distinct()
        if (availableLanguages.isEmpty()) return null
        if (availableLanguages.size == 1) return availableLanguages.first()

        return availableLanguages.firstOrNull { available ->
            spokenLanguages.none { preferred ->
                LanguageCatalog.matches(application, available, preferred)
            }
        } ?: availableLanguages.first()
    }

    private fun groupPrimaryLanguage(group: Tracks.Group?): String? {
        if (group == null) return null
        return (0 until group.length)
            .mapNotNull { index ->
                LanguageCatalog.normalize(
                    application,
                    group.getTrackFormat(index).language ?: group.getTrackFormat(index).label,
                )
            }
            .firstOrNull()
    }

    private fun groupMatchesLanguage(group: Tracks.Group, languageCode: String): Boolean {
        return (0 until group.length).any { index ->
            val format = group.getTrackFormat(index)
            LanguageCatalog.matches(application, format.language, languageCode) ||
                LanguageCatalog.matches(application, format.label, languageCode)
        }
    }

    private fun persistSeriesLanguageOverride(
        trackType: @C.TrackType Int,
        languageCode: String?,
        trackSignature: String?,
        enabled: Boolean,
    ) {
        val seriesId = currentPlayerItem()?.seriesId?.toString() ?: return
        val existingOverride = appPreferences.getSeriesLanguageOverride(seriesId) ?: SeriesLanguageOverride()
        val updatedOverride =
            when (trackType) {
                C.TRACK_TYPE_AUDIO ->
                    existingOverride.copy(
                        audioLanguageCode = if (enabled) languageCode else null,
                        audioTrackSignature = if (enabled) trackSignature else null,
                    )
                C.TRACK_TYPE_TEXT ->
                    existingOverride.copy(
                        subtitleLanguageCode = if (enabled) languageCode else null,
                        subtitleTrackSignature = if (enabled) trackSignature else null,
                        subtitlesEnabled = enabled,
                    )
                else -> existingOverride
            }

        appPreferences.setSeriesLanguageOverride(seriesId, updatedOverride)
        Timber.d("Saved series language override seriesId=%s override=%s", seriesId, updatedOverride)
    }

    private fun maybeEnableSubtitleForManualAudioSelection(audioLanguageCode: String?) {
        val normalizedAudio = LanguageCatalog.normalize(application, audioLanguageCode) ?: return
        val spokenLanguages = appPreferences.getSmartSpokenLanguageCodes(application)
        val audioUnderstood =
            spokenLanguages.any { preferred ->
                LanguageCatalog.matches(application, normalizedAudio, preferred)
            }
        if (audioUnderstood) return

        val subtitleGroups =
            player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
        val selectedSubtitleGroup =
            spokenLanguages.firstNotNullOfOrNull { preferredCode ->
                subtitleGroups
                    .filter { group -> groupMatchesLanguage(group, preferredCode) }
                    .maxByOrNull { scoreSubtitleGroup(it, preferredLanguageCode = preferredCode) }
            } ?: return

        val subtitleLanguage = groupPrimaryLanguage(selectedSubtitleGroup)
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setIgnoredTextSelectionFlags(0)
                .setOverrideForType(
                    TrackSelectionOverride(selectedSubtitleGroup.mediaTrackGroup, 0)
                )
                .build()

        persistSeriesLanguageOverride(
            trackType = C.TRACK_TYPE_TEXT,
            languageCode = subtitleLanguage,
            trackSignature = subtitleTrackSignature(selectedSubtitleGroup),
            enabled = true,
        )
        Timber.d(
            "Enabled spoken-language subtitles after manual audio switch audio=%s subtitle=%s",
            normalizedAudio,
            subtitleLanguage,
        )
    }

    private fun subtitleTrackSignature(group: Tracks.Group): String {
        val format = group.getTrackFormat(0)
        val label = format.label.orEmpty().trim().lowercase()
        return listOf(
            format.language.orEmpty().trim().lowercase(),
            label,
            format.roleFlags.toString(),
            format.selectionFlags.toString(),
            format.sampleMimeType.orEmpty().trim().lowercase(),
        ).joinToString("|")
    }

    private fun audioTrackSignature(group: Tracks.Group): String {
        val format = group.getTrackFormat(0)
        val label = format.label.orEmpty().trim().lowercase()
        return listOf(
            format.language.orEmpty().trim().lowercase(),
            label,
            format.roleFlags.toString(),
            format.selectionFlags.toString(),
            format.sampleMimeType.orEmpty().trim().lowercase(),
            format.codecs.orEmpty().trim().lowercase(),
        ).joinToString("|")
    }

    private fun scoreSubtitleGroup(
        group: Tracks.Group,
        preferredLanguageCode: String?,
    ): Int {
        val format = group.getTrackFormat(0)
        val label = format.label.orEmpty().lowercase()
        var score = 0

        if (preferredLanguageCode != null && groupMatchesLanguage(group, preferredLanguageCode)) {
            score += 100
        }
        if (label.contains("full") || label.contains("dialog")) {
            score += 35
        }
        if (label.contains("default")) {
            score += 30
        }
        if ((format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0) {
            score += 25
        }
        if ((format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0) {
            score -= 40
        }
        if (label.contains("sign") || label.contains("song")) {
            score -= 60
        }
        if (label.contains("forced")) {
            score -= 50
        }
        if (label.contains("sdh") || label.contains("cc")) {
            score -= 15
        }
        return score
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

    fun searchForSubtitles(language: String) {
        val itemIdStr = _uiState.value.currentItemId ?: return
        viewModelScope.launch {
            _subtitleSearchState.value = SubtitleSearchState.Searching
            try {
                val results = repository.searchRemoteSubtitles(UUID.fromString(itemIdStr), language)
                _subtitleSearchState.value = SubtitleSearchState.Success(results)
            } catch (e: Exception) {
                Timber.e(e, "Failed to search subtitles")
                _subtitleSearchState.value = SubtitleSearchState.Error(e.message, e)
            }
        }
    }

    fun downloadAndSwitchSubtitles(subtitleInfo: org.jellyfin.sdk.model.api.RemoteSubtitleInfo) {
        val itemIdStr = _uiState.value.currentItemId ?: return
        val itemKind = _uiState.value.currentItemKind ?: return
        val itemId = UUID.fromString(itemIdStr)
        val subtitleId = subtitleInfo.id ?: return

        viewModelScope.launch {
            _subtitleSearchState.value = SubtitleSearchState.Downloading
            try {
                repository.downloadRemoteSubtitles(itemId, subtitleId)
                
                val currentPos = player.currentPosition
                val isPlaying = player.playWhenReady
                
                savedStateHandle["position"] = currentPos
                
                initializePlayer(
                    itemId = itemId,
                    itemKind = itemKind,
                    startFromBeginning = false,
                    autoPlay = isPlaying
                )

                _subtitleSearchState.value = SubtitleSearchState.Idle
            } catch (e: Exception) {
                Timber.e(e, "Failed to download subtitle")
                _subtitleSearchState.value = SubtitleSearchState.Error(e.message, e)
            }
        }
    }

    fun clearSubtitleSearchState() {
        _subtitleSearchState.value = SubtitleSearchState.Idle
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
