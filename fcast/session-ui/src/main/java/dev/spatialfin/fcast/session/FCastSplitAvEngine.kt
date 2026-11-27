package dev.spatialfin.fcast.session

import android.os.SystemClock
import dev.jdtech.jellyfin.cast.toCastReceiver
import dev.jdtech.jellyfin.fcast.protocol.PlayMessage
import dev.jdtech.jellyfin.fcast.protocol.PlaybackState
import dev.jdtech.jellyfin.fcast.protocol.SplitAvMetadata
import dev.jdtech.jellyfin.fcast.protocol.SplitAvRole
import dev.jdtech.jellyfin.fcast.protocol.withSplitAv
import dev.jdtech.jellyfin.fcast.sender.FCastCastingController
import dev.jdtech.jellyfin.fcast.sender.FCastReceiver
import dev.jdtech.jellyfin.player.core.splitav.SplitAvVideoBridge
import dev.jdtech.jellyfin.player.core.splitav.SplitAvVideoMaster
import dev.jdtech.jellyfin.player.core.splitav.TheaterClientEvent
import dev.jdtech.jellyfin.player.core.splitav.TheaterSessionSnapshot
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Legacy FCast Split A/V engine preserving existing continuous drift estimation, PI speed-trim,
 * Ping/Pong RTT & clock-offset estimation, learned codec offsets, and CSV trace logging.
 */
internal class FCastSplitAvEngine(
    private val castingController: FCastCastingController,
    private val rememberedReceiversStore: RememberedReceiversStore,
) : SplitAvSessionEngine {

    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(SplitAvController.State.Idle)
    override val state: StateFlow<SplitAvController.State> = _state.asStateFlow()

    private val _lastDriftMs = MutableStateFlow<Long?>(null)
    override val lastDriftMs: StateFlow<Long?> = _lastDriftMs.asStateFlow()

    private val _theaterSnapshot = MutableStateFlow<TheaterSessionSnapshot?>(null)
    override val theaterSnapshot: StateFlow<TheaterSessionSnapshot?> = _theaterSnapshot.asStateFlow()

    private val seekLimiter = HardSeekRateLimiter()
    private val networkDelay = NetworkDelayEstimator()
    private val driftEstimator = DriftEstimator()
    private val freshnessGate = BeaconFreshnessGate()
    private val clockOffset = ClockOffsetEstimator()

    var clock: () -> Long = SystemClock::elapsedRealtime
    var traceSink: SplitAvTraceSink = LogcatSplitAvTraceSink

    private var sessionJob: Job? = null
    private val nudgeController = NudgeController()
    private var appliedSpeed: Float = 1.0f
    private var lastBeaconAtMs: Long? = null

    private var cachedAudioLatencyMs: Int = 0
    private var manualVideoOffsetMs: Int = 0
    private var learnedCodecCorrections: MutableMap<String, Int> = mutableMapOf()
    private var activeRouteFingerprint: String? = null
    private val codecLearner = LearnedCodecCorrectionTracker()
    private val sessionMetrics = SplitAvSessionMetrics()
    private var sessionReceiver: FCastReceiver? = null
    private var mediaStartOffsetMs: Long = 0L
    private var hasObservedTvPlaying: Boolean = false
    private var masterFirstPlayingWallMs: Long? = null
    private var lastBeaconTvIsPlaying: Boolean? = null

    @Volatile private var isMachinePaused: Boolean = false
    @Volatile private var isMachineResumed: Boolean = false

    suspend fun start(
        receiver: FCastReceiver,
        play: PlayMessage,
        audioLatencyMs: Int = 0,
        mediaStartOffsetMs: Long = 0L,
        manualVideoOffsetMs: Int = 0,
        learnedCodecCorrections: Map<String, Int> = emptyMap(),
        routeFingerprint: String? = null,
    ) = mutex.withLock {
        val castReceiver = receiver.toCastReceiver()
        if (!SplitAvPolicy.isAvailable(castReceiver)) {
            Timber.tag(TAG).w(
                "start: rejecting receiver %s:%d — SplitAv capability missing",
                receiver.host, receiver.port,
            )
            throw SplitAvUnsupportedException(castReceiver)
        }
        stopInternal()
        _state.value = SplitAvController.State.Connecting

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
            _state.value = SplitAvController.State.Idle
            throw e
        }
        cachedAudioLatencyMs = audioLatencyMs.coerceAtLeast(0)
        this.manualVideoOffsetMs = manualVideoOffsetMs.coerceIn(
            MANUAL_VIDEO_OFFSET_MIN_MS,
            MANUAL_VIDEO_OFFSET_MAX_MS,
        )
        this.learnedCodecCorrections = learnedCodecCorrections
            .mapKeys { AudioLatencyProfile.codecKey(it.key) }
            .mapValues {
                it.value.coerceIn(
                    -LearnedCodecCorrectionTracker.DEFAULT_MAX_ABS_CORRECTION_MS,
                    LearnedCodecCorrectionTracker.DEFAULT_MAX_ABS_CORRECTION_MS,
                )
            }
            .toMutableMap()
        activeRouteFingerprint = routeFingerprint
        this.mediaStartOffsetMs = mediaStartOffsetMs.coerceAtLeast(0L)
        hasObservedTvPlaying = false
        masterFirstPlayingWallMs = null
        lastBeaconTvIsPlaying = null
        sessionReceiver = receiver
        SplitAvSessionRegistry.setActive(castReceiver)
        seekLimiter.reset()
        networkDelay.reset()
        driftEstimator.reset()
        freshnessGate.reset()
        clockOffset.reset()
        codecLearner.reset()
        sessionMetrics.reset(clock())
        nudgeController.reset()
        appliedSpeed = 1.0f
        lastBeaconAtMs = null
        _lastDriftMs.value = null
        _state.value = SplitAvController.State.AwaitingMaster
        sessionJob = scope.launch { runSession() }
        Timber.tag(TAG).i(
            "Split-A/V started receiver=%s:%d audioLatencyMs=%d manualOffsetMs=%d route=%s",
            receiver.host,
            receiver.port,
            cachedAudioLatencyMs,
            this.manualVideoOffsetMs,
            activeRouteFingerprint ?: "unknown",
        )
    }

    fun setManualVideoOffset(offsetMs: Int) {
        manualVideoOffsetMs = offsetMs.coerceIn(
            MANUAL_VIDEO_OFFSET_MIN_MS,
            MANUAL_VIDEO_OFFSET_MAX_MS,
        )
    }

    fun updateRouteLatencyConfig(
        baseLatencyMs: Int?,
        manualVideoOffsetMs: Int?,
        learnedCodecCorrections: Map<String, Int>?,
        routeFingerprint: String?,
    ) {
        baseLatencyMs?.let { cachedAudioLatencyMs = it.coerceAtLeast(0) }
        manualVideoOffsetMs?.let { setManualVideoOffset(it) }
        learnedCodecCorrections?.let { corrections ->
            this.learnedCodecCorrections = corrections
                .mapKeys { AudioLatencyProfile.codecKey(it.key) }
                .mapValues {
                    it.value.coerceIn(
                        -LearnedCodecCorrectionTracker.DEFAULT_MAX_ABS_CORRECTION_MS,
                        LearnedCodecCorrectionTracker.DEFAULT_MAX_ABS_CORRECTION_MS,
                    )
                }
                .toMutableMap()
        }
        activeRouteFingerprint = routeFingerprint ?: activeRouteFingerprint
    }

    override suspend fun pause() {
        if (_state.value !in ACTIVE_STATES) return
        SplitAvVideoBridge.activeMaster.value?.let { master ->
            if (master.isPlaying.value) {
                isMachinePaused = true
                master.pauseFromMaster()
            }
        }
        try {
            castingController.pause()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "pause cascade to TV failed")
        }
    }

    override suspend fun resume() {
        if (_state.value !in ACTIVE_STATES) return
        SplitAvVideoBridge.activeMaster.value?.let { master ->
            if (!master.isPlaying.value) {
                isMachineResumed = true
                master.resumeFromMaster()
            }
        }
        try {
            castingController.resume()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "resume cascade to TV failed")
        }
    }

    override suspend fun seekTo(positionMs: Long) {
        if (_state.value !in ACTIVE_STATES) return
        val targetMs = positionMs.coerceAtLeast(0L)
        val receiverStreamPosMs = (targetMs - mediaStartOffsetMs).coerceAtLeast(0L)
        SplitAvVideoBridge.activeMaster.value?.seekTo(targetMs)
        try {
            castingController.seek(receiverStreamPosMs / 1000.0)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "seek cascade to TV failed")
        }
        masterFirstPlayingWallMs = clock()
        codecLearner.reset()
    }

    override fun currentMasterPositionMs(): Long? {
        if (_state.value !in ACTIVE_STATES) return null
        return SplitAvVideoBridge.activeMaster.value?.currentPositionMs()
    }

    override suspend fun stop(reason: String) = mutex.withLock {
        stopInternal()
    }

    override fun onMasterDisconnected(userExit: Boolean) {
        // Legacy FCast preserves audio on the receiver when local master detaches across
        // navigation; explicit exit stops via Stop action / Activity exit flow.
    }

    override suspend fun handleTheaterClientEvent(event: TheaterClientEvent) = Unit

    override suspend fun endFromMaster() {
        try {
            castingController.stopCast()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "endFromMaster: stopCast to receiver failed (continuing)")
        }
        endByReceiver()
    }

    override suspend fun endByReceiver() = mutex.withLock {
        if (_state.value !in ACTIVE_STATES) return@withLock
        sessionJob?.cancel()
        sessionJob = null
        SplitAvVideoBridge.activeMaster.value?.let {
            nudgeController.reset()
            it.setPlaybackSpeed(1.0f)
            it.setAudioMuted(false)
            it.foldBackToLocal()
        }
        _state.value = SplitAvController.State.Idle
        _lastDriftMs.value = null
        logSessionSummary("receiver-ended")
        sessionReceiver = null
        SplitAvSessionRegistry.setActive(null)
        Timber.tag(TAG).i("Split-A/V ended by receiver — master folded back to local audio")
    }

    private suspend fun stopInternal() {
        sessionJob?.cancel()
        sessionJob = null
        nudgeController.reset()
        appliedSpeed = 1.0f
        if (_state.value != SplitAvController.State.Idle) {
            try {
                castingController.stopCast()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "stopCast failed")
            }
            SplitAvVideoBridge.activeMaster.value?.stopFromMaster()
        }
        _state.value = SplitAvController.State.Idle
        _lastDriftMs.value = null
        logSessionSummary("stop")
        sessionReceiver = null
        SplitAvSessionRegistry.setActive(null)
    }

    private suspend fun runSession() = coroutineScope {
        launch { runPingLoop() }
        launch { runPongCollector() }
        SplitAvVideoBridge.activeMaster.collectLatest { master ->
            if (master == null) {
                _state.value = SplitAvController.State.AwaitingMaster
                return@collectLatest
            }
            master.setAudioMuted(true)
            _state.value = SplitAvController.State.Playing
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

    private suspend fun mirrorMasterPlayState(master: SplitAvVideoMaster): Unit = coroutineScope {
        var lastSent: Boolean? = null
        Timber.tag(TAG).i(
            "mirrorMasterPlayState: starting (initial master.isPlaying=%b)",
            master.isPlaying.value,
        )
        var aligned = false
        master.isPlaying.collect { playing ->
            if (playing == lastSent) return@collect
            lastSent = playing

            if (!playing && isMachinePaused) {
                Timber.tag(TAG).i("mirror master state: playing=false (machine-initiated) — skipping cascade")
                isMachinePaused = false
                return@collect
            }
            if (playing && isMachineResumed) {
                Timber.tag(TAG).i("mirror master state: playing=true (machine-initiated) — skipping cascade")
                isMachineResumed = false
                return@collect
            }

            if (playing && masterFirstPlayingWallMs == null) {
                masterFirstPlayingWallMs = clock()
                Timber.tag(TAG).i("master first playWhenReady=true — warmup grace begins")
            }
            var sentScheduledStart = false
            if (playing && !aligned) {
                var pausedForAlignedStart = false
                if (clockOffset.offsetMs() != null) {
                    isMachinePaused = true
                    master.pauseFromMaster()
                    pausedForAlignedStart = true
                }
                val offset = if (pausedForAlignedStart) awaitAlignedStartOffset() else null
                val masterPos = master.currentPositionMs()
                if (offset != null) {
                    val now = clock()
                    val t0 = now + ALIGNED_START_LEAD_MS
                    val recvStreamPos = (masterPos - mediaStartOffsetMs).coerceAtLeast(0L)
                    Timber.tag(TAG).i(
                        "first play (v4 two-sided): master=%dms θ=%dms T0=+%dms → recv stream %dms",
                        masterPos, offset, ALIGNED_START_LEAD_MS, recvStreamPos,
                    )
                    try {
                        castingController.seek(recvStreamPos / 1000.0)
                        castingController.resumeAt(t0 + offset)
                        sentScheduledStart = true
                        aligned = true
                        masterFirstPlayingWallMs = t0
                        launch {
                            delay((t0 - clock()).coerceAtLeast(0L))
                            if (_state.value in ACTIVE_STATES) {
                                isMachineResumed = true
                                master.resumeFromMaster()
                            }
                        }
                        launch {
                            delay(ALIGNED_START_LEAD_MS + ALIGNED_START_FALLBACK_GRACE_MS)
                            if (
                                _state.value in ACTIVE_STATES &&
                                master.isPlaying.value &&
                                !hasObservedTvPlaying
                            ) {
                                Timber.tag(TAG).w("v4 aligned start still not playing; sending resume fallback")
                                try {
                                    castingController.resume()
                                } catch (e: Exception) {
                                    Timber.tag(TAG).w(e, "resume fallback failed")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "v4 two-sided start failed — falling back to resume-now")
                    }
                }
                if (!sentScheduledStart) {
                    if (pausedForAlignedStart) {
                        isMachineResumed = true
                        master.resumeFromMaster()
                    }
                    val expectedReceiverStreamPos =
                        (masterPos - mediaStartOffsetMs).coerceAtLeast(0L)
                    Timber.tag(TAG).i(
                        "first play (legacy): master=%dms offset=%dms → align receiver to %dms",
                        masterPos, mediaStartOffsetMs, expectedReceiverStreamPos,
                    )
                    try {
                        castingController.seek(expectedReceiverStreamPos / 1000.0)
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "first-play receiver seek failed")
                    }
                }
            }
            if (sentScheduledStart) return@collect
            Timber.tag(TAG).i("mirror master state: playing=%b — cascading to receiver", playing)
            try {
                if (playing) castingController.resume() else castingController.pause()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "mirror play state to receiver failed (playing=%b)", playing)
            }
        }
    }

    private suspend fun mirrorMasterSeeks(master: SplitAvVideoMaster) {
        master.userSeeks.collect { masterPosMs ->
            val receiverStreamPosMs = (masterPosMs - mediaStartOffsetMs).coerceAtLeast(0L)
            Timber.tag(TAG).i(
                "user seek: master=%dms mediaStartOffset=%dms → receiver stream pos %dms",
                masterPosMs, mediaStartOffsetMs, receiverStreamPosMs,
            )
            runCatching { castingController.seek(receiverStreamPosMs / 1000.0) }
                .onFailure { Timber.tag(TAG).w(it, "user-seek cascade to receiver failed") }
            masterFirstPlayingWallMs = clock()
            codecLearner.reset()
        }
    }

    private suspend fun awaitAlignedStartOffset(): Long? {
        val deadline = clock() + ALIGNED_START_READY_TIMEOUT_MS
        while (clock() < deadline) {
            val offset = clockOffset.offsetMs()
            val remoteReady = castingController.remoteState.value?.videoReadyToStart == true
            if (offset != null && remoteReady) return offset
            delay(ALIGNED_START_READY_POLL_MS)
        }
        Timber.tag(TAG).w(
            "v4 two-sided start readiness timed out (θ=%b receiverReady=%b)",
            clockOffset.offsetMs() != null,
            castingController.remoteState.value?.videoReadyToStart == true,
        )
        return null
    }

    private suspend fun runPingLoop() {
        val burstStart = clock()
        var burstCount = 0
        while (clockOffset.offsetMs() == null && (clock() - burstStart) < 5_000L) {
            try {
                castingController.ping()
                burstCount++
            } catch (e: Exception) {
                Timber.tag(TAG).w("Initial burst ping %d failed: %s", burstCount, e.message)
            }
            delay(if (burstCount < CLOCK_SYNC_BURST_PINGS) CLOCK_SYNC_BURST_INTERVAL_MS else PING_INTERVAL_MS)
            if (burstCount >= CLOCK_SYNC_BURST_PINGS && clockOffset.offsetMs() == null) {
                continue
            }
            if (burstCount >= CLOCK_SYNC_BURST_PINGS) break
        }
        Timber.tag(TAG).i("Initial ping burst finished after %d pings, θ converged=%b", burstCount, clockOffset.offsetMs() != null)

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
                    _state.value = SplitAvController.State.Degraded
                    return
                }
            }
        }
    }

    private suspend fun runPongCollector() {
        castingController.pongs.collect { obs ->
            networkDelay.recordRtt(obs.rttMs)
            if (obs.hasClockSync) {
                clockOffset.record(
                    t1 = obs.pingSentWallMs,
                    t2 = obs.receiverRecvMs!!,
                    t3 = obs.receiverSendMs!!,
                    t4 = obs.pongReceivedWallMs,
                )
            }
        }
    }

    private fun currentVideoPositionMs(master: SplitAvVideoMaster, now: Long): Long {
        val timing = master.renderedVideoTiming()
        if (timing != null &&
            kotlin.math.abs(now - timing.sampledMonotonicMs) <= VIDEO_TIMING_FRESH_MS &&
            kotlin.math.abs(now - timing.releaseMonotonicMs) <= VIDEO_TIMING_FRESH_MS
        ) {
            return (timing.mediaPositionMs + (now - timing.releaseMonotonicMs).coerceAtLeast(0L))
                .coerceAtLeast(0L)
        }
        return master.currentPositionMs()
    }

    private fun recordStableCodecCorrection(
        codecKey: String,
        codecMime: String?,
        smoothedDriftMs: Long,
        currentCorrectionMs: Int,
    ) {
        val routeFingerprint = activeRouteFingerprint ?: return
        if (codecKey == "unknown") return
        val learned = codecLearner.recordStableResidual(
            codecKey = codecKey,
            smoothedDriftMs = smoothedDriftMs,
            currentCorrectionMs = currentCorrectionMs,
        ) ?: return
        learnedCodecCorrections[codecKey] = learned
        val receiver = sessionReceiver ?: return
        scope.launch {
            rememberedReceiversStore.setLearnedCodecCorrection(
                host = receiver.host,
                port = receiver.port,
                routeFingerprint = routeFingerprint,
                codecKey = codecKey,
                correctionMs = learned,
            )
        }
        Timber.tag(TAG).i(
            "learned codec correction route=%s codec=%s (%s) correction=%dms",
            routeFingerprint,
            codecKey,
            codecMime ?: "unknown",
            learned,
        )
    }

    private fun logSessionSummary(reason: String) {
        val summary = sessionMetrics.summary() ?: return
        val receiver = sessionReceiver
        Timber.tag("SplitAvSummary").i(
            "reason=%s receiver=%s:%s samples=%d p50DriftMs=%d p95DriftMs=%d " +
                "staleBeacons=%d hardSeeks=%d lockAfterStartMs=%s lockAfterSeekMs=%s " +
                "codec=%s route=%s manualOffsetMs=%d learnedCorrectionMs=%d",
            reason,
            receiver?.host ?: "unknown",
            receiver?.port?.toString() ?: "unknown",
            summary.samples,
            summary.p50DriftMs,
            summary.p95DriftMs,
            summary.staleBeaconCount,
            summary.hardSeekCount,
            summary.lockAfterStartMs?.toString() ?: "",
            summary.lockAfterSeekMs?.toString() ?: "",
            summary.codecMime ?: "",
            summary.routeFingerprint ?: "",
            summary.manualVideoOffsetMs,
            summary.learnedCodecCorrectionMs,
        )
    }

    private fun applyTrim(master: SplitAvVideoMaster, factor: Float) {
        if (kotlin.math.abs(factor - appliedSpeed) >= TRIM_EPSILON) {
            master.setPlaybackSpeed(factor)
            appliedSpeed = factor
        }
    }

    private fun resetTrim(master: SplitAvVideoMaster) {
        nudgeController.reset()
        if (appliedSpeed != 1.0f) {
            master.setPlaybackSpeed(1.0f)
            appliedSpeed = 1.0f
        }
    }

    private suspend fun handleBeacon(
        master: SplitAvVideoMaster,
        update: dev.jdtech.jellyfin.fcast.protocol.PlaybackUpdateMessage,
    ) {
        val now = clock()
        val tvIsPlaying = update.playbackState == PlaybackState.Playing
        val prevTvIsPlaying = lastBeaconTvIsPlaying
        val alreadyObservedTvPlaying = hasObservedTvPlaying
        if (tvIsPlaying) hasObservedTvPlaying = true
        lastBeaconTvIsPlaying = tvIsPlaying

        if (alreadyObservedTvPlaying && tvIsPlaying && prevTvIsPlaying == false) {
            Timber.tag("SplitAvPauseTrace").w(
                "TV→Playing transition — cascading resume to master. tvIsPlaying=%b prevTvIsPlaying=%b",
                tvIsPlaying, prevTvIsPlaying,
            )
            if (!master.isPlaying.value) {
                isMachineResumed = true
                master.resumeFromMaster()
            }
        }

        if (update.playbackState == PlaybackState.Idle && hasObservedTvPlaying) {
            Timber.tag(TAG).i("Receiver reported Idle after Playing — ending split-A/V, unmuting master")
            scope.launch { endByReceiver() }
            return
        }

        val firstPlayMs = masterFirstPlayingWallMs
        if (firstPlayMs == null || now - firstPlayMs < WARMUP_GRACE_MS) {
            return
        }

        val audioClock = ReceiverAudioClock.choose(
            playbackTimeSeconds = update.time,
            monotonicSampleMs = update.monotonicSampleMs,
            durationSeconds = update.duration,
            audioSinkPositionUs = update.audioSinkPositionUs,
            audioSinkSampleMonotonicMs = update.audioSinkSampleMonotonicMs,
        )
        if (audioClock == null) {
            Timber.tag(TAG).v(
                "implausible receiver playback position dropped (time=%s duration=%s sinkUs=%s)",
                update.time,
                update.duration,
                update.audioSinkPositionUs,
            )
            sessionMetrics.recordStaleBeacon()
            if (!tvIsPlaying && hasObservedTvPlaying && prevTvIsPlaying == true) {
                resetTrim(master)
                if (master.isPlaying.value) {
                    isMachinePaused = true
                    master.pauseFromMaster()
                }
            }
            return
        }

        val gateOffset = clockOffset.offsetMs()
        val gateSampleMs = audioClock.sampleMonotonicMs
        val beaconAgeMs: Long? =
            if (gateOffset != null && gateSampleMs != null) {
                now - (gateSampleMs - gateOffset)
            } else {
                null
            }
        if (!freshnessGate.accept(beaconAgeMs, now)) {
            Timber.tag(TAG).v("stale beacon dropped (age=%s ms)", beaconAgeMs)
            sessionMetrics.recordStaleBeacon()
            update.audioFormat?.mimeType?.let {
                codecLearner.rejectCurrentEvidence(AudioLatencyProfile.codecKey(it))
            }
            return
        }

        update.audioRoute?.fingerprint?.takeIf { it.isNotBlank() }?.let { route ->
            if (route != activeRouteFingerprint) {
                activeRouteFingerprint = route
                codecLearner.reset()
            }
        }

        val codecKey = AudioLatencyProfile.codecKey(update.audioFormat?.mimeType)
        val codecExtraMs = AudioLatencyProfile.extraLatencyMs(update.audioFormat?.mimeType)
        val learnedCodecCorrectionMs = learnedCodecCorrections[codecKey] ?: 0
        val effectiveAudioLatencyMs =
            (cachedAudioLatencyMs + codecExtraMs + learnedCodecCorrectionMs + manualVideoOffsetMs)
                .coerceAtLeast(0)
        val audioPositionMs = audioClock.positionMs
        val audioSampleMonotonicMs = audioClock.sampleMonotonicMs
        val xrPositionMs = currentVideoPositionMs(master, now)

        val state = SplitAvPolicy.BeaconState(
            beaconStreamPositionMs = audioPositionMs + mediaStartOffsetMs,
            beaconReceivedWallMs = now,
            xrPositionMs = xrPositionMs,
            nowWallMs = now,
            audioLatencyMs = effectiveAudioLatencyMs,
            networkOneWayMs = networkDelay.oneWayMs() ?: 0,
            tvIsPlaying = tvIsPlaying,
            clockOffsetMs = clockOffset.offsetMs(),
            receiverMonotonicSampleMs = audioSampleMonotonicMs,
        )

        val rawDriftMs = state.xrPositionMs - SplitAvPolicy.expectedXrPositionMs(state)
        if (tvIsPlaying && !driftEstimator.record(rawDriftMs, now)) {
            traceSink.record(
                SplitAvTrace(
                    atMs = now,
                    rawDriftMs = rawDriftMs,
                    smoothedDriftMs = driftEstimator.driftMs() ?: rawDriftMs,
                    driftRateMsPerSec = driftEstimator.driftRateMsPerSec(),
                    rttMs = networkDelay.rttMs(),
                    codecMime = update.audioFormat?.mimeType,
                    routeFingerprint = activeRouteFingerprint,
                    manualVideoOffsetMs = manualVideoOffsetMs,
                    learnedCodecCorrectionMs = learnedCodecCorrectionMs,
                    audioSinkPositionUs = update.audioSinkPositionUs.takeIf { audioClock.usedAudioSink },
                    audioSinkBufferSizeUs = update.audioSinkBufferSizeUs.takeIf { audioClock.usedAudioSink },
                    videoTimingPositionMs = master.renderedVideoTiming()?.mediaPositionMs,
                    action = "RejectedOutlier",
                ),
            )
            codecLearner.rejectCurrentEvidence(codecKey)
            return
        }
        val smoothedDriftMs = driftEstimator.driftMs() ?: rawDriftMs
        val driftRateMsPerSec = driftEstimator.driftRateMsPerSec()

        val action = SplitAvPolicy.decide(state, smoothedDriftMs, driftRateMsPerSec)
        sessionMetrics.recordDriftSample(
            atMs = now,
            smoothedDriftMs = smoothedDriftMs,
            codecMime = update.audioFormat?.mimeType,
            routeFingerprint = activeRouteFingerprint,
            manualVideoOffsetMs = manualVideoOffsetMs,
            learnedCodecCorrectionMs = learnedCodecCorrectionMs,
        )
        traceSink.record(
            SplitAvTrace(
                atMs = now,
                rawDriftMs = rawDriftMs,
                smoothedDriftMs = smoothedDriftMs,
                driftRateMsPerSec = driftRateMsPerSec,
                rttMs = networkDelay.rttMs(),
                codecMime = update.audioFormat?.mimeType,
                routeFingerprint = activeRouteFingerprint,
                manualVideoOffsetMs = manualVideoOffsetMs,
                learnedCodecCorrectionMs = learnedCodecCorrectionMs,
                audioSinkPositionUs = update.audioSinkPositionUs.takeIf { audioClock.usedAudioSink },
                audioSinkBufferSizeUs = update.audioSinkBufferSizeUs.takeIf { audioClock.usedAudioSink },
                videoTimingPositionMs = master.renderedVideoTiming()?.mediaPositionMs,
                action = action::class.simpleName ?: "?",
            ),
        )
        val dtMs = lastBeaconAtMs?.let { now - it } ?: 100L
        lastBeaconAtMs = now
        when (action) {
            is SplitAvPolicy.DriftAction.Hold -> {
                _lastDriftMs.value = smoothedDriftMs
                recordStableCodecCorrection(
                    codecKey = codecKey,
                    codecMime = update.audioFormat?.mimeType,
                    smoothedDriftMs = smoothedDriftMs,
                    currentCorrectionMs = learnedCodecCorrectionMs,
                )
                applyTrim(
                    master,
                    nudgeController.update(smoothedDriftMs, driftRateMsPerSec, dtMs, frozen = true),
                )
            }
            is SplitAvPolicy.DriftAction.NudgeSpeed -> {
                _lastDriftMs.value = action.driftMs
                val factor =
                    nudgeController.update(action.driftMs, driftRateMsPerSec, dtMs, frozen = false)
                Timber.tag(TAG).i(
                    "drift=%dms rate=%.1fms/s — speed trim=%.4f",
                    action.driftMs, driftRateMsPerSec, factor,
                )
                applyTrim(master, factor)
            }
            is SplitAvPolicy.DriftAction.HardSeek -> {
                _lastDriftMs.value = action.driftMs
                codecLearner.rejectCurrentEvidence(codecKey)
                resetTrim(master)
                if (seekLimiter.wouldDegrade(now)) {
                    Timber.tag(TAG).w(
                        "Split-A/V drift cap exceeded (%d in %dms) — degrading to single-renderer",
                        seekLimiter.count(now),
                        HardSeekRateLimiter.DEFAULT_WINDOW_MS,
                    )
                    _state.value = SplitAvController.State.Degraded
                    return
                }
                seekLimiter.record(now)
                sessionMetrics.recordHardSeek(now)
                driftEstimator.reset()
                Timber.tag(TAG).i("drift=%dms — hard seek to %dms", action.driftMs, action.toPositionMs)
                master.seekTo(action.toPositionMs)
            }
            SplitAvPolicy.DriftAction.TvNotPlaying -> {
                codecLearner.rejectCurrentEvidence(codecKey)
                resetTrim(master)
                val transitioned = prevTvIsPlaying == true
                if (hasObservedTvPlaying && transitioned) {
                    Timber.tag("SplitAvPauseTrace").w(
                        "TV→Paused transition — cascading pause to master. " +
                            "tvIsPlaying=%b prevTvIsPlaying=%b hasObservedTvPlaying=%b " +
                            "masterPos=%dms beaconStreamPos=%dms",
                        tvIsPlaying, prevTvIsPlaying, hasObservedTvPlaying,
                        state.xrPositionMs, state.beaconStreamPositionMs,
                    )
                    if (master.isPlaying.value) {
                        isMachinePaused = true
                        master.pauseFromMaster()
                    }
                }
            }
        }
    }

    override fun shutdown() {
        scope.cancel()
    }

    fun tagAsAudio(play: PlayMessage): PlayMessage = play.withSplitAv(
        SplitAvMetadata(
            role = SplitAvRole.AUDIO,
            syncCadenceHz = SplitAvMetadata.DEFAULT_SYNC_CADENCE_HZ,
        ),
    )

    private class SplitAvSessionMetrics {
        data class Summary(
            val samples: Int,
            val p50DriftMs: Long,
            val p95DriftMs: Long,
            val staleBeaconCount: Int,
            val hardSeekCount: Int,
            val lockAfterStartMs: Long?,
            val lockAfterSeekMs: Long?,
            val codecMime: String?,
            val routeFingerprint: String?,
            val manualVideoOffsetMs: Int,
            val learnedCodecCorrectionMs: Int,
        )

        private val absDriftSamples = mutableListOf<Long>()
        private var startedAtMs: Long = 0L
        private var lastHardSeekAtMs: Long? = null
        private var firstLockAtMs: Long? = null
        private var firstLockAfterSeekAtMs: Long? = null
        private var staleBeaconCount: Int = 0
        private var hardSeekCount: Int = 0
        private var codecMime: String? = null
        private var routeFingerprint: String? = null
        private var manualVideoOffsetMs: Int = 0
        private var learnedCodecCorrectionMs: Int = 0

        fun reset(nowMs: Long) {
            absDriftSamples.clear()
            startedAtMs = nowMs
            lastHardSeekAtMs = null
            firstLockAtMs = null
            firstLockAfterSeekAtMs = null
            staleBeaconCount = 0
            hardSeekCount = 0
            codecMime = null
            routeFingerprint = null
            manualVideoOffsetMs = 0
            learnedCodecCorrectionMs = 0
        }

        fun recordStaleBeacon() {
            staleBeaconCount++
        }

        fun recordHardSeek(nowMs: Long) {
            hardSeekCount++
            lastHardSeekAtMs = nowMs
            firstLockAfterSeekAtMs = null
        }

        fun recordDriftSample(
            atMs: Long,
            smoothedDriftMs: Long,
            codecMime: String?,
            routeFingerprint: String?,
            manualVideoOffsetMs: Int,
            learnedCodecCorrectionMs: Int,
        ) {
            absDriftSamples += kotlin.math.abs(smoothedDriftMs)
            codecMime?.let { this.codecMime = it }
            routeFingerprint?.let { this.routeFingerprint = it }
            this.manualVideoOffsetMs = manualVideoOffsetMs
            this.learnedCodecCorrectionMs = learnedCodecCorrectionMs
            if (SplitAvPolicy.withinHoldBand(smoothedDriftMs)) {
                if (firstLockAtMs == null) firstLockAtMs = atMs
                if (lastHardSeekAtMs != null && firstLockAfterSeekAtMs == null) {
                    firstLockAfterSeekAtMs = atMs
                }
            }
        }

        fun summary(): Summary? {
            if (absDriftSamples.isEmpty()) return null
            val sorted = absDriftSamples.sorted()
            fun percentile(p: Double): Long {
                val index = ((sorted.size - 1) * p).roundToLong().toInt()
                    .coerceIn(0, sorted.lastIndex)
                return sorted[index]
            }
            return Summary(
                samples = sorted.size,
                p50DriftMs = percentile(0.50),
                p95DriftMs = percentile(0.95),
                staleBeaconCount = staleBeaconCount,
                hardSeekCount = hardSeekCount,
                lockAfterStartMs = firstLockAtMs?.let { it - startedAtMs },
                lockAfterSeekMs = firstLockAfterSeekAtMs?.let { lock ->
                    lastHardSeekAtMs?.let { lock - it }
                },
                codecMime = codecMime,
                routeFingerprint = routeFingerprint,
                manualVideoOffsetMs = manualVideoOffsetMs,
                learnedCodecCorrectionMs = learnedCodecCorrectionMs,
            )
        }
    }

    private companion object {
        const val TAG = "FCastSplitAvEngine"
        const val TRIM_EPSILON = 1e-3f
        const val PING_INTERVAL_MS: Long = 2_000L
        const val CLOCK_SYNC_BURST_PINGS: Int = 6
        const val CLOCK_SYNC_BURST_INTERVAL_MS: Long = 150L
        const val ALIGNED_START_LEAD_MS: Long = 700L
        const val ALIGNED_START_FALLBACK_GRACE_MS: Long = 1_500L
        const val ALIGNED_START_READY_TIMEOUT_MS: Long = 2_000L
        const val ALIGNED_START_READY_POLL_MS: Long = 50L
        const val VIDEO_TIMING_FRESH_MS: Long = 500L
        const val MANUAL_VIDEO_OFFSET_MIN_MS: Int = -200
        const val MANUAL_VIDEO_OFFSET_MAX_MS: Int = 200
        const val PING_GIVEUP_FAILURES: Int = 5
        const val WARMUP_GRACE_MS: Long = 8_000L

        val ACTIVE_STATES: Set<SplitAvController.State> = setOf(
            SplitAvController.State.Connecting,
            SplitAvController.State.AwaitingMaster,
            SplitAvController.State.Playing,
            SplitAvController.State.Degraded,
        )
    }
}
