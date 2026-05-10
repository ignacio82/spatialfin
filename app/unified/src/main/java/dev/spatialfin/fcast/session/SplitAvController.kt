package dev.spatialfin.fcast.session

import dev.jdtech.jellyfin.fcast.protocol.PlayMessage
import dev.jdtech.jellyfin.fcast.protocol.PlaybackState
import dev.jdtech.jellyfin.fcast.protocol.SplitAvMetadata
import dev.jdtech.jellyfin.fcast.protocol.SplitAvRole
import dev.jdtech.jellyfin.fcast.protocol.withSplitAv
import dev.jdtech.jellyfin.fcast.sender.FCastCastingController
import dev.jdtech.jellyfin.fcast.sender.FCastReceiver
import dev.jdtech.jellyfin.player.core.splitav.SplitAvVideoBridge
import dev.jdtech.jellyfin.player.core.splitav.SplitAvVideoMaster
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Orchestrator for split-A/V playback. The XR sender owns this; it sends a [PlayMessage] tagged
 * with [SplitAvRole.AUDIO] to the picked TV receiver, observes the receiver's PlaybackUpdate
 * beacons (the audio clock), and applies drift correction to the local [SplitAvVideoMaster]
 * (XR-side video).
 *
 * Lifecycle:
 *  - [start] tells the TV to play and primes the local-master listener loop.
 *  - The local player Activity (`XrPlayerActivity` etc.) registers itself with
 *    [SplitAvVideoBridge] as soon as its ExoPlayer is ready.
 *  - The drift loop runs only while both: a beacon has been received recently AND a master is
 *    bound. If the master goes away (Activity destroyed) the loop pauses without tearing down
 *    the FCast cast — the user can come back and the audio keeps playing on TV.
 *  - [stop] sends Stop to the TV and clears state.
 *
 * Concurrency: a single mutex serializes start/stop. The drift loop runs on a per-session
 * coroutine that is cancelled on stop.
 */
@Singleton
class SplitAvController @Inject constructor(
    private val castingController: FCastCastingController,
) {

    enum class State {
        Idle,
        Connecting,
        AwaitingMaster,
        Playing,
        Degraded,
        Stopped,
    }

    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _lastDriftMs = MutableStateFlow<Long?>(null)
    val lastDriftMs: StateFlow<Long?> = _lastDriftMs.asStateFlow()

    private val seekLimiter = HardSeekRateLimiter()
    private val networkDelay = NetworkDelayEstimator()

    private var sessionJob: Job? = null
    private var nudgeRevertJob: Job? = null
    private var cachedAudioLatencyMs: Int = 0
    private var sessionReceiver: FCastReceiver? = null

    /**
     * Begin a split-A/V session. The Play message is augmented with `splitAv.role=AUDIO` so the
     * remote receiver enters audio-only mode. The local video master may bind any time after
     * this returns — the drift loop will idle until it does.
     *
     * @param audioLatencyMs Calibrated AVR/soundbar tail latency. Pass 0 for "uncalibrated"
     * (split mode will work but lipsync will be off by the AVR delay, typically 30–200ms).
     */
    suspend fun start(
        receiver: FCastReceiver,
        play: PlayMessage,
        audioLatencyMs: Int = 0,
    ) = mutex.withLock {
        stopInternal()
        _state.value = State.Connecting

        val tagged = play.withSplitAv(
            SplitAvMetadata(
                role = SplitAvRole.AUDIO,
                syncCadenceHz = SplitAvMetadata.DEFAULT_SYNC_CADENCE_HZ,
            ),
        )
        try {
            castingController.startCast(receiver, tagged)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Split-A/V startCast failed")
            _state.value = State.Idle
            throw e
        }
        cachedAudioLatencyMs = audioLatencyMs.coerceAtLeast(0)
        sessionReceiver = receiver
        seekLimiter.reset()
        networkDelay.reset()
        _lastDriftMs.value = null
        _state.value = State.AwaitingMaster
        sessionJob = scope.launch { runSession() }
        Timber.tag(TAG).i(
            "Split-A/V started receiver=%s:%d audioLatencyMs=%d",
            receiver.host,
            receiver.port,
            cachedAudioLatencyMs,
        )
    }

    /** Cascade a pause from the user/UI to both ends. */
    suspend fun pause() {
        if (_state.value !in ACTIVE_STATES) return
        try {
            castingController.pause()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "pause cascade to TV failed")
        }
        SplitAvVideoBridge.activeMaster.value?.pauseFromMaster()
    }

    suspend fun resume() {
        if (_state.value !in ACTIVE_STATES) return
        try {
            castingController.resume()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "resume cascade to TV failed")
        }
        SplitAvVideoBridge.activeMaster.value?.resumeFromMaster()
    }

    suspend fun stop() = mutex.withLock {
        stopInternal()
    }

    private suspend fun stopInternal() {
        sessionJob?.cancel()
        sessionJob = null
        nudgeRevertJob?.cancel()
        nudgeRevertJob = null
        if (_state.value != State.Idle) {
            try {
                castingController.stopCast()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "stopCast failed")
            }
            SplitAvVideoBridge.activeMaster.value?.stopFromMaster()
        }
        _state.value = State.Idle
        _lastDriftMs.value = null
        sessionReceiver = null
    }

    /**
     * Per-session loop. Combines the master-binding state with each PlaybackUpdate beacon
     * coming back from the TV and runs drift correction. Self-cancels via [scope] cancellation
     * when the controller stops.
     */
    private suspend fun runSession() {
        SplitAvVideoBridge.activeMaster.collectLatest { master ->
            if (master == null) {
                _state.value = State.AwaitingMaster
                return@collectLatest
            }
            // First-bind housekeeping: ensure the master mutes audio.
            master.setAudioMuted(true)
            _state.value = State.Playing
            castingController.remoteState
                .filterNotNull()
                .collectLatest { update -> handleBeacon(master, update) }
        }
    }

    private suspend fun handleBeacon(
        master: SplitAvVideoMaster,
        update: dev.jdtech.jellyfin.fcast.protocol.PlaybackUpdateMessage,
    ) {
        val now = System.currentTimeMillis()
        val tvIsPlaying = update.playbackState == PlaybackState.Playing
        val state = SplitAvPolicy.BeaconState(
            beaconStreamPositionMs = ((update.time ?: 0.0) * 1_000.0).toLong(),
            beaconReceivedWallMs = now,
            xrPositionMs = master.currentPositionMs(),
            nowWallMs = now,
            audioLatencyMs = cachedAudioLatencyMs,
            networkOneWayMs = networkDelay.oneWayMs() ?: 0,
            tvIsPlaying = tvIsPlaying,
        )
        when (val action = SplitAvPolicy.decide(state)) {
            is SplitAvPolicy.DriftAction.Hold -> {
                _lastDriftMs.value = master.currentPositionMs() - SplitAvPolicy.expectedXrPositionMs(state)
            }
            is SplitAvPolicy.DriftAction.NudgeSpeed -> {
                _lastDriftMs.value = action.driftMs
                applyNudge(master, action.factor)
            }
            is SplitAvPolicy.DriftAction.HardSeek -> {
                _lastDriftMs.value = action.driftMs
                if (seekLimiter.wouldDegrade(now)) {
                    Timber.tag(TAG).w(
                        "Split-A/V drift cap exceeded (%d in %dms) — degrading to single-renderer",
                        seekLimiter.count(now),
                        HardSeekRateLimiter.DEFAULT_WINDOW_MS,
                    )
                    _state.value = State.Degraded
                    return
                }
                seekLimiter.record(now)
                master.seekTo(action.toPositionMs)
            }
            SplitAvPolicy.DriftAction.TvNotPlaying -> {
                // Cascade pause on the local side so user sees consistent state.
                master.pauseFromMaster()
            }
        }
    }

    private fun applyNudge(master: SplitAvVideoMaster, factor: Float) {
        nudgeRevertJob?.cancel()
        master.setPlaybackSpeed(factor)
        nudgeRevertJob = scope.launch {
            delay(SplitAvPolicy.NUDGE_DURATION_MS)
            if (isActive) master.setPlaybackSpeed(1.0f)
        }
    }

    /** Tear down forever. Wires up to process shutdown. */
    fun shutdown() {
        scope.cancel()
    }

    /**
     * Build a Play message that already carries the audio role flag. Provided as a convenience
     * for callers that want to construct the message themselves (e.g. an in-player cast button)
     * before passing it to [start].
     */
    fun tagAsAudio(play: PlayMessage): PlayMessage = play.withSplitAv(
        SplitAvMetadata(
            role = SplitAvRole.AUDIO,
            syncCadenceHz = SplitAvMetadata.DEFAULT_SYNC_CADENCE_HZ,
        ),
    )

    private companion object {
        const val TAG = "SplitAvController"

        /** True while the controller is between Connecting and Stopped. */
        val ACTIVE_STATES: Set<State> = setOf(
            State.Connecting, State.AwaitingMaster, State.Playing, State.Degraded,
        )
    }
}
