package dev.jdtech.jellyfin.player.local.presentation

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.Rating
import dev.jdtech.jellyfin.models.SpatialFinMediaStream
import dev.jdtech.jellyfin.models.SpatialFinSegment
import dev.jdtech.jellyfin.models.SpatialFinSegmentType
import dev.jdtech.jellyfin.models.SyncPlayGroup
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter
import dev.jdtech.jellyfin.player.core.domain.models.PlayerContentSource
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.core.domain.models.PlayerPerson
import dev.jdtech.jellyfin.player.core.domain.models.Trickplay
import dev.jdtech.jellyfin.player.local.R
import dev.jdtech.jellyfin.player.local.domain.PlaylistManager
import dev.jdtech.jellyfin.player.local.subtitles.SubtitleCacheManager
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.repository.LocalMediaRepository
import dev.jdtech.jellyfin.repository.NetworkMediaRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.Constants
import dev.jdtech.jellyfin.settings.presentation.enums.QualityOption
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
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber

private const val ASSISTANT_SUBTITLE_HISTORY_WINDOW_MS = 20 * 60 * 1_000L
private const val ASSISTANT_SUBTITLE_HISTORY_MAX_LINES = 400

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

    /** Disk-backed subtitle cache used to provide full-video dialogue context to the AI. */
    val subtitleCacheManager = SubtitleCacheManager(application)

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
                visualSubtitlesEnabled = true,
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

    private val syncPlay =
        SyncPlayCoordinator(
            application = application,
            repository = repository,
            playlistManager = playlistManager,
            scope = viewModelScope,
            host = object : SyncPlayCoordinator.Host {
                override val player: Player
                    get() = this@PlayerViewModel.player
                override val currentMediaSourceStreams: List<SpatialFinMediaStream>
                    get() = this@PlayerViewModel.currentMediaSourceStreams
                override var playbackPosition: Long
                    get() = this@PlayerViewModel.playbackPosition
                    set(value) {
                        this@PlayerViewModel.playbackPosition = value
                    }

                override fun currentPlayerItem(): PlayerItem? =
                    this@PlayerViewModel.currentPlayerItem()

                override fun currentItemTitle(): String = _uiState.value.currentItemTitle

                override fun updateNextEpisode(nextEpisode: PlayerItem?) {
                    _uiState.update { it.copy(nextEpisode = nextEpisode) }
                }

                override fun replaceItems(items: MutableList<PlayerItem>) {
                    this@PlayerViewModel.items = items
                }

                override fun initializePlayer(
                    itemId: UUID,
                    itemKind: String,
                    startFromBeginning: Boolean,
                    mediaSourceIndex: Int?,
                    autoPlay: Boolean,
                ) {
                    this@PlayerViewModel.initializePlayer(
                        itemId = itemId,
                        itemKind = itemKind,
                        startFromBeginning = startFromBeginning,
                        mediaSourceIndex = mediaSourceIndex,
                        autoPlay = autoPlay,
                    )
                }

                override fun switchToTrack(trackType: Int, index: Int) {
                    this@PlayerViewModel.switchToTrack(trackType, index)
                }

                override fun skipToNextItem() {
                    this@PlayerViewModel.skipToNextItem()
                }

                override fun toMediaItem(item: PlayerItem): MediaItem = with(item) { toMediaItem() }
            },
        )
    val syncPlayState = syncPlay.state

    private val trackSelector =
        PlayerTrackSelector(
            application = application,
            appPreferences = appPreferences,
            host = object : PlayerTrackSelector.Host {
                override val player: Player
                    get() = this@PlayerViewModel.player

                override fun currentPlayerItem(): PlayerItem? =
                    this@PlayerViewModel.currentPlayerItem()

                override fun setVisualSubtitlesEnabled(enabled: Boolean) {
                    _uiState.update { it.copy(visualSubtitlesEnabled = enabled) }
                }
            },
        )

    private val eventsChannel = Channel<PlayerEvents>()
    val eventsChannelFlow = eventsChannel.receiveAsFlow()

    private val _currentFrameRate = MutableStateFlow(-1f)
    val currentFrameRate = _currentFrameRate.asStateFlow()

    private val _assistantSubtitleHistory = MutableStateFlow<List<Pair<Long, String>>>(emptyList())
    val assistantSubtitleHistory = _assistantSubtitleHistory.asStateFlow()

    data class UiState(
        val currentItemTitle: String,
        val currentItemId: String? = null,
        val currentItemKind: String? = null,
        val currentItemLogoUri: String? = null,
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
        val visualSubtitlesEnabled: Boolean = true,
        /**
         * Pre-formatted chip shown in the player overlay describing the active
         * stream — e.g. "Direct Play · 1080p · HEVC". Null while the source is
         * loading or for non-Jellyfin content (local / network file).
         */
        val currentPlaybackInfoLabel: String? = null,
    )

    private var items: MutableList<PlayerItem> = mutableListOf()

    private val eventTrackSelector = DefaultTrackSelector(application)
    var playWhenReady = true
    private var currentMediaItemIndex = savedStateHandle["mediaItemIndex"] ?: 0
    private var playbackPosition: Long = savedStateHandle["position"] ?: 0
    private var currentMediaItemSegments: List<SpatialFinSegment> = emptyList()
    private var currentMediaSourceStreams: List<SpatialFinMediaStream> = emptyList()

    // Segments preferences
    var segmentsSkipButton: Boolean = false
    private var segmentsSkipButtonTypes: Set<String> = emptySet()
    var segmentsSkipButtonDuration: Long = 0L
    var segmentsAutoSkip: Boolean = false
    private var segmentsAutoSkipTypes: Set<String> = emptySet()
    private var segmentsAutoSkipMode: String = "always"

    var playbackSpeed: Float = 1f

    var isInPictureInPictureMode: Boolean = false

    private var pendingAutoAdvanceMediaId: String? = null

    // Adaptive-auto state. `effectiveAutoBitrate` is the cap currently in use
    // when the user's pref is Auto (0L); a rebuffer storm pushes it down a
    // QualityOption at a time. `recentRebufferMs` holds the timestamps of
    // BUFFERING events that happened after the file was loaded, trimmed to a
    // rolling 60-second window. Reset whenever a new Jellyfin item loads.
    private var effectiveAutoBitrate: Long = 0L
    private val recentRebufferMs: ArrayDeque<Long> = ArrayDeque()
    private var lastPlaybackState: Int = Player.STATE_IDLE
    private var autoDowngradeCooldownUntilMs: Long = 0L
    private var startupWatchdogJob: kotlinx.coroutines.Job? = null

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

        Timber.d("PlayerViewModel init: loading segment preferences")
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
        Timber.d("PlayerViewModel init: segment preferences loaded")

        // All current player activities replace this constructor-time player instance with
        // an activity-specific ExoPlayer almost immediately. Keep the placeholder minimal so
        // XR launch does not allocate advanced renderer / spatialization resources twice.
        Timber.d("PlayerViewModel init: building placeholder player")
        val initialPlayer = ExoPlayer.Builder(application)
            .setPauseAtEndOfMediaItems(true)
            .build()
        Timber.d("PlayerViewModel init: placeholder player built")
        _playerFlow = MutableStateFlow(initialPlayer)
        playerFlow = _playerFlow.asStateFlow()
        
        // Add comprehensive logging for network, buffering, and dropped frames for ExoPlayer
        (player as? ExoPlayer)?.addAnalyticsListener(EventLogger(eventTrackSelector))

        viewModelScope.launch {
            repository.observePlayStateMessages().collect { message ->
                message.data?.let { request ->
                    syncPlay.handlePlayStateMessage(request.command, request.seekPositionTicks)
                }
            }
        }
        viewModelScope.launch {
            repository.observeSyncPlayCommandMessages().collect(syncPlay::handleSyncPlayCommandMessage)
        }
        viewModelScope.launch {
            repository.observeSyncPlayGroupUpdates().collect(syncPlay::handleSyncPlayGroupUpdate)
        }
        viewModelScope.launch {
            repository.observeGeneralCommandMessages().collect(syncPlay::handleGeneralCommandMessage)
        }
        viewModelScope.launch {
            repository.observeSocketState().collectLatest(syncPlay::handleSocketState)
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
        clearAssistantSubtitleHistory()
        player.removeListener(this)
        player.release()
        player = newPlayer
        player.addListener(this)
        (player as? ExoPlayer)?.addAnalyticsListener(EventLogger(eventTrackSelector))
    }

    fun clearAssistantSubtitleHistory() {
        _assistantSubtitleHistory.value = emptyList()
    }

    fun recordAssistantSubtitleLine(timestampMs: Long, text: String) {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return

        _assistantSubtitleHistory.update { existing ->
            val cutoff = (timestampMs - ASSISTANT_SUBTITLE_HISTORY_WINDOW_MS).coerceAtLeast(0L)
            val trimmed =
                existing
                    .filter { (lineTimestampMs, _) -> lineTimestampMs in cutoff..timestampMs }
                    .toMutableList()
            val lastLine = trimmed.lastOrNull()
            if (lastLine != null && lastLine.first == timestampMs && lastLine.second == normalized) {
                return@update trimmed
            }
            trimmed.add(timestampMs to normalized)
            trimmed.takeLast(ASSISTANT_SUBTITLE_HISTORY_MAX_LINES)
        }
    }

    /**
     * Downloads and caches the subtitle file for [item] so the AI can answer questions about any
     * part of the video — not just the last 20 minutes captured in the in-memory ring buffer.
     *
     * Strategy:
     * 1. Skip if a cache already exists for this item.
     * 2. Download from an existing external Jellyfin subtitle track (fastest path — the URL is
     *    already embedded in the PlaybackInfo response).
     * 3. Otherwise fall back to an embedded subtitle stream via Jellyfin's deliveryUrl.
     *
     * If neither path yields a cache, the AI gracefully degrades to the in-memory rolling
     * buffer alone. We deliberately do NOT trigger Jellyfin's OpenSubtitles auto-download as
     * a side effect: that writes a sibling `<media>.<lang>.srt` next to the video file, which
     * shifts every embedded MediaStream.Index on the next metadata refresh and desyncs
     * Jellyfin's own subtitle cache — selecting "Spanish" ends up rendering Thai, etc.
     * Users who want a fresh subtitle for an item with none can still use the manual
     * "Search subtitles" dialog (see [searchRemoteSubtitles] / [downloadRemoteSubtitles]).
     *
     * This runs on a background IO dispatcher and never blocks or affects playback.
     */
    private suspend fun prefetchSubtitleCache(item: PlayerItem, itemKind: String) {
        val itemId = item.itemId
        if (subtitleCacheManager.hasCache(itemId)) return

        val accessToken = repository.getAccessToken()
        Timber.i(
            "SubtitleCache: prefetching for %s extSubs=%d contentSource=%s",
            itemId, item.externalSubtitles.size, item.contentSource,
        )

        // ── Phase 1: existing external subtitle tracks (URI already known) ─────
        val externalSub = item.externalSubtitles
            .firstOrNull { it.mimeType != androidx.media3.common.MimeTypes.TEXT_UNKNOWN }
            ?: item.externalSubtitles.firstOrNull()

        if (externalSub != null) {
            val count = subtitleCacheManager.downloadAndCache(itemId, externalSub.uri, accessToken)
            Timber.i("SubtitleCache: phase1 external → %d lines for %s", count, itemId)
            if (count > 0) return
        }

        // ── Phase 1.5: embedded subtitle streams via Jellyfin delivery API ────
        // Jellyfin constructs a deliveryUrl for ALL text subtitle tracks (embedded or external),
        // stored in SpatialFinMediaStream.path = baseUrl + deliveryUrl.
        // This is the primary path for embedded MKV subtitle tracks.
        if (item.contentSource != dev.jdtech.jellyfin.player.core.domain.models.PlayerContentSource.JELLYFIN) return
        runCatching {
            val sources = repository.getMediaSources(itemId, includePath = true)
            // Pick the best subtitle stream: prefer SDH (hearing-impaired), otherwise any text track.
            // We do NOT filter by isExternal — embedded tracks also have valid deliveryUrls.
            val subtitleStream = sources
                .flatMap { it.mediaStreams }
                .filter { it.type == MediaStreamType.SUBTITLE && !it.path.isNullOrBlank() }
                .let { streams ->
                    streams.firstOrNull { it.codec in listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt") }
                        ?: streams.firstOrNull()
                }
            Timber.i(
                "SubtitleCache: phase1.5 streams=%d chosen=%s path=%s",
                sources.flatMap { it.mediaStreams }.count { it.type == MediaStreamType.SUBTITLE },
                subtitleStream?.codec, subtitleStream?.path?.take(80),
            )
            if (subtitleStream?.path != null) {
                val count = subtitleCacheManager.downloadAndCache(
                    itemId,
                    android.net.Uri.parse(subtitleStream.path),
                    accessToken,
                )
                Timber.i("SubtitleCache: phase1.5 embedded → %d lines for %s", count, itemId)
            }
        }.onFailure { e ->
            Timber.w(e, "SubtitleCache: phase1.5 failed for %s", itemId)
        }
    }

    fun refreshSyncPlayGroups() = syncPlay.refreshGroups()

    fun createSyncPlayGroup() = syncPlay.createGroup()

    fun joinSyncPlayGroup(groupId: UUID) = syncPlay.joinGroup(groupId)

    fun leaveSyncPlayGroup() = syncPlay.leaveGroup()

    fun initializePlayer(
        itemId: UUID,
        itemKind: String,
        startFromBeginning: Boolean,
        mediaSourceIndex: Int? = null,
        maxBitrate: Long? = null,
        autoPlay: Boolean = true,
        startPositionMs: Long? = null,
    ) {
        currentItemKind = itemKind
        if (startPositionMs != null && startPositionMs > 0L) {
            playbackPosition = startPositionMs
        }
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
            armStartupWatchdog()
            if (autoPlay) {
                player.play()
            } else {
                player.pause()
            }

            // Pre-fetch subtitle file for AI context in the background.
            viewModelScope.launch(Dispatchers.IO) {
                prefetchSubtitleCache(startItem, itemKind)
            }
        }
    }

    fun changeQuality(itemId: UUID, itemKind: String, newMaxBitrate: Long) {
        val currentPosition = player.currentPosition
        playbackPosition = currentPosition
        appPreferences.setValue(appPreferences.playerMaxBitrate, newMaxBitrate)
        // User made an explicit choice — wipe any adaptive-auto history so the
        // rolling rebuffer window starts fresh from this preset.
        effectiveAutoBitrate = newMaxBitrate
        recentRebufferMs.clear()
        autoDowngradeCooldownUntilMs = 0L
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
        val builder = MediaItem.Builder()
            .setMediaId(itemId.toString())
            .setUri(streamUrl)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(name).build())
            .setSubtitleConfigurations(mediaSubtitles)
        // Hint HLS when Jellyfin returned a transcoding master playlist so
        // DefaultMediaSourceFactory dispatches to HlsMediaSource even when the
        // URL's .m3u8 extension is hidden behind query params.
        if (streamUrl.contains(".m3u8", ignoreCase = true)) {
            builder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
        }
        return builder.build()
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
                                    if (duration > 0) position.div(duration.toFloat()).times(100).toInt() else 0,
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
                            val itemId = UUID.fromString(player.currentMediaItem?.mediaId ?: return@let)
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
        clearAssistantSubtitleHistory()
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
                                currentItemLogoUri = item.logoImageUri,
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
                            val activeSource = repository
                                .getMediaSources(item.itemId, includePath = true)
                                .firstOrNull { source -> source.id == item.mediaSourceId }
                            currentMediaSourceStreams = activeSource?.mediaStreams.orEmpty()
                            val playbackInfoLabel = buildPlaybackInfoLabel(activeSource)
                            _uiState.update { it.copy(currentPlaybackInfoLabel = playbackInfoLabel) }
                            syncPlay.ensureRemotePlaybackSessionReady()
                            repository.postPlaybackStart(item.itemId)

                            if (segmentsSkipButton || segmentsAutoSkip) {
                                getSegments(item.itemId)
                            }

                            if (appPreferences.getValue(appPreferences.playerTrickplay)) {
                                getTrickplay(item)
                            }
                        } else {
                            currentMediaItemSegments = emptyList()
                            currentMediaSourceStreams = emptyList()
                            _uiState.update { it.copy(currentPlaybackInfoLabel = null) }
                        }

                        if (item.contentSource == PlayerContentSource.JELLYFIN) {
                            playlistManager.setCurrentMediaItemIndex(item.itemId)
                        }

                        if (syncPlay.isActive()) {
                            syncPlay.currentPlaylistItemId = item.playlistItemId
                            _uiState.update {
                                it.copy(nextEpisode = items.getOrNull(player.currentMediaItemIndex + 1))
                            }
                        } else if (item.contentSource == PlayerContentSource.JELLYFIN) {
                            val nextItem = playlistManager.getNextPlayerItem()
                            _uiState.update { it.copy(nextEpisode = nextItem) }
                            // Re-query indices after the suspend to get up-to-date queue state.
                            if (nextItem != null) {
                                val queueIndex =
                                    items.indexOfFirst { queuedItem -> queuedItem.itemId == item.itemId }
                                        .takeIf { it >= 0 } ?: player.currentMediaItemIndex.coerceAtLeast(0)
                                if (items.none { it.itemId == nextItem.itemId }) {
                                    items.add((queueIndex + 1).coerceAtMost(items.size), nextItem)
                                }
                                val alreadyQueued = (0 until player.mediaItemCount).any {
                                    player.getMediaItemAt(it).mediaId == nextItem.itemId.toString()
                                }
                                if (!alreadyQueued) {
                                    player.addMediaItem(
                                        (player.currentMediaItemIndex + 1).coerceAtMost(player.mediaItemCount),
                                        nextItem.toMediaItem(),
                                    )
                                }
                            }

                            val previousItem = playlistManager.getPreviousPlayerItem()
                            if (previousItem != null) {
                                // Re-query again after second suspend.
                                val queueIndex =
                                    items.indexOfFirst { queuedItem -> queuedItem.itemId == item.itemId }
                                        .takeIf { it >= 0 } ?: player.currentMediaItemIndex.coerceAtLeast(0)
                                if (items.none { it.itemId == previousItem.itemId }) {
                                    items.add(queueIndex.coerceAtMost(items.size), previousItem)
                                }
                                val alreadyQueued = (0 until player.mediaItemCount).any {
                                    player.getMediaItemAt(it).mediaId == previousItem.itemId.toString()
                                }
                                if (!alreadyQueued) {
                                    player.addMediaItem(
                                        player.currentMediaItemIndex.coerceAtMost(player.mediaItemCount),
                                        previousItem.toMediaItem(),
                                    )
                                }
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

                        trackSelector.resetAutoSelection()
                        trackSelector.applySmart()
                        Timber.tag("PlayerItems").d(items.map { it.indexNumber }.toString())
                    }
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
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
                            if (mediaId == null) return@launch
                            repository.postPlaybackStop(
                                UUID.fromString(mediaId),
                                position.times(10000),
                                if (duration > 0) position.div(duration.toFloat()).times(100).toInt() else 0,
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
                if (syncPlay.isActive() && !syncPlay.shouldSuppressEvents()) {
                    val playlistItemId = syncPlay.currentPlaylistItemId
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
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            clearAssistantSubtitleHistory()
        }
        if (
            !syncPlay.isActive() ||
                syncPlay.shouldSuppressEvents() ||
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
                // Only count as a rebuffer if we were mid-playback (READY → BUFFERING);
                // the initial load transitions IDLE/BUFFERING → READY and shouldn't count.
                if (lastPlaybackState == ExoPlayer.STATE_READY && playWhenReady) {
                    onRebufferObserved()
                }
            }
            ExoPlayer.STATE_READY -> {
                stateString = "ExoPlayer.STATE_READY     -"
                _uiState.update { it.copy(fileLoaded = true) }
                startupWatchdogJob?.cancel()
                startupWatchdogJob = null
            }
            ExoPlayer.STATE_ENDED -> {
                stateString = "ExoPlayer.STATE_ENDED     -"
                val currentMediaId = player.currentMediaItem?.mediaId
                val nextEpisode = _uiState.value.nextEpisode
                val shouldAutoAdvance =
                    if (syncPlay.isActive()) {
                        syncPlay.currentPlaylistItemId != null && nextEpisode != null
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
        lastPlaybackState = state
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

    /**
     * Records a mid-playback BUFFERING transition and, when the user is on the
     * "Auto" quality preset, steps the streaming cap down one preset if three
     * rebuffers have occurred within the last 60 seconds. A 30-second cooldown
     * after each downgrade prevents a cascade while the new, lower-bitrate
     * stream is re-buffering itself.
     */
    private fun onRebufferObserved() {
        val nowMs = SystemClock.elapsedRealtime()
        // Trim to 60s rolling window
        while (recentRebufferMs.isNotEmpty() && nowMs - recentRebufferMs.first() > 60_000L) {
            recentRebufferMs.removeFirst()
        }
        recentRebufferMs.addLast(nowMs)

        if (!isAdaptiveAutoEligible(nowMs)) return
        if (recentRebufferMs.size < 3) return
        triggerAutoDowngrade(reason = "rebuffer-storm")
    }

    private fun isAdaptiveAutoEligible(nowMs: Long): Boolean {
        if (appPreferences.getValue(appPreferences.playerForceDirectPlay)) return false
        if (appPreferences.getValue(appPreferences.playerMaxBitrate) != 0L) return false
        if (nowMs < autoDowngradeCooldownUntilMs) return false
        return true
    }

    private fun triggerAutoDowngrade(reason: String) {
        val nowMs = SystemClock.elapsedRealtime()
        val next = nextLowerAutoStep(effectiveAutoBitrate) ?: return
        effectiveAutoBitrate = next
        autoDowngradeCooldownUntilMs = nowMs + 30_000L
        recentRebufferMs.clear()

        val currentItemId = _uiState.value.currentItemId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return
        val currentItemKind = _uiState.value.currentItemKind ?: return

        val newOption = QualityOption.fromBps(next)
        val friendly = application.getString(newOption.labelRes)
        Timber.i("Adaptive auto: downgrading to %s (%d bps) reason=%s", friendly, next, reason)
        Toast.makeText(
            application,
            application.getString(R.string.quality_auto_downgraded, friendly),
            Toast.LENGTH_SHORT,
        ).show()

        playbackPosition = player.currentPosition
        initializePlayer(
            itemId = currentItemId,
            itemKind = currentItemKind,
            startFromBeginning = false,
            maxBitrate = next,
        )
    }

    /**
     * Watchdog that fires when a session never reaches STATE_READY in time —
     * typically because the selected preset is bigger than the network can
     * sustain, so ExoPlayer sits in BUFFERING indefinitely. 45s for the first
     * attempt (accommodates server-side HLS transcode startup), 25s on retries
     * since the transcoder pipeline is already warm.
     */
    private fun armStartupWatchdog() {
        startupWatchdogJob?.cancel()
        val nowMs = SystemClock.elapsedRealtime()
        if (!isAdaptiveAutoEligible(nowMs)) return
        val firstAttempt = effectiveAutoBitrate == 0L && recentRebufferMs.isEmpty()
        val timeoutMs = if (firstAttempt) 45_000L else 25_000L
        startupWatchdogJob = viewModelScope.launch {
            kotlinx.coroutines.delay(timeoutMs)
            if (lastPlaybackState != Player.STATE_READY && player.playWhenReady) {
                triggerAutoDowngrade(reason = "startup-stall-${timeoutMs}ms")
            }
        }
    }

    /**
     * The first rebuffer in Auto caps at 4K (40 Mbps) regardless of source —
     * that's already tight enough for most networks. Subsequent rebuffers walk
     * the QualityOption ladder downward one step at a time.
     */
    private fun nextLowerAutoStep(current: Long): Long? = when {
        current == 0L -> QualityOption.UHD.bps
        current > QualityOption.UHD.bps -> QualityOption.UHD.bps
        current > QualityOption.FHD.bps -> QualityOption.FHD.bps
        current > QualityOption.HD.bps -> QualityOption.HD.bps
        current > QualityOption.SD.bps -> QualityOption.SD.bps
        current > QualityOption.LOW.bps -> QualityOption.LOW.bps
        else -> null
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

        trackSelector.applySmart()
        syncPlay.applyPendingRemoteStreamSelections()
    }

    override fun onCleared() {
        super.onCleared()
        Timber.d("Clearing Player ViewModel")
        // viewModelScope is cancelled before onCleared() is called, so use a detached scope.
        if (syncPlay.isActive()) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).launch {
                runCatching { repository.leaveSyncPlayGroup() }
            }
        }
        releasePlayer()
    }

    fun switchToTrack(trackType: @C.TrackType Int, index: Int) =
        trackSelector.switchToTrack(trackType, index)

    fun skipToNextItem() {
        viewModelScope.launch {
            if (syncPlay.isActive() && !syncPlay.shouldSuppressEvents()) {
                syncPlay.currentPlaylistItemId?.let { playlistItemId ->
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
            if (syncPlay.isActive() && !syncPlay.shouldSuppressEvents()) {
                syncPlay.currentPlaylistItemId?.let { playlistItemId ->
                    runCatching { repository.previousSyncPlayItem(playlistItemId) }
                        .onFailure { Timber.w(it, "Failed to request previous SyncPlay item") }
                }
            } else if (player.hasPreviousMediaItem()) {
                player.seekToPreviousMediaItem()
                player.play()
            }
        }
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
            if (trickplayInfo.width <= 0 || trickplayInfo.height <= 0 ||
                trickplayInfo.tileWidth <= 0 || trickplayInfo.tileHeight <= 0
            ) {
                Timber.w("Trickplay: skipping — invalid dimensions w=%d h=%d tw=%d th=%d",
                    trickplayInfo.width, trickplayInfo.height,
                    trickplayInfo.tileWidth, trickplayInfo.tileHeight)
                return@withContext
            }
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
                    try {
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
                    } finally {
                        fullBitmap.recycle()
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
        if (syncPlay.isActive() && !syncPlay.shouldSuppressEvents() && player.playbackState == Player.STATE_READY) {
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

    private fun buildPlaybackInfoLabel(source: dev.jdtech.jellyfin.models.SpatialFinSource?): String? {
        if (source == null) return null
        val videoStream = source.mediaStreams.firstOrNull { it.type == MediaStreamType.VIDEO } ?: return null
        val methodRes =
            if (source.supportsDirectPlay) R.string.playback_method_direct_play
            else R.string.playback_method_transcoding
        val method = application.getString(methodRes)
        val resolutionLabel = when (val h = videoStream.height) {
            null -> null
            in 2000..Int.MAX_VALUE -> "4K"
            in 1400..1999 -> "1440p"
            in 950..1399 -> "1080p"
            in 650..949 -> "720p"
            in 440..649 -> "480p"
            in 300..439 -> "360p"
            else -> "${h}p"
        }
        val codec = videoStream.codec.takeIf { it.isNotBlank() }?.uppercase()
        return listOfNotNull(method, resolutionLabel, codec).joinToString(" · ")
    }
}

sealed interface PlayerEvents {
    data object NavigateBack : PlayerEvents

    data class IsPlayingChanged(val isPlaying: Boolean) : PlayerEvents
}
