package dev.jdtech.jellyfin.player.tv

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Stable
import androidx.media3.common.C
import androidx.media3.common.Player
import dagger.hilt.android.EntryPointAccessors
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.player.local.domain.getTrackNames
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import dev.jdtech.jellyfin.player.session.voice.PlayerSessionController
import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.player.session.voice.VoiceScreenContext
import dev.jdtech.jellyfin.player.session.voice.XrPlayerAction
import dev.jdtech.jellyfin.player.xr.LlmEntryPoint
import dev.jdtech.jellyfin.player.xr.voice.GeminiCloudService
import dev.jdtech.jellyfin.player.xr.voice.GeminiNanoService
import dev.jdtech.jellyfin.player.xr.voice.SmartChatEngine
import dev.jdtech.jellyfin.player.xr.voice.SpatialCommandCoordinator
import dev.jdtech.jellyfin.player.xr.voice.SpatialVoiceService
import dev.jdtech.jellyfin.player.xr.voice.SpatialVoiceSynthesizer
import dev.jdtech.jellyfin.player.xr.voice.VoiceState
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/** UI state for the TV player voice overlay — mirrors the design VoiceFeedback states. */
internal sealed interface TvVoiceUi {
    data object Idle : TvVoiceUi
    data object Listening : TvVoiceUi
    data object Processing : TvVoiceUi
    data class Answered(val text: String) : TvVoiceUi
    data class Error(val text: String) : TvVoiceUi
}

/**
 * Drives voice turns on the TV player, giving the 10-foot surface the same
 * assistant the phone and XR players have. Captures speech via
 * [SpatialVoiceService], parses it with [SpatialCommandCoordinator] (deterministic
 * keyword matching first, on-device model as fallback), and either dispatches a
 * player action through [PlayerSessionController] or answers a question through
 * [SmartChatEngine] — speaking the reply via [SpatialVoiceSynthesizer].
 *
 * The heavy AI singletons are built lazily on first mic use so they never warm
 * engines on a TV that never touches voice. STT itself is cheap and eager so the
 * feedback overlay can observe partial transcripts immediately.
 */
@Stable
internal class TvPlayerVoiceController(
    private val appContext: Context,
    private val viewModel: PlayerViewModel,
    private val player: Player,
    private val scope: CoroutineScope,
    private val onNavigateBack: () -> Unit,
    private val onControlsVisibility: (Boolean) -> Unit,
    private val onLaunchItem: (SpatialFinItem) -> Unit,
    private val onShowSearchResults: (String, List<SpatialFinItem>) -> Unit,
    private val onOpenSyncPlay: () -> Unit,
) {
    private val _ui = MutableStateFlow<TvVoiceUi>(TvVoiceUi.Idle)
    val ui: StateFlow<TvVoiceUi> = _ui.asStateFlow()

    private val voiceService = SpatialVoiceService(appContext)
    val partialTranscript: StateFlow<String> = voiceService.partialTranscript

    fun isAvailable(): Boolean = voiceService.isAvailable()

    private val geminiNano by lazy { GeminiNanoService(appContext) }
    private val geminiCloud by lazy {
        GeminiCloudService(appContext, viewModel.appPreferences, viewModel.repository)
    }
    private val tts by lazy { SpatialVoiceSynthesizer(appContext) }
    private val modelManager by lazy {
        EntryPointAccessors.fromApplication(appContext, LlmEntryPoint::class.java).modelManager()
    }
    private val coordinator by lazy {
        SpatialCommandCoordinator(appContext, geminiNano, geminiCloud, viewModel.appPreferences, modelManager)
    }
    private val chatEngine by lazy {
        SmartChatEngine(geminiNano, geminiCloud, viewModel.appPreferences, modelManager, viewModel.repository)
    }

    private val controller: PlayerSessionController by lazy {
        PlayerSessionController(
            viewModel = viewModel,
            player = player,
            onControlsVisibilityChange = onControlsVisibility,
            onNavigateBack = onNavigateBack,
            onShowVoiceSearch = { query, results, _ -> onShowSearchResults(query, results) },
            onShowSyncPlayDialog = onOpenSyncPlay,
            onGoHome = {
                runCatching {
                    appContext.startActivity(
                        Intent()
                            .setClassName(appContext, "dev.spatialfin.unified.UnifiedMainActivity")
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    true
                }.getOrElse { false }
            },
            onCloseApp = onNavigateBack,
            onLaunchSearchResult = onLaunchItem,
            onSearchQuery = { query ->
                runCatching { viewModel.repository.getSearchItems(query) }.getOrDefault(emptyList())
            },
            getAvailableSyncPlayGroups = { viewModel.syncPlayState.value.availableGroups },
            setPassthroughEnabled = {},
            getPassthroughEnabled = { false },
        )
    }

    private var turnJob: Job? = null

    /** Toggle: start a turn when idle, cancel when mid-turn. */
    fun toggle(snapshot: PlayerStateSnapshot) {
        when (_ui.value) {
            is TvVoiceUi.Listening, is TvVoiceUi.Processing -> cancel()
            else -> start(snapshot)
        }
    }

    private fun start(snapshot: PlayerStateSnapshot) {
        if (!voiceService.canStartListening()) return
        _ui.value = TvVoiceUi.Listening
        voiceService.startListening { transcript ->
            turnJob = scope.launch {
                try {
                    _ui.value = TvVoiceUi.Processing
                    val parseResult = coordinator.parse(transcript, snapshot)
                    val action = parseResult.action
                    if (action is XrPlayerAction.ChatQuery) {
                        val reply = runCatching {
                            chatEngine.query(
                                question = action.query,
                                playerState = snapshot,
                                currentPositionMs = player.currentPosition,
                                onTokenStream = { partial -> _ui.value = TvVoiceUi.Answered(partial) },
                            )
                        }.getOrNull()
                        val text = reply?.text?.takeIf { it.isNotBlank() } ?: "Sorry, I couldn't process that."
                        _ui.value = TvVoiceUi.Answered(text)
                        speak(text)
                        reply?.playRequest?.let {
                            controller.dispatch(XrPlayerAction.Search(query = it.title, autoPlay = true))
                        }
                        if (reply?.recommendedItems?.isNotEmpty() == true) {
                            controller.showRecommendations(action.query, reply.recommendedItems)
                        }
                    } else {
                        val feedback = controller.dispatch(action)
                        if (action is XrPlayerAction.Unrecognized) {
                            _ui.value = TvVoiceUi.Error(feedback)
                        } else {
                            _ui.value = TvVoiceUi.Answered(feedback)
                            if (shouldSpeak(action)) speak(feedback)
                        }
                    }
                } catch (t: Throwable) {
                    Timber.w(t, "TV voice turn failed")
                    _ui.value = TvVoiceUi.Error("Something went wrong.")
                } finally {
                    voiceService.resetState()
                }
            }
        }
    }

    fun cancel() {
        turnJob?.cancel()
        runCatching { voiceService.cancelListening() }
        _ui.value = TvVoiceUi.Idle
    }

    fun dismiss() {
        _ui.value = TvVoiceUi.Idle
    }

    private fun speak(text: String) {
        runCatching { tts.speak(text) }
    }

    private fun shouldSpeak(action: XrPlayerAction): Boolean = when (action) {
        is XrPlayerAction.ReportCurrentTime,
        is XrPlayerAction.ReportRemainingTime,
        is XrPlayerAction.ReportEndTime,
        is XrPlayerAction.ReportCurrentMedia,
        -> true
        else -> false
    }

    fun destroy() {
        turnJob?.cancel()
        runCatching { voiceService.destroy() }
        runCatching { coordinator.destroy() }
        runCatching { chatEngine.destroy() }
        runCatching { geminiNano.destroy() }
        runCatching { geminiCloud.destroy() }
        runCatching { tts.destroy() }
    }
}

/** Builds the assistant's view of the player from the current UI/track state. */
internal fun buildTvPlayerSnapshot(
    uiState: PlayerViewModel.UiState,
    player: Player,
    currentPosition: Long,
    duration: Long,
    controlsVisible: Boolean,
): PlayerStateSnapshot {
    val groups = player.currentTracks.groups
    val audioNames = groups.filter { it.type == C.TRACK_TYPE_AUDIO }.getTrackNames().toList()
    val subtitleNames = groups.filter { it.type == C.TRACK_TYPE_TEXT }.getTrackNames().toList()
    return PlayerStateSnapshot(
        screenContext = VoiceScreenContext.PLAYER,
        isPlaying = player.isPlaying,
        positionSeconds = (currentPosition / 1000).coerceAtLeast(0),
        durationSeconds = (duration / 1000).coerceAtLeast(0),
        controlsVisible = controlsVisible,
        currentItemTitle = uiState.currentItemTitle,
        currentOverview = uiState.currentOverview,
        currentSeriesName = uiState.currentSeriesName,
        currentSeasonNumber = uiState.currentSeasonNumber,
        currentEpisodeNumber = uiState.currentEpisodeNumber,
        nextEpisodeTitle = uiState.nextEpisode?.name,
        currentGenres = uiState.currentGenres.toImmutableList(),
        productionYear = uiState.currentProductionYear,
        officialRating = uiState.currentOfficialRating,
        audioTrackNames = audioNames.toImmutableList(),
        subtitleTrackNames = subtitleNames.toImmutableList(),
        chapterNames = uiState.currentChapters.mapNotNull { it.name?.ifBlank { null } }.toImmutableList(),
    )
}
