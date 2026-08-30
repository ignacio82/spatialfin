package dev.jdtech.jellyfin.player.session.voice

import dev.spatialfin.companion.protocol.WearChapterInfo
import dev.spatialfin.companion.protocol.WearNowPlayingState
import dev.spatialfin.companion.protocol.WearPlayerAction
import dev.spatialfin.companion.protocol.WearStreamInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID

/**
 * Process-wide holder for the active playback session across XR, Beam (phone),
 * and TV form factors. Allows background services (such as the Wear OS Data Layer
 * listener, FCast receiver, and voice relays) to dispatch commands and observe
 * playback state without direct activity handles.
 *
 * **Threading:** every provider stored in an [ActiveSession] reads the Media3
 * `Player`, which rejects access from anything but the thread it was built on
 * (the main thread). [bind] runs from composition and is already there; [refresh]
 * and [dispatch] are suspend functions that hop to `Dispatchers.Main.immediate`
 * themselves, so callers on an IO scope — like `WearHostDataLayerService` — are safe.
 */
object ActivePlayerSessionHolder {

    data class ActiveSession(
        val id: String = UUID.randomUUID().toString(),
        val controller: PlayerSessionController,
        val snapshotProvider: () -> PlayerStateSnapshot,
        /** Chapter name to start position in **milliseconds**, in playback order. */
        val chaptersProvider: (() -> List<Pair<String, Long>>)? = null,
        val streamUrlProvider: (() -> String?)? = null,
        val mediaContainerProvider: (() -> String?)? = null,
        val currentItemIdProvider: (() -> String?)? = null,
        val volumeProvider: (() -> Float)? = null,
        val speedProvider: (() -> Float)? = null,
        val onPlayMediaItem: (suspend (itemId: String, mediaType: String?, startPositionMs: Long) -> String)? = null,
        /**
         * Runs a natural-language command through this form factor's own voice
         * pipeline (`SpatialCommandCoordinator` on XR/Beam, `TvPlayerVoiceController`
         * on TV) and returns the spoken feedback. Kept as a seam because the
         * coordinator lives in `:player:xr`, which the Wear host library must not
         * depend on.
         */
        val onVoiceCommand: (suspend (transcript: String) -> String)? = null,
    )

    private val _activeSession = MutableStateFlow<ActiveSession?>(null)
    val activeSession: StateFlow<ActiveSession?> = _activeSession.asStateFlow()

    private val _nowPlayingState = MutableStateFlow<WearNowPlayingState?>(null)
    val nowPlayingState: StateFlow<WearNowPlayingState?> = _nowPlayingState.asStateFlow()

    /** Bind from composition (main thread). */
    fun bind(session: ActiveSession) {
        Timber.d("ActivePlayerSessionHolder: binding session %s", session.id)
        _activeSession.value = session
        rebuildNowPlayingState()
    }

    fun unbind(session: ActiveSession) {
        if (_activeSession.value?.id == session.id) {
            Timber.d("ActivePlayerSessionHolder: unbinding session %s", session.id)
            _activeSession.value = null
            _nowPlayingState.value = null
        }
    }

    fun unbindById(sessionId: String) {
        if (_activeSession.value?.id == sessionId) {
            _activeSession.value = null
            _nowPlayingState.value = null
        }
    }

    /**
     * Re-read the live player and republish [nowPlayingState]. Safe to call from any
     * dispatcher; hops to main because the providers touch the Media3 `Player`.
     */
    suspend fun refresh() {
        withContext(Dispatchers.Main.immediate) { rebuildNowPlayingState() }
    }

    private fun rebuildNowPlayingState() {
        val session = _activeSession.value ?: run {
            _nowPlayingState.value = null
            return
        }
        val snapshot = runCatching { session.snapshotProvider() }.getOrElse {
            Timber.w(it, "ActivePlayerSessionHolder: snapshot provider failed")
            return
        }
        val volume = session.volumeProvider?.invoke() ?: 1.0f
        val speed = session.speedProvider?.invoke() ?: 1.0f
        val streamUrl = session.streamUrlProvider?.invoke()
        val container = session.mediaContainerProvider?.invoke()
        val itemId = session.currentItemIdProvider?.invoke()

        val audioTracks = snapshot.audioTrackNames.mapIndexed { idx, name ->
            WearStreamInfo(
                index = idx,
                name = name,
                language = if (name == snapshot.currentAudioTrack) snapshot.currentAudioLanguageCode else null,
                isSelected = name == snapshot.currentAudioTrack,
            )
        }

        val subtitleTracks = snapshot.subtitleTrackNames.mapIndexed { idx, name ->
            WearStreamInfo(
                index = idx,
                name = name,
                language = if (name == snapshot.currentSubtitleTrack) snapshot.currentSubtitleLanguageCode else null,
                isSelected = name == snapshot.currentSubtitleTrack,
            )
        }

        // Real chapter offsets when the player supplies them. PlayerStateSnapshot
        // carries names only, so without this seam the watch would have to guess —
        // and a guessed offset means every chapter tap seeks to the wrong place.
        val chapters = session.chaptersProvider?.invoke()?.map { (name, startMs) ->
            WearChapterInfo(name = name, startPositionSeconds = startMs / 1000L)
        } ?: emptyList()

        _nowPlayingState.value = WearNowPlayingState(
            isPlaying = snapshot.isPlaying,
            positionSeconds = snapshot.positionSeconds,
            durationSeconds = snapshot.durationSeconds,
            title = snapshot.currentItemTitle,
            overview = snapshot.currentOverview,
            seriesName = snapshot.currentSeriesName,
            seasonNumber = snapshot.currentSeasonNumber,
            episodeNumber = snapshot.currentEpisodeNumber,
            segmentType = snapshot.currentSegmentType,
            currentChapterName = snapshot.currentChapterName,
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks,
            chapters = chapters,
            currentAudioTrack = snapshot.currentAudioTrack,
            currentSubtitleTrack = snapshot.currentSubtitleTrack,
            currentAudioLanguageCode = snapshot.currentAudioLanguageCode,
            currentSubtitleLanguageCode = snapshot.currentSubtitleLanguageCode,
            volume = volume,
            speed = speed,
            targetDeviceName = android.os.Build.MODEL ?: "SpatialFin",
            streamUrl = streamUrl,
            mediaContainer = container,
            itemId = itemId,
            timestampEpochMs = System.currentTimeMillis(),
        )
    }

    /** Run a natural-language command through the active form factor's voice pipeline. */
    suspend fun dispatchVoiceCommand(transcript: String): String {
        val handler = _activeSession.value?.onVoiceCommand
            ?: return "No active player session"
        return withContext(Dispatchers.Main.immediate) { handler(transcript) }
    }

    suspend fun dispatch(action: WearPlayerAction): String = withContext(Dispatchers.Main.immediate) {
        Timber.d("ActivePlayerSessionHolder: dispatching WearPlayerAction %s", action)
        val session = _activeSession.value

        if (action is WearPlayerAction.PlayMediaItem) {
            val handler = session?.onPlayMediaItem
            if (handler != null) {
                return@withContext handler(action.itemId, action.mediaType, action.startPositionMs)
            }
            return@withContext "Open SpatialFin on the target device to start playback"
        }

        if (session == null) {
            return@withContext when (action) {
                is WearPlayerAction.Play,
                is WearPlayerAction.TogglePlayPause,
                is WearPlayerAction.Pause,
                -> "Nothing is playing"
                is WearPlayerAction.MusicPlayPause,
                is WearPlayerAction.MusicPause,
                is WearPlayerAction.MusicResume,
                is WearPlayerAction.MusicNext,
                is WearPlayerAction.MusicPrevious,
                is WearPlayerAction.MusicAdjustVolume,
                -> "Music player not active"
                else -> "No active player session"
            }
        }

        val xrAction: XrPlayerAction = when (action) {
            is WearPlayerAction.Play -> XrPlayerAction.Play
            is WearPlayerAction.Pause -> XrPlayerAction.Pause
            is WearPlayerAction.TogglePlayPause -> XrPlayerAction.TogglePlayPause
            is WearPlayerAction.SeekForward -> XrPlayerAction.SeekForward(action.seconds)
            is WearPlayerAction.SeekBackward -> XrPlayerAction.SeekBackward(action.seconds)
            is WearPlayerAction.SeekTo -> XrPlayerAction.SeekTo(action.positionSeconds)
            is WearPlayerAction.SkipIntro -> XrPlayerAction.SkipIntro
            is WearPlayerAction.SkipOutro -> XrPlayerAction.SkipOutro
            is WearPlayerAction.NextEpisode -> XrPlayerAction.NextEpisode
            is WearPlayerAction.PreviousEpisode -> XrPlayerAction.PreviousEpisode
            is WearPlayerAction.SetSpeed -> XrPlayerAction.SetSpeed(action.speed)
            is WearPlayerAction.SelectAudioTrack -> XrPlayerAction.SelectAudioTrack(action.language, action.index)
            is WearPlayerAction.SelectSubtitleTrack -> XrPlayerAction.SelectSubtitleTrack(action.language, action.index)
            is WearPlayerAction.DisableSubtitles -> XrPlayerAction.DisableSubtitles
            is WearPlayerAction.AdjustVolume -> XrPlayerAction.AdjustVolume(action.percentage, action.delta)
            is WearPlayerAction.AdjustScale -> XrPlayerAction.AdjustScale(action.delta, action.reset)
            is WearPlayerAction.AdjustDistance -> XrPlayerAction.AdjustDistance(action.delta, action.reset)
            is WearPlayerAction.ResetScreenPlacement -> XrPlayerAction.ResetScreenPlacement
            is WearPlayerAction.GoHome -> XrPlayerAction.GoHome
            is WearPlayerAction.CloseApp -> XrPlayerAction.CloseApp
            is WearPlayerAction.GoBack -> XrPlayerAction.GoBack
            is WearPlayerAction.CastToFCastReceiver -> XrPlayerAction.CastToFCastReceiver(action.name, action.host, action.port)
            is WearPlayerAction.StopFCastCasting -> XrPlayerAction.StopFCastCasting
            is WearPlayerAction.MusicPlayPause -> XrPlayerAction.MusicPlayPause
            is WearPlayerAction.MusicPause -> XrPlayerAction.MusicPause
            is WearPlayerAction.MusicResume -> XrPlayerAction.MusicResume
            is WearPlayerAction.MusicNext -> XrPlayerAction.MusicNext
            is WearPlayerAction.MusicPrevious -> XrPlayerAction.MusicPrevious
            is WearPlayerAction.MusicAdjustVolume -> XrPlayerAction.MusicAdjustVolume(action.percentage, action.delta)
            is WearPlayerAction.PlayMediaItem -> error("handled above")
            is WearPlayerAction.Unrecognized -> XrPlayerAction.Unrecognized(action.raw)
        }

        val result = session.controller.dispatch(xrAction)
        rebuildNowPlayingState()
        result
    }
}
