package dev.spatialfin.fcast.session

import dev.jdtech.jellyfin.fcast.protocol.PlayMessage
import dev.jdtech.jellyfin.fcast.protocol.PlaybackState
import dev.jdtech.jellyfin.fcast.protocol.SplitAvMetadata
import dev.jdtech.jellyfin.fcast.protocol.SplitAvRole
import dev.jdtech.jellyfin.fcast.protocol.withSplitAv
import dev.jdtech.jellyfin.cast.toCastReceiver
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
    private var currentNudgeFactor: Float? = null
    private var cachedAudioLatencyMs: Int = 0
    private var sessionReceiver: FCastReceiver? = null

    /**
     * Absolute media-time offset (ms) the receiver's stream begins at. When we ask Jellyfin to
     * serve a transcoded stream with `startTimeTicks=`, the server emits a *new* timeline that
     * begins at byte 0 → the Pixel's PlaybackUpdate beacons report `time=0+elapsed`, not the
     * user-visible absolute media position. Add this offset to beacon times before feeding
     * them to the drift policy so XR's master position (which IS absolute) and the beacon
     * position end up on the same clock.
     */
    private var mediaStartOffsetMs: Long = 0L

    /**
     * Have we seen the receiver report `state=Playing` at least once during this session?
     * Until we have, we ignore TvNotPlaying beacons — at startup the TV reports Paused (it
     * hasn't loaded yet) which would otherwise feed back through pauseFromMaster() and pause
     * the XR master, which the pause-mirror then cascades back to the TV → deadlock where
     * both ends stay paused forever. Once the TV has confirmed it actually started playing,
     * a subsequent TvNotPlaying is a real pause and we cascade it.
     */
    private var hasObservedTvPlaying: Boolean = false

    /**
     * Wall-clock at which the local master first transitioned to playWhenReady=true. Used as
     * the start of the "warmup grace period" — for [WARMUP_GRACE_MS] after this, drift
     * correction is suppressed because the master is still buffering and the receiver is
     * already several seconds ahead; firing hard seeks during this window just yanks the XR
     * forward into another buffer cycle.
     *
     * Null until master goes playWhenReady=true for the first time this session.
     */
    private var masterFirstPlayingWallMs: Long? = null

    /**
     * The previous beacon's `tvIsPlaying` value. Used to make the TvNotPlaying cascade
     * edge-triggered: only call `pauseFromMaster` on the transition Playing→Paused, not on
     * every Paused beacon. Without this gate the controller dispatches `pauseFromMaster` at
     * 10 Hz forever once the receiver pauses, which spams the bridge IPC and (more importantly)
     * keeps re-pausing the master if the user tries to resume.
     */
    private var lastBeaconTvIsPlaying: Boolean? = null

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
        mediaStartOffsetMs: Long = 0L,
    ) = mutex.withLock {
        // Defense in depth: SplitAv is meaningless without an FCast receiver (no commanded
        // start, no calibration side-channel, no sub-frame clock telemetry). The session
        // manager already filters this, but a stray caller into the controller should fail
        // loud rather than spin up a half-broken split session.
        val castReceiver = receiver.toCastReceiver()
        if (!SplitAvPolicy.isAvailable(castReceiver)) {
            Timber.tag(TAG).w(
                "start: rejecting receiver %s:%d — SplitAv capability missing",
                receiver.host, receiver.port,
            )
            throw SplitAvUnsupportedException(castReceiver)
        }
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
        this.mediaStartOffsetMs = mediaStartOffsetMs.coerceAtLeast(0L)
        hasObservedTvPlaying = false
        masterFirstPlayingWallMs = null
        lastBeaconTvIsPlaying = null
        sessionReceiver = receiver
        SplitAvSessionRegistry.setActive(castReceiver)
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

    /**
     * Master-initiated session end. Symmetric counterpart of [endByReceiver]: the XR user
     * asked to fold audio back to the headset (via the in-player cast picker's
     * "Stop split-A/V" action). Tells the receiver to stop its audio overlay, then ends the
     * session locally without stopping the master player.
     */
    suspend fun endFromMaster() {
        try {
            castingController.stopCast()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "endFromMaster: stopCast to receiver failed (continuing)")
        }
        endByReceiver()
    }

    /**
     * Receiver-initiated session end. The Pixel's audio overlay's Stop button (or any other
     * end-of-stream signal from the receiver) lands here. Unmutes the local master so the
     * user keeps hearing the movie on the XR, but does *not* stop the master player — the
     * user wanted to fold audio back home, not exit playback entirely.
     *
     * Distinct from [stop]:
     *  - [stop] tears everything down (used when the master Activity exits): receiver stops,
     *    master stops.
     *  - [endByReceiver] only ends the controller's drift loop + unmutes the master. The
     *    master keeps its current play/pause state and the user continues with the local
     *    player.
     */
    suspend fun endByReceiver() = mutex.withLock {
        if (_state.value !in ACTIVE_STATES) return@withLock
        sessionJob?.cancel()
        sessionJob = null
        nudgeRevertJob?.cancel()
        nudgeRevertJob = null
        SplitAvVideoBridge.activeMaster.value?.setAudioMuted(false)
        _state.value = State.Idle
        _lastDriftMs.value = null
        sessionReceiver = null
        SplitAvSessionRegistry.setActive(null)
        Timber.tag(TAG).i("Split-A/V ended by receiver — master unmuted, drift loop stopped")
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
        SplitAvSessionRegistry.setActive(null)
    }

    /**
     * Per-session loop. Combines the master-binding state with each PlaybackUpdate beacon
     * coming back from the TV and runs drift correction. Self-cancels via [scope] cancellation
     * when the controller stops.
     *
     * Two side jobs run alongside: a Ping/Pong loop for RTT estimation (feeds
     * [networkDelay] so the policy's `networkOneWayMs` term is non-zero), and a Pong observer
     * that records each observation. Both stop when the session ends.
     */
    private suspend fun runSession() {
        scope.launch { runPingLoop() }
        scope.launch { runPongCollector() }
        SplitAvVideoBridge.activeMaster.collectLatest { master ->
            if (master == null) {
                _state.value = State.AwaitingMaster
                return@collectLatest
            }
            // First-bind housekeeping: ensure the master mutes audio.
            master.setAudioMuted(true)
            _state.value = State.Playing
            // Mirror the local video master's play/pause state to the audio receiver so a user
            // pause on the XR also pauses the Pixel audio (and resume resumes both). Without
            // this the receiver keeps playing while the local video is frozen and the gap
            // grows unbounded.
            val pauseMirror = scope.launch { mirrorMasterPlayState(master) }
            val seekMirror = scope.launch { mirrorMasterSeeks(master) }
            try {
                castingController.remoteState
                    .filterNotNull()
                    .collectLatest { update -> handleBeacon(master, update) }
            } finally {
                pauseMirror.cancel()
                seekMirror.cancel()
            }
        }
    }

    /**
     * Track the local master's `isPlaying` flow and call [FCastCastingController.pause] /
     * [FCastCastingController.resume] when the user toggles. Only cascades genuine *transitions*
     * — the initial value is seeded as `lastSent` and not sent.
     *
     * Why we skip the initial value: at session bootstrap the master starts
     * `playWhenReady=false` (still buffering / not started). Cascading that to the receiver
     * right after we just sent a Play frame would immediately re-pause the Pixel audio — the
     * Pixel hears "play this stream", then 100 ms later hears "pause", and never recovers
     * because the master is also paused. Once the master goes true→false→true the cascade
     * works correctly.
     *
     * Also stamps [masterFirstPlayingWallMs] on the first true so the drift-correction warmup
     * window can be measured from the moment the user actually pressed play.
     *
     * On the *first* playing=true transition, also pre-aligns the receiver: the cast pipeline
     * was told [mediaStartOffsetMs] at session start, but the local XR master may have resumed
     * at a different position (e.g. fresh Jellyfin lookup vs stale `playbackPositionTicks` in
     * the calling Compose surface, or saved-progress vs start-from-zero). Send a Seek frame so
     * the receiver lands at the master's actual position before the Resume — otherwise drift
     * policy will see a huge initial gap and yank the master backwards, throwing away the
     * user's resume position.
     */
    private suspend fun mirrorMasterPlayState(master: SplitAvVideoMaster) {
        // We **don't** seed lastSent from `master.isPlaying.value` and skip the initial value
        // anymore. Earlier the seed-and-skip pattern prevented an "I just sent Play, now I'm
        // re-pausing" double-fire when the master started paused — fine when the master always
        // booted at playWhenReady=false. But XrPlayerActivity (and BeamPlayerActivity) auto-play
        // the moment the player is built, so by the time SplitAvVideoBridge binds and this
        // collector runs, master.isPlaying.value is *already true*. Seeding lastSent = true and
        // bailing on the first equal emission silently swallowed the first-play seek+resume, so
        // the receiver loaded the URL with playWhenReady=false and never got the Resume frame.
        // Result on the user's report: video played, audio never started.
        //
        // The fix: start lastSent = null and always cascade the first emission. The downside of
        // the original race (receiver gets re-paused immediately after Play) is gone because the
        // receiver explicitly loads with playWhenReady=false (FCastInboundPlayerActivity sets it
        // in the SplitAvRole.AUDIO branch). A redundant Pause to an already-paused receiver is a
        // no-op; the controller's pause-mirror only matters once the receiver has started
        // playing, which can only happen after our explicit Resume below.
        var lastSent: Boolean? = null
        Timber.tag(TAG).i(
            "mirrorMasterPlayState: starting (initial master.isPlaying=%b)",
            master.isPlaying.value,
        )
        var aligned = false
        master.isPlaying.collect { playing ->
            if (playing == lastSent) return@collect
            lastSent = playing
            if (playing && masterFirstPlayingWallMs == null) {
                masterFirstPlayingWallMs = System.currentTimeMillis()
                Timber.tag(TAG).i("master first playWhenReady=true — warmup grace begins")
            }
            if (playing && !aligned) {
                aligned = true
                val masterPos = master.currentPositionMs()
                val expectedReceiverStreamPos = (masterPos - mediaStartOffsetMs).coerceAtLeast(0L)
                Timber.tag(TAG).i(
                    "first play: master=%dms mediaStartOffset=%dms → align receiver to stream pos %dms",
                    masterPos, mediaStartOffsetMs, expectedReceiverStreamPos,
                )
                try {
                    castingController.seek(expectedReceiverStreamPos / 1000.0)
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "first-play receiver seek failed")
                }
            }
            Timber.tag(TAG).i("mirror master state: playing=%b — cascading to receiver", playing)
            try {
                if (playing) castingController.resume() else castingController.pause()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "mirror play state to receiver failed (playing=%b)", playing)
            }
        }
    }

    /**
     * Cascade user-initiated seeks (fast-forward, rewind, scrub-bar drag, ±10 s buttons) on
     * the master to the audio receiver so the audio track follows the video. Without this the
     * receiver keeps playing where it was while the master jumped, and the drift policy can't
     * catch up because the gap is intentional rather than accidental.
     *
     * Programmatic seeks issued by [SplitAvVideoMaster.seekTo] (drift-correction hard seeks)
     * are *not* forwarded — see [PlayerSplitAvAdapter]'s `suppressNextUserSeekEmit` gate. If
     * we cascaded those, the drift correction would feed back on itself: master jumps to align
     * with receiver, cascade forwards the jump back to the receiver, receiver jumps again,
     * etc.
     *
     * Also bumps [masterFirstPlayingWallMs] forward by the warmup grace so the drift policy
     * doesn't hard-seek the master back to its old position during the post-seek buffering
     * window — receiver typically takes 1-2 s to refill after a seek, during which beacons
     * still report the *old* stream position.
     */
    private suspend fun mirrorMasterSeeks(master: SplitAvVideoMaster) {
        master.userSeeks.collect { masterPosMs ->
            val receiverStreamPosMs = (masterPosMs - mediaStartOffsetMs).coerceAtLeast(0L)
            Timber.tag(TAG).i(
                "user seek: master=%dms mediaStartOffset=%dms → receiver stream pos %dms",
                masterPosMs, mediaStartOffsetMs, receiverStreamPosMs,
            )
            runCatching { castingController.seek(receiverStreamPosMs / 1000.0) }
                .onFailure { Timber.tag(TAG).w(it, "user-seek cascade to receiver failed") }
            // Reset the warmup window so drift correction doesn't punish the receiver's
            // post-seek buffer-fill (which can take a second or two on a slow LAN). Without
            // this the policy sees the gap and hard-seeks the master back to where the
            // receiver was, undoing the user's scrub.
            masterFirstPlayingWallMs = System.currentTimeMillis()
        }
    }

    private suspend fun runPingLoop() {
        // Send a Ping every PING_INTERVAL_MS so we get a steady stream of RTT samples.
        // Cadence is low — once every ~2 s — because RTT on a stable LAN doesn't change
        // fast and we don't want to flood the receiver's reader thread.
        //
        // Failure handling: when the receiver's TCP closes (Wi-Fi flap, screen-off doze on the
        // Pixel, sender process killed) every ping throws IOException. Logging each failure
        // floods logcat at 0.5 Hz forever. After [PING_GIVEUP_FAILURES] consecutive failures
        // we declare the session degraded and exit the loop — the user has to dismiss + re-pick
        // a receiver to re-establish the cast.
        var consecutiveFailures = 0
        while (true) {
            delay(PING_INTERVAL_MS)
            try {
                castingController.ping()
                consecutiveFailures = 0
            } catch (e: Exception) {
                consecutiveFailures++
                if (consecutiveFailures == 1) {
                    Timber.tag(TAG).w(e, "split-A/V ping failed; will retry next interval")
                }
                if (consecutiveFailures >= PING_GIVEUP_FAILURES) {
                    Timber.tag(TAG).w(
                        "split-A/V ping has failed %d times in a row — receiver appears dead, " +
                            "marking session degraded and stopping the ping loop",
                        consecutiveFailures,
                    )
                    _state.value = State.Degraded
                    return
                }
            }
        }
    }

    private suspend fun runPongCollector() {
        castingController.pongs.collect { obs ->
            networkDelay.recordRtt(obs.rttMs)
        }
    }

    private fun clearNudge(master: SplitAvVideoMaster) {
        if (currentNudgeFactor != null) {
            nudgeRevertJob?.cancel()
            master.setPlaybackSpeed(1.0f)
            currentNudgeFactor = null
        }
    }

    private suspend fun handleBeacon(
        master: SplitAvVideoMaster,
        update: dev.jdtech.jellyfin.fcast.protocol.PlaybackUpdateMessage,
    ) {
        val now = System.currentTimeMillis()
        val tvIsPlaying = update.playbackState == PlaybackState.Playing
        if (tvIsPlaying) hasObservedTvPlaying = true
        val prevTvIsPlaying = lastBeaconTvIsPlaying
        lastBeaconTvIsPlaying = tvIsPlaying

        // Receiver finished (user pressed Stop on the Pixel's audio overlay, or its Activity
        // was destroyed). Hand audio back to the local master — unmute it so the user keeps
        // hearing the movie on the XR — and tear down the split-A/V session without stopping
        // the master player. The user's expectation: "if I stop the audio split, audio should
        // come from the same device as the video."
        if (update.playbackState == PlaybackState.Idle && hasObservedTvPlaying) {
            Timber.tag(TAG).i("Receiver reported Idle after Playing — ending split-A/V, unmuting master")
            scope.launch { endByReceiver() }
            return
        }

        // Warmup gate: suppress drift correction until the master has been playing for a
        // grace period. At startup the receiver is several seconds ahead of the master
        // (Pixel decodes the audio chunk while XR is still buffering the video) — firing
        // hard seeks here just yanks the XR forward into a second buffer cycle and pauses
        // playback. The grace period lets the master catch up to the steady state where the
        // policy can do real work with small drifts.
        val firstPlayMs = masterFirstPlayingWallMs
        if (firstPlayMs == null || now - firstPlayMs < WARMUP_GRACE_MS) {
            return
        }

        val state = SplitAvPolicy.BeaconState(
            // Receiver reports time inside *its* stream (which begins at mediaStartOffsetMs
            // when Jellyfin transcodes via startTimeTicks); add the offset to land back on the
            // absolute media clock the XR master uses.
            beaconStreamPositionMs = ((update.time ?: 0.0) * 1_000.0).toLong() + mediaStartOffsetMs,
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
                clearNudge(master)
            }
            is SplitAvPolicy.DriftAction.NudgeSpeed -> {
                _lastDriftMs.value = action.driftMs
                Timber.tag(TAG).i("drift=%dms — speed nudge factor=%.2f", action.driftMs, action.factor)
                applyNudge(master, action.factor)
            }
            is SplitAvPolicy.DriftAction.HardSeek -> {
                _lastDriftMs.value = action.driftMs
                clearNudge(master)
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
                Timber.tag(TAG).i("drift=%dms — hard seek to %dms", action.driftMs, action.toPositionMs)
                master.seekTo(action.toPositionMs)
            }
            SplitAvPolicy.DriftAction.TvNotPlaying -> {
                clearNudge(master)
                // Only cascade the receiver's "I'm paused" back to the master on the *edge*
                // (Playing→Paused transition), not on every Paused beacon. Without this gate
                // the controller fires `pauseFromMaster` at 10 Hz forever once the receiver
                // pauses — which spams the bridge IPC and prevents the user from ever
                // resuming, since each beacon re-pauses the master.
                //
                // Also requires hasObservedTvPlaying so the bootstrap-time Paused beacon (TV
                // hasn't loaded yet) doesn't propagate into the master.
                val transitioned = prevTvIsPlaying == true
                if (hasObservedTvPlaying && transitioned) {
                    Timber.tag("SplitAvPauseTrace").w(
                        "TV→Paused transition — cascading pause to master. " +
                            "tvIsPlaying=%b prevTvIsPlaying=%b hasObservedTvPlaying=%b " +
                            "masterPos=%dms beaconStreamPos=%dms",
                        tvIsPlaying, prevTvIsPlaying, hasObservedTvPlaying,
                        state.xrPositionMs, state.beaconStreamPositionMs,
                    )
                    master.pauseFromMaster()
                }
            }
        }
    }

    private fun applyNudge(master: SplitAvVideoMaster, factor: Float) {
        nudgeRevertJob?.cancel()
        // Only call setPlaybackSpeed if the factor actually changed. ExoPlayer's
        // setPlaybackSpeed is expensive (re-evaluates renderers, may cause buffer flush);
        // beacons arrive at 10 Hz so a naive applyNudge would issue 10 redundant calls per
        // second while the drift stays in the nudge band. Re-arm the revert timer either way
        // so a sustained nudge holds the factor until the policy says Hold.
        if (currentNudgeFactor != factor) {
            master.setPlaybackSpeed(factor)
            currentNudgeFactor = factor
        }
        nudgeRevertJob = scope.launch {
            delay(SplitAvPolicy.NUDGE_DURATION_MS)
            if (isActive) {
                master.setPlaybackSpeed(1.0f)
                currentNudgeFactor = null
            }
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

        /** Cadence for RTT-probe Pings while in a split session. Low enough not to spam, high
         *  enough that NetworkDelayEstimator's smoothed RTT keeps up with link changes. */
        const val PING_INTERVAL_MS: Long = 2_000L

        /**
         * After this many consecutive ping failures we declare the session dead and stop
         * pinging. 5 × 2 s = 10 s of silence is well past any plausible transient Wi-Fi
         * stall. The user re-picks the receiver to recover.
         */
        const val PING_GIVEUP_FAILURES: Int = 5

        /**
         * Time after the master first transitions to playWhenReady=true during which beacons
         * are ignored for drift correction. The XR's master and the Pixel receiver bootstrap
         * with very different cadences (Pixel ships a Play frame to ExoPlayer with
         * `startTimeTicks` and decodes audio almost instantly; the XR has to fetch and decode
         * video at the same offset and is typically 1–3 s behind). Without this window the
         * controller fires HardSeek storms at startup, exhausts the seek-rate cap, and
         * degrades the session before steady-state is even reached.
         */
        // Suppresses drift correction for this long after the master's first playWhenReady=true.
        // At session bootstrap the receiver is several seconds *behind* the master rather than
        // ahead: the controller's first-play seek (mirrorMasterPlayState → castingController.seek)
        // jumps the receiver from the freshly-loaded byte 0 to the master's absolute position,
        // and on direct-play paths (where Jellyfin serves the raw container) that seek triggers
        // an HTTP byte-range fetch and a buffer refill before audio actually starts. On a slow
        // home Wi-Fi or a busy AVR that can easily run 5–6 s. Firing the drift policy inside
        // that window sees a multi-second gap, hard-seeks the master backward, and yanks the
        // user out of their resume position. 8 s leaves comfortable headroom while still being
        // short enough that genuine drift gets caught before the user notices lipsync skew.
        const val WARMUP_GRACE_MS: Long = 8_000L

        /** True while the controller is between Connecting and Stopped. */
        val ACTIVE_STATES: Set<State> = setOf(
            State.Connecting, State.AwaitingMaster, State.Playing, State.Degraded,
        )
    }
}
