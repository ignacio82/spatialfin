package dev.spatialfin.fcast.session

import dev.jdtech.jellyfin.cast.adapter.sendspinavr.HelloRequestPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.PlaybackLoadPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrHttpClient
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrPushEvent
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrRpcException
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrTransport
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SessionStartPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.TheaterAudioProtocol
import dev.jdtech.jellyfin.player.core.splitav.SplitAvVideoBridge
import dev.jdtech.jellyfin.player.core.splitav.SplitAvVideoMaster
import dev.jdtech.jellyfin.player.core.splitav.TheaterAudioTelemetry
import dev.jdtech.jellyfin.player.core.splitav.TheaterClientEvent
import dev.jdtech.jellyfin.player.core.splitav.TheaterHudBanner
import dev.jdtech.jellyfin.player.core.splitav.TheaterSessionSnapshot
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Spec 0 / Spec 1 Theater Audio (`CastProtocol.SendspinAvr`) Split A/V engine.
 *
 * Drives inverted clock leadership (`spatialfin-avr` owns audio timing + resampler; local video
 * stays locked at `1.0000×`), executes two-step keyframe-hold `T_play` barriers with monotonic
 * `barrier_seq` fencing, maintains state-independent `2 s` heartbeats from `session.start` `rpc.ok`,
 * streams `2 Hz` 4-field `playback.video_timing` beacons, handles all Spec 0 errors/events, and
 * tears down deterministically on `USER_EXIT` (`0 ms` grace) or unexpected detach (`1.5 s` grace).
 */
internal class SendspinAvrSplitAvEngine(
    private val transportFactory: (SendspinAvrSessionConfig) -> SendspinAvrTransport,
    private val clockUs: () -> Long = SendspinAvrTransport.DEFAULT_BOOTTIME_CLOCK_US,
    private val masterFlow: StateFlow<SplitAvVideoMaster?> = SplitAvVideoBridge.activeMaster,
    private val onAuthInvalid: (String) -> Unit = {},
    private val onLcrScreenLockChanged: (Boolean) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val heartbeatIntervalMs: Long = DEFAULT_HEARTBEAT_INTERVAL_MS,
    private val videoTimingIntervalMs: Long = DEFAULT_VIDEO_TIMING_INTERVAL_MS,
    private val seekDebounceMs: Long = DEFAULT_SEEK_DEBOUNCE_MS,
    private val disconnectGraceMs: Long = DEFAULT_DISCONNECT_GRACE_MS,
    private val barrierTimeoutMs: Long = DEFAULT_BARRIER_TIMEOUT_MS,
    private val initialFirstFrameTimeoutMs: Long = if (barrierTimeoutMs == DEFAULT_BARRIER_TIMEOUT_MS) {
        DEFAULT_INITIAL_FIRST_FRAME_TIMEOUT_MS
    } else {
        barrierTimeoutMs
    },
    private val stopTimeoutMs: Long = DEFAULT_STOP_TIMEOUT_MS,
    private val initialClockSamples: Int = SendspinAvrTransport.DEFAULT_INITIAL_CLOCK_SAMPLES,
    private val pingIntervalMs: Long = SendspinAvrTransport.DEFAULT_PING_INTERVAL_MS,
) : SplitAvSessionEngine {

    private val _state = MutableStateFlow(SplitAvController.State.Idle)
    override val state: StateFlow<SplitAvController.State> = _state.asStateFlow()

    private val _lastDriftMs = MutableStateFlow<Long?>(null)
    override val lastDriftMs: StateFlow<Long?> = _lastDriftMs.asStateFlow()

    private val _theaterSnapshot = MutableStateFlow<TheaterSessionSnapshot?>(null)
    override val theaterSnapshot: StateFlow<TheaterSessionSnapshot?> = _theaterSnapshot.asStateFlow()

    private val barrierSeq = AtomicLong(1L)
    private val sessionGeneration = AtomicLong(0L)
    private val resyncTimestampsMs = ArrayDeque<Long>()

    @Volatile private var activeConfig: SendspinAvrSessionConfig? = null
    @Volatile private var activeTransport: SendspinAvrTransport? = null
    @Volatile private var activeFsmState: String = "idle"
    @Volatile private var hasRemoteSessionStarted: Boolean = false
    @Volatile private var hasBoundMasterOnce: Boolean = false
    @Volatile private var initialBarrierCompleted: Boolean = false
    @Volatile private var activeBarrierInFlight: Boolean = false
    @Volatile private var isMachinePaused: Boolean = false
    @Volatile private var isMachineResumed: Boolean = false
    @Volatile private var suppressNextSeekMs: Long? = null

    private var sessionJob: Job? = null
    private var seekDebounceJob: Job? = null
    private var barrierJob: Job? = null
    private var graceStopJob: Job? = null
    private var videoTimingJob: Job? = null

    suspend fun start(config: SendspinAvrSessionConfig): Boolean {
        val gen = sessionGeneration.incrementAndGet()
        cancelJobsForRestart()
        tearDownTransportLocked(reason = "preempted", stopRemote = true)

        activeConfig = config
        activeFsmState = "starting"
        hasRemoteSessionStarted = false
        barrierSeq.set(1L)
        hasBoundMasterOnce = false
        initialBarrierCompleted = false
        activeBarrierInFlight = false
        isMachinePaused = false
        isMachineResumed = false
        suppressNextSeekMs = null
        synchronized(resyncTimestampsMs) { resyncTimestampsMs.clear() }
        _lastDriftMs.value = null
        _state.value = SplitAvController.State.Connecting
        _theaterSnapshot.value = TheaterSessionSnapshot(
            active = true,
            serverId = normalizedServerId(config.receiver.id),
            serverName = config.receiver.name,
            sessionId = config.sessionId,
            lcrScreenLockEnabled = config.initialLcrScreenLock,
            anchorUuid = config.initialAnchorUuid,
            anchorToMlpTransform = config.initialAnchorToMlpTransform,
            roomGeometry = config.initialRoomGeometry,
            telemetry = TheaterAudioTelemetry(sessionId = config.sessionId, state = "starting"),
        )

        val transport = transportFactory(config)
        activeTransport = transport
        val endpoint = SendspinAvrHttpClient.Endpoint(
            host = config.receiver.host,
            port = config.receiver.port,
            useTls = config.receiver.useTls,
        )

        return try {
            val helloOk = transport.connect(
                endpoint = endpoint,
                bearerToken = config.bearerToken,
                hello = HelloRequestPayload(
                    clientId = config.clientId,
                    clientName = config.clientName,
                    formFactor = config.formFactor,
                    appVersion = config.appVersion,
                    minProtocolVersion = TheaterAudioProtocol.VERSION,
                    maxProtocolVersion = TheaterAudioProtocol.VERSION,
                ),
                initialClockSamples = initialClockSamples,
                pingIntervalMs = pingIntervalMs,
            )
            updateSnapshot {
                it.copy(
                    masterVolume = it.masterVolume.coerceAtMost(helloOk.maxSafeVolume),
                )
            }
            val startOk = startAvrSession(transport, config, config.forceTakeover)
            if (!startOk || sessionGeneration.get() != gen) return false

            SplitAvSessionRegistry.setActive(config.receiver)
            _state.value = SplitAvController.State.AwaitingMaster
            Timber.tag(TAG).i(
                "session started server=%s session_id=%s barrier_seq=%d",
                config.receiver.id, config.sessionId, barrierSeq.get(),
            )
            sessionJob = scope.launch { runSessionLoops(gen, transport, config) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: SendspinAvrRpcException) {
            handleRpcFailure(config, e, foldBack = true)
            false
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "SendspinAvr session start failed session_id=%s", config.sessionId)
            publishBanner("internal_error", e.message ?: "Theater Audio connection failed", retryable = true)
            _state.value = SplitAvController.State.Idle
            tearDownTransportLocked(reason = "start_failed", stopRemote = false)
            false
        }
    }

    private suspend fun startAvrSession(
        transport: SendspinAvrTransport,
        config: SendspinAvrSessionConfig,
        forceTakeover: Boolean,
    ): Boolean {
        return try {
            val startOk = transport.startSession(
                sessionId = config.sessionId,
                payload = SessionStartPayload(
                    sourceType = TheaterAudioProtocol.VALID_SOURCE_TYPE,
                    streamUrl = config.streamUrl,
                    streamLayout = config.streamLayout,
                    mediaSourceId = config.mediaSourceId,
                    audioStreamIndex = config.audioStreamIndex,
                    sourceCodec = config.sourceCodec,
                    targetPtsUs = config.initialTargetPtsUs,
                    jellyfinToken = config.jellyfinToken,
                    forceTakeover = forceTakeover,
                ),
            )
            barrierSeq.set(startOk.barrierSeq)
            hasRemoteSessionStarted = true
            activeFsmState = "starting"
            true
        } catch (e: SendspinAvrRpcException) {
            if (e.code == "session_busy" && !forceTakeover) {
                publishBanner(
                    code = "session_busy",
                    message = "Theater room is busy — take over playback?",
                    retryable = true,
                    requiresTakeoverConfirm = true,
                )
                false
            } else {
                throw e
            }
        }
    }

    private suspend fun runSessionLoops(
        gen: Long,
        transport: SendspinAvrTransport,
        config: SendspinAvrSessionConfig,
    ) = coroutineScope {
        // Immediate state-independent lease heartbeat before activeMaster binds.
        launch {
            while (isActive && sessionGeneration.get() == gen && activeTransport === transport) {
                val fsm = activeFsmState
                if (fsm in HEARTBEAT_LEASE_STATES) {
                    transport.sendHeartbeat(config.sessionId, barrierSeq.get(), fsm)
                }
                delay(heartbeatIntervalMs)
            }
        }

        launch {
            transport.pushEvents.collect { event ->
                if (sessionGeneration.get() != gen) return@collect
                handlePushEvent(event, config)
            }
        }

        masterFlow.collectLatest { master ->
            if (sessionGeneration.get() != gen) return@collectLatest
            if (master == null) {
                videoTimingJob?.cancel()
                videoTimingJob = null
                if (hasBoundMasterOnce && graceStopJob == null && _state.value in ACTIVE_STATES) {
                    _state.value = SplitAvController.State.AwaitingMaster
                    onMasterDisconnected(userExit = false)
                }
                return@collectLatest
            }

            graceStopJob?.cancel()
            graceStopJob = null
            hasBoundMasterOnce = true
            master.setAudioMuted(true)
            master.setPlaybackSpeed(1.0f)

            coroutineScope {
                launch { observeUserSeeks(gen, master, transport, config) }
                launch { observeBufferingStalls(gen, master, transport, config) }
                launch { observePlayStateTransitions(gen, master, transport, config) }
                launch { observeTrackSwitches(gen, master, transport, config) }
                launch { observeMediaItemTransitions(gen, master, transport, config) }

                val targetMs = if (!initialBarrierCompleted) {
                    config.initialTargetPtsUs / 1_000L
                } else {
                    master.currentPositionMs()
                }
                launchBarrier(gen, master, transport, config, targetMs)
            }
        }
    }

    private suspend fun observeUserSeeks(
        gen: Long,
        master: SplitAvVideoMaster,
        transport: SendspinAvrTransport,
        config: SendspinAvrSessionConfig,
    ) {
        master.userSeeks.collect { masterPosMs ->
            if (sessionGeneration.get() != gen || !hasRemoteSessionStarted) return@collect
            if (suppressNextSeekMs == masterPosMs) {
                suppressNextSeekMs = null
                return@collect
            }
            barrierJob?.cancel()
            videoTimingJob?.cancel()
            videoTimingJob = null
            activeBarrierInFlight = false
            activeFsmState = "paused"
            val pauseSeq = barrierSeq.incrementAndGet()
            runCatching { transport.pause(config.sessionId, pauseSeq, flush = true) }
                .onFailure { Timber.tag(TAG).w(it, "Seek flush pause failed session_id=%s", config.sessionId) }
            seekDebounceJob?.cancel()
            seekDebounceJob = scope.launch {
                delay(seekDebounceMs)
                if (sessionGeneration.get() == gen && masterFlow.value === master) {
                    launchBarrier(gen, master, transport, config, masterPosMs)
                }
            }
        }
    }

    private suspend fun observeBufferingStalls(
        gen: Long,
        master: SplitAvVideoMaster,
        transport: SendspinAvrTransport,
        config: SendspinAvrSessionConfig,
    ) {
        var wasStalled = false
        master.isBufferingStalled.collect { stalled ->
            if (sessionGeneration.get() != gen) return@collect
            if (stalled == wasStalled) return@collect
            wasStalled = stalled
            if (stalled && !activeBarrierInFlight && activeFsmState == "playing") {
                activeFsmState = "stalled"
                videoTimingJob?.cancel()
                videoTimingJob = null
                val stallSeq = barrierSeq.incrementAndGet()
                runCatching { transport.stall(config.sessionId, stallSeq) }
                    .onFailure { Timber.tag(TAG).w(it, "playback.stall failed session_id=%s", config.sessionId) }
            } else if (!stalled && activeFsmState == "stalled") {
                launchBarrier(gen, master, transport, config, master.currentPositionMs())
            }
        }
    }

    private suspend fun observePlayStateTransitions(
        gen: Long,
        master: SplitAvVideoMaster,
        transport: SendspinAvrTransport,
        config: SendspinAvrSessionConfig,
    ) {
        var lastPlaying: Boolean? = null
        master.isPlaying.collect { playing ->
            if (sessionGeneration.get() != gen || !hasRemoteSessionStarted) return@collect
            if (!initialBarrierCompleted || activeBarrierInFlight) {
                lastPlaying = playing
                if (!playing) isMachinePaused = false
                return@collect
            }
            if (playing == lastPlaying) return@collect
            lastPlaying = playing

            if (!playing && isMachinePaused) {
                isMachinePaused = false
                return@collect
            }
            if (playing && isMachineResumed) {
                isMachineResumed = false
                return@collect
            }
            if (!playing) {
                isMachineResumed = false
                videoTimingJob?.cancel()
                videoTimingJob = null
                activeFsmState = "paused"
                val pauseSeq = barrierSeq.incrementAndGet()
                runCatching { transport.pause(config.sessionId, pauseSeq, flush = true) }
                    .onFailure { Timber.tag(TAG).w(it, "playback.pause failed session_id=%s", config.sessionId) }
            } else {
                isMachinePaused = false
                launchBarrier(gen, master, transport, config, master.currentPositionMs())
            }
        }
    }

    private suspend fun observeTrackSwitches(
        gen: Long,
        master: SplitAvVideoMaster,
        transport: SendspinAvrTransport,
        config: SendspinAvrSessionConfig,
    ) {
        master.trackSwitches.collect { req ->
            performAudioTrackSwitch(gen, master, transport, config, req.audioStreamIndex, req.codec)
        }
    }

    private suspend fun performAudioTrackSwitch(
        gen: Long,
        master: SplitAvVideoMaster,
        transport: SendspinAvrTransport,
        config: SendspinAvrSessionConfig,
        audioStreamIndex: Int,
        codec: String?,
    ) {
        if (sessionGeneration.get() != gen) return
        val resolved = config.resolveTrackSwitchStream?.invoke(audioStreamIndex, codec)
        val loadSeq = barrierSeq.incrementAndGet()
        val posMs = master.currentPositionMs()
        barrierJob?.cancel()
        videoTimingJob?.cancel()
        videoTimingJob = null
        activeFsmState = "awaiting_go"
        try {
            transport.loadMedia(
                sessionId = config.sessionId,
                payload = PlaybackLoadPayload(
                    barrierSeq = loadSeq,
                    audioStreamIndex = resolved?.audioStreamIndex ?: audioStreamIndex,
                    targetPtsUs = posMs * 1_000L,
                    streamUrl = resolved?.streamUrl,
                    streamLayout = resolved?.streamLayout,
                    mediaSourceId = resolved?.mediaSourceId,
                    sourceCodec = resolved?.sourceCodec ?: codec,
                    jellyfinToken = resolved?.jellyfinToken,
                ),
            )
            launchBarrier(gen, master, transport, config, posMs, explicitSeq = loadSeq)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SendspinAvrRpcException) {
            handleRpcFailure(config, e, foldBack = e.code in FOLD_BACK_ERROR_CODES)
        }
    }

    private suspend fun observeMediaItemTransitions(
        gen: Long,
        master: SplitAvVideoMaster,
        transport: SendspinAvrTransport,
        config: SendspinAvrSessionConfig,
    ) {
        master.mediaItemTransitions.collect { nextItem ->
            performMediaItemTransition(
                gen = gen,
                master = master,
                transport = transport,
                config = config,
                itemId = nextItem.itemId,
                mediaSourceId = nextItem.mediaSourceId,
                audioStreamIndex = nextItem.audioStreamIndex,
            )
        }
    }

    private suspend fun performMediaItemTransition(
        gen: Long,
        master: SplitAvVideoMaster,
        transport: SendspinAvrTransport,
        config: SendspinAvrSessionConfig,
        itemId: String,
        mediaSourceId: String?,
        audioStreamIndex: Int,
    ) {
        if (sessionGeneration.get() != gen) return
        barrierJob?.cancel()
        videoTimingJob?.cancel()
        videoTimingJob = null
        isMachinePaused = true
        master.pauseFromMaster()
        val resolved = config.resolveMediaItemStream?.invoke(
            itemId,
            mediaSourceId,
            audioStreamIndex,
        )
        if (resolved == null) {
            Timber.tag(TAG).w("Next item %s offline-only or unresolvable — folding back", itemId)
            foldBackAndStop(master, "offline_item")
            return
        }
        val loadSeq = barrierSeq.incrementAndGet()
        activeFsmState = "awaiting_go"
        try {
            transport.loadMedia(
                sessionId = config.sessionId,
                payload = PlaybackLoadPayload(
                    barrierSeq = loadSeq,
                    audioStreamIndex = resolved.audioStreamIndex,
                    targetPtsUs = 0L,
                    streamUrl = resolved.streamUrl,
                    streamLayout = resolved.streamLayout,
                    mediaSourceId = resolved.mediaSourceId,
                    sourceCodec = resolved.sourceCodec,
                    jellyfinToken = resolved.jellyfinToken,
                ),
            )
            launchBarrier(gen, master, transport, config, targetPositionMs = 0L, explicitSeq = loadSeq)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SendspinAvrRpcException) {
            handleRpcFailure(config, e, foldBack = e.code in FOLD_BACK_ERROR_CODES)
        }
    }

    private fun launchBarrier(
        gen: Long,
        master: SplitAvVideoMaster,
        transport: SendspinAvrTransport,
        config: SendspinAvrSessionConfig,
        targetPositionMs: Long,
        explicitSeq: Long? = null,
    ) {
        barrierJob?.cancel()
        barrierJob = scope.launch {
            runTwoStepBarrier(gen, master, transport, config, targetPositionMs, explicitSeq)
        }
    }

    private suspend fun runTwoStepBarrier(
        gen: Long,
        master: SplitAvVideoMaster,
        transport: SendspinAvrTransport,
        config: SendspinAvrSessionConfig,
        targetPositionMs: Long,
        explicitSeq: Long? = null,
    ) {
        activeBarrierInFlight = true
        videoTimingJob?.cancel()
        videoTimingJob = null
        master.setPlaybackSpeed(1.0f)

        var seq = explicitSeq ?: if (!initialBarrierCompleted) {
            barrierSeq.get()
        } else {
            barrierSeq.incrementAndGet()
        }
        var retries = 0

        try {
            while (sessionGeneration.get() == gen && retries <= MAX_MISSED_LEAD_RETRIES) {
                activeFsmState = "awaiting_go"
                val readyDeferred = scope.async(start = CoroutineStart.UNDISPATCHED) {
                    master.firstFrameReady.first { it.barrierSeq == seq }
                }
                suppressNextSeekMs = targetPositionMs
                master.prepareAndHoldAt(seq, targetPositionMs)

                val firstFrameTimeoutMs = if (!initialBarrierCompleted) {
                    initialFirstFrameTimeoutMs
                } else {
                    barrierTimeoutMs
                }
                val readyFrame = withTimeout(firstFrameTimeoutMs) { readyDeferred.await() }
                if (sessionGeneration.get() != gen) return

                val readyOk = transport.signalReady(
                    sessionId = config.sessionId,
                    barrierSeq = seq,
                    actualPtsUs = readyFrame.actualPtsUs,
                    timeoutMs = barrierTimeoutMs,
                )
                if (readyOk.barrierSeq != barrierSeq.get() && readyOk.barrierSeq != seq) {
                    return
                }
                val nowUs = clockUs()
                val thetaUs = transport.clockSync.snapshot(nowUs).thetaUs
                val tPlayClientUs = readyOk.tPlayAvrUs - thetaUs - config.clientDisplayOffsetUs
                val leadRemainingUs = tPlayClientUs - nowUs
                if (leadRemainingUs < MIN_CLIENT_LEAD_US) {
                    retries++
                    Timber.tag(TAG).w(
                        "Missed T_play lead (remaining=%d us, retry=%d/%d) session_id=%s",
                        leadRemainingUs, retries, MAX_MISSED_LEAD_RETRIES, config.sessionId,
                    )
                    if (retries > MAX_MISSED_LEAD_RETRIES) {
                        publishBanner("seek_timeout", "Audio sync barrier missed playback window", retryable = true)
                        foldBackAndStop(master, "missed_lead", preserveBanner = true)
                        return
                    }
                    seq = barrierSeq.incrementAndGet()
                    continue
                }

                isMachinePaused = false
                isMachineResumed = true
                master.startAtBoottimeUs(seq, tPlayClientUs)
                initialBarrierCompleted = true
                activeBarrierInFlight = false
                activeFsmState = "playing"
                _state.value = SplitAvController.State.Playing
                updateSnapshot { snap ->
                    snap.copy(
                        banner = null,
                        telemetry = snap.telemetry.copy(
                            sessionId = config.sessionId,
                            state = "playing",
                            dspGroupDelayUs = readyOk.leadBreakdownUs.dspGroupDelayUs,
                            codecActive = readyOk.codecActive,
                            rendererMode = readyOk.rendererMode,
                            activeDecodeChain = readyOk.activeDecodeChain,
                            tPlayLaunchErrorUs = master.lastTPlayLaunchErrorUs(),
                        ),
                    )
                }
                startVideoTimingLoop(gen, seq, master, transport, config)
                return
            }
        } catch (e: TimeoutCancellationException) {
            Timber.tag(TAG).w(e, "T_play barrier timed out session_id=%s", config.sessionId)
            publishBanner("seek_timeout", "Theater Audio sync timed out", retryable = true)
            foldBackAndStop(master, "barrier_timeout", preserveBanner = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SendspinAvrRpcException) {
            handleRpcFailure(config, e, foldBack = true, master = master)
        } finally {
            activeBarrierInFlight = false
        }
    }

    private fun startVideoTimingLoop(
        gen: Long,
        barrierSeqForLoop: Long,
        master: SplitAvVideoMaster,
        transport: SendspinAvrTransport,
        config: SendspinAvrSessionConfig,
    ) {
        videoTimingJob?.cancel()
        videoTimingJob = scope.launch {
            while (isActive && sessionGeneration.get() == gen && activeFsmState == "playing") {
                val nowUs = clockUs()
                val clockSnap = transport.clockSync.snapshot(nowUs)
                val timing = master.renderedVideoTiming()
                val mediaPtsUs = timing?.mediaPositionUs ?: (master.currentPositionMs().coerceAtLeast(0L) * 1_000L)
                val releaseBootUs = timing?.releaseBoottimeUs ?: nowUs
                val visibleAvrUs = releaseBootUs + config.clientDisplayOffsetUs + clockSnap.thetaUs
                transport.sendVideoTiming(
                    sessionId = config.sessionId,
                    barrierSeq = barrierSeqForLoop,
                    mediaPtsUs = mediaPtsUs,
                    visibleAvrUs = visibleAvrUs,
                    clockUncertaintyUs = clockSnap.uncertaintyUs,
                )
                updateSnapshot { snap ->
                    snap.copy(
                        telemetry = snap.telemetry.copy(
                            clockUncertaintyUs = clockSnap.uncertaintyUs,
                            tPlayLaunchErrorUs = master.lastTPlayLaunchErrorUs() ?: snap.telemetry.tPlayLaunchErrorUs,
                        ),
                    )
                }
                delay(videoTimingIntervalMs)
            }
        }
    }

    private suspend fun handlePushEvent(
        event: SendspinAvrPushEvent,
        config: SendspinAvrSessionConfig,
    ) {
        when (event) {
            is SendspinAvrPushEvent.TelemetrySession -> {
                val t = event.payload
                _lastDriftMs.value = t.driftUs / 1_000L
                val degraded = t.speakers.firstOrNull { !it.online || it.degradedCrossfade }?.role
                updateSnapshot { snap ->
                    snap.copy(
                        masterVolume = t.masterVolume,
                        muted = t.muted,
                        eqBypass = t.eqBypass,
                        upmixMode = t.upmixMode,
                        maZoneState = t.maZoneState,
                        telemetry = snap.telemetry.copy(
                            sessionId = event.sessionId ?: config.sessionId,
                            state = t.state,
                            driftUs = t.driftUs,
                            resamplerPpm = t.resamplerPpm,
                            dspGroupDelayUs = t.dspGroupDelayUs,
                            codecActive = t.codecActive,
                            rendererMode = t.rendererMode,
                            activeDecodeChain = t.activeDecodeChain,
                            eqBypass = t.eqBypass,
                            degradedSpeaker = degraded,
                            fallbackActive = t.fallbackActive,
                            fallbackReason = t.fallbackReason,
                        ),
                    )
                }
            }
            is SendspinAvrPushEvent.PlaybackResyncRequired -> {
                if (event.payload.barrierSeq != barrierSeq.get()) return
                val nowMs = clockUs() / 1_000L
                val count = synchronized(resyncTimestampsMs) {
                    while (resyncTimestampsMs.isNotEmpty() &&
                        nowMs - resyncTimestampsMs.first() > RESYNC_WINDOW_MS
                    ) {
                        resyncTimestampsMs.removeFirst()
                    }
                    resyncTimestampsMs.addLast(nowMs)
                    resyncTimestampsMs.size
                }
                Timber.tag(TAG).w(
                    "playback.resync_required drift_us=%d count=%d/4 session_id=%s",
                    event.payload.driftUs, count, config.sessionId,
                )
                val master = masterFlow.value
                if (count >= MAX_RESYNCS_BEFORE_FOLD_BACK) {
                    publishBanner("seek_timeout", "Too many audio resyncs; switched to headset audio")
                    foldBackAndStop(master, "resync_cap", preserveBanner = true)
                } else if (master != null) {
                    val transport = activeTransport ?: return
                    launchBarrier(sessionGeneration.get(), master, transport, config, master.currentPositionMs())
                }
            }
            is SendspinAvrPushEvent.SessionReleased -> {
                hasRemoteSessionStarted = false
                activeFsmState = "paused"
                videoTimingJob?.cancel()
                videoTimingJob = null
                masterFlow.value?.let { master ->
                    isMachinePaused = true
                    master.pauseFromMaster()
                }
                _state.value = SplitAvController.State.Degraded
                publishBanner(
                    code = "session_released",
                    message = "Theater session ended (${event.payload.reason})",
                    retryable = event.payload.reason != "fatal_decode_error",
                )
            }
            is SendspinAvrPushEvent.RoomStateChanged -> updateSnapshot {
                it.copy(
                    masterVolume = event.payload.masterVolume,
                    muted = event.payload.muted,
                    eqBypass = event.payload.eqBypass,
                    upmixMode = event.payload.upmixMode,
                )
            }
            is SendspinAvrPushEvent.RoomProfileUpdated -> updateSnapshot { snap ->
                val geom = snap.roomGeometry?.copy(
                    roomProfileVersion = event.payload.roomProfileVersion,
                    layout = event.payload.layout,
                )
                snap.copy(roomGeometry = geom)
            }
            is SendspinAvrPushEvent.SpeakerDegraded -> updateSnapshot {
                it.copy(
                    telemetry = it.telemetry.copy(degradedSpeaker = event.payload.role),
                    lastToast = "Speaker ${event.payload.role} offline — rerouted (${event.payload.reason})",
                )
            }
            is SendspinAvrPushEvent.SpeakerRestored -> updateSnapshot {
                val cleared = if (it.telemetry.degradedSpeaker == event.payload.role) null else it.telemetry.degradedSpeaker
                it.copy(
                    telemetry = it.telemetry.copy(degradedSpeaker = cleared),
                    lastToast = "Speaker ${event.payload.role} restored",
                )
            }
            is SendspinAvrPushEvent.DecoderFallback -> updateSnapshot {
                it.copy(
                    telemetry = it.telemetry.copy(
                        codecActive = event.payload.codecActive,
                        rendererMode = event.payload.rendererMode,
                        activeDecodeChain = event.payload.toChain,
                        fallbackActive = true,
                        fallbackReason = event.payload.reason,
                    ),
                    lastToast = "Decoder fallback to ${event.payload.rendererMode}",
                )
            }
            is SendspinAvrPushEvent.MaStateChanged -> updateSnapshot {
                it.copy(maZoneState = event.payload.maZoneState)
            }
            is SendspinAvrPushEvent.Disconnected -> {
                if (_state.value in ACTIVE_STATES) {
                    _state.value = SplitAvController.State.Degraded
                    publishBanner("disconnected", "Lost connection to Theater AVR", retryable = true)
                }
            }
        }
    }

    private suspend fun handleRpcFailure(
        config: SendspinAvrSessionConfig,
        e: SendspinAvrRpcException,
        foldBack: Boolean,
        master: SplitAvVideoMaster? = masterFlow.value,
    ) {
        Timber.tag(TAG).w(e, "RPC failure code=%s session_id=%s", e.code, config.sessionId)
        val serverId = normalizedServerId(config.receiver.id)
        if (e.code == "auth_invalid") {
            onAuthInvalid(serverId)
            publishBanner("auth_invalid", "Room pairing expired — re-pair required", requiresRepair = true)
        } else {
            publishBanner(e.code, e.rpcMessage, retryable = e.retryable)
        }
        if (foldBack && e.code != "session_busy") {
            foldBackAndStop(master, e.code, preserveBanner = true)
        } else {
            _state.value = SplitAvController.State.Idle
        }
    }

    private suspend fun foldBackAndStop(
        master: SplitAvVideoMaster?,
        reason: String,
        preserveBanner: Boolean = false,
    ) {
        master?.let {
            it.setPlaybackSpeed(1.0f)
            it.setAudioMuted(false)
            it.foldBackToLocal()
            if (!initialBarrierCompleted && !it.isPlaying.value) {
                it.resumeFromMaster()
            }
        }
        stopInternal(reason = reason, preserveBanner = preserveBanner)
    }

    override suspend fun pause() {
        if (_state.value !in ACTIVE_STATES) return
        val transport = activeTransport ?: return
        val config = activeConfig ?: return
        masterFlow.value?.let { master ->
            if (master.isPlaying.value) {
                isMachinePaused = true
                master.pauseFromMaster()
            }
        }
        videoTimingJob?.cancel()
        videoTimingJob = null
        activeFsmState = "paused"
        val seq = barrierSeq.incrementAndGet()
        runCatching { transport.pause(config.sessionId, seq, flush = true) }
    }

    override suspend fun resume() {
        if (_state.value !in ACTIVE_STATES) return
        val master = masterFlow.value ?: return
        val transport = activeTransport ?: return
        val config = activeConfig ?: return
        launchBarrier(sessionGeneration.get(), master, transport, config, master.currentPositionMs())
    }

    override suspend fun seekTo(positionMs: Long) {
        if (_state.value !in ACTIVE_STATES) return
        val master = masterFlow.value ?: return
        val transport = activeTransport ?: return
        val config = activeConfig ?: return
        val seq = barrierSeq.incrementAndGet()
        runCatching { transport.pause(config.sessionId, seq, flush = true) }
        launchBarrier(sessionGeneration.get(), master, transport, config, positionMs.coerceAtLeast(0L))
    }

    override fun currentMasterPositionMs(): Long? {
        if (_state.value !in ACTIVE_STATES) return null
        return masterFlow.value?.currentPositionMs()
    }

    override suspend fun endFromMaster() {
        foldBackAndStop(masterFlow.value, "fold_back")
    }

    override suspend fun endByReceiver() {
        foldBackAndStop(masterFlow.value, "receiver_ended")
    }

    override suspend fun stop(reason: String) {
        stopInternal(reason = reason, preserveBanner = false)
    }

    private suspend fun stopInternal(reason: String, preserveBanner: Boolean) {
        sessionGeneration.incrementAndGet()
        withContext(NonCancellable) {
            cancelJobsForRestart()
            tearDownTransportLocked(reason = reason, stopRemote = true)
            _state.value = SplitAvController.State.Idle
            _lastDriftMs.value = null
            activeFsmState = "idle"
            hasRemoteSessionStarted = false
            SplitAvSessionRegistry.setActive(null)
            _theaterSnapshot.value = if (preserveBanner) {
                _theaterSnapshot.value?.copy(
                    active = false,
                    telemetry = _theaterSnapshot.value!!.telemetry.copy(state = "idle"),
                )
            } else {
                null
            }
            activeConfig = null
        }
    }

    override fun onMasterDisconnected(userExit: Boolean) {
        val gen = sessionGeneration.get()
        if (_state.value == SplitAvController.State.Idle) return
        val transport = activeTransport ?: return
        val config = activeConfig ?: return
        if (userExit) {
            graceStopJob?.cancel()
            graceStopJob = null
            scope.launch { stop("activity_exit") }
            return
        }
        if (graceStopJob?.isActive == true) return
        videoTimingJob?.cancel()
        videoTimingJob = null
        activeFsmState = "paused"
        val pauseSeq = barrierSeq.incrementAndGet()
        scope.launch {
            runCatching { transport.pause(config.sessionId, pauseSeq, flush = true) }
        }
        graceStopJob = scope.launch {
            delay(disconnectGraceMs)
            if (sessionGeneration.get() == gen && masterFlow.value == null && _state.value in ACTIVE_STATES) {
                Timber.tag(TAG).w("Master stayed disconnected for %d ms; stopping session_id=%s", disconnectGraceMs, config.sessionId)
                stop("master_lost")
            }
        }
    }

    override suspend fun handleTheaterClientEvent(event: TheaterClientEvent) {
        val config = activeConfig ?: return
        val transport = activeTransport
        when (event) {
            is TheaterClientEvent.SetVolume -> transport?.let {
                runCatching {
                    val ok = it.setVolume(config.sessionId, event.volume, event.muted)
                    updateSnapshot { s -> s.copy(masterVolume = ok.masterVolume, muted = ok.muted) }
                }
            }
            is TheaterClientEvent.SetMuted -> transport?.let {
                val vol = _theaterSnapshot.value?.masterVolume ?: 1.0
                runCatching {
                    val ok = it.setVolume(config.sessionId, vol, event.muted)
                    updateSnapshot { s -> s.copy(masterVolume = ok.masterVolume, muted = ok.muted) }
                }
            }
            is TheaterClientEvent.SetEqBypass -> transport?.let {
                runCatching {
                    val ok = it.setEqBypass(config.sessionId, event.bypass)
                    updateSnapshot { s ->
                        s.copy(
                            eqBypass = ok.eqBypass,
                            telemetry = s.telemetry.copy(eqBypass = ok.eqBypass, dspGroupDelayUs = ok.dspGroupDelayUs),
                        )
                    }
                }
            }
            is TheaterClientEvent.SetUpmixMode -> transport?.let {
                runCatching {
                    val ok = it.setUpmixMode(config.sessionId, event.mode)
                    updateSnapshot { s -> s.copy(upmixMode = ok.upmixMode) }
                }
            }
            is TheaterClientEvent.SetLcrScreenLockPref -> {
                onLcrScreenLockChanged(event.enabled)
                updateSnapshot { s -> s.copy(lcrScreenLockEnabled = event.enabled) }
            }
            is TheaterClientEvent.CenterSnap -> transport?.let {
                runCatching { it.centerSnap(config.sessionId, event.durationMs, event.gainDbfs) }
            }
            is TheaterClientEvent.ConfirmTakeover -> {
                if (event.confirm) {
                    start(config.copy(forceTakeover = true))
                } else {
                    stop("takeover_declined")
                }
            }
            TheaterClientEvent.ReconnectRequested -> {
                val posUs = (masterFlow.value?.currentPositionMs() ?: 0L) * 1_000L
                start(config.copy(initialTargetPtsUs = posUs))
            }
            TheaterClientEvent.FoldBackRequested -> {
                endFromMaster()
            }
            is TheaterClientEvent.AudioTrackChanged -> {
                val master = masterFlow.value ?: return
                val active = transport ?: return
                performAudioTrackSwitch(
                    gen = sessionGeneration.get(),
                    master = master,
                    transport = active,
                    config = config,
                    audioStreamIndex = event.mediaStreamIndex,
                    codec = event.codec,
                )
            }
            is TheaterClientEvent.MediaItemChanged -> {
                val master = masterFlow.value ?: return
                val active = transport ?: return
                performMediaItemTransition(
                    gen = sessionGeneration.get(),
                    master = master,
                    transport = active,
                    config = config,
                    itemId = event.itemId,
                    mediaSourceId = event.mediaSourceId,
                    audioStreamIndex = event.audioStreamIndex,
                )
            }
            is TheaterClientEvent.BufferingStall -> Unit
        }
    }

    override fun shutdown() {
        sessionGeneration.incrementAndGet()
        cancelJobsForRestart()
        activeTransport?.disconnect()
        activeTransport = null
        activeConfig = null
        scope.cancel()
    }

    private fun cancelJobsForRestart() {
        graceStopJob?.cancel()
        graceStopJob = null
        seekDebounceJob?.cancel()
        seekDebounceJob = null
        barrierJob?.cancel()
        barrierJob = null
        videoTimingJob?.cancel()
        videoTimingJob = null
        sessionJob?.cancel()
        sessionJob = null
    }

    private suspend fun tearDownTransportLocked(reason: String, stopRemote: Boolean) {
        val transport = activeTransport ?: return
        val config = activeConfig
        val hadSession = hasRemoteSessionStarted
        hasRemoteSessionStarted = false
        activeTransport = null
        if (stopRemote && hadSession && config != null && transport.connected.value) {
            withTimeoutOrNull(stopTimeoutMs) {
                runCatching { transport.stopSession(config.sessionId, reason, timeoutMs = stopTimeoutMs) }
            }
        }
        transport.disconnect()
    }

    private fun publishBanner(
        code: String,
        message: String,
        retryable: Boolean = false,
        requiresTakeoverConfirm: Boolean = false,
        requiresRepair: Boolean = false,
    ) {
        updateSnapshot {
            it.copy(
                banner = TheaterHudBanner(
                    code = code,
                    message = message,
                    retryable = retryable,
                    requiresTakeoverConfirm = requiresTakeoverConfirm,
                    requiresRepair = requiresRepair,
                ),
            )
        }
    }

    private inline fun updateSnapshot(transform: (TheaterSessionSnapshot) -> TheaterSessionSnapshot) {
        while (true) {
            val current = _theaterSnapshot.value ?: TheaterSessionSnapshot()
            val next = transform(current)
            if (_theaterSnapshot.compareAndSet(_theaterSnapshot.value, next)) return
        }
    }

    private fun normalizedServerId(receiverId: String): String =
        receiverId.removePrefix("SendspinAvr:")

    private companion object {
        const val TAG = "TheaterAudio"
        const val DEFAULT_HEARTBEAT_INTERVAL_MS = 2_000L
        const val DEFAULT_VIDEO_TIMING_INTERVAL_MS = 500L
        const val DEFAULT_SEEK_DEBOUNCE_MS = 150L
        const val DEFAULT_DISCONNECT_GRACE_MS = 1_500L
        const val DEFAULT_BARRIER_TIMEOUT_MS = 5_000L
        const val DEFAULT_INITIAL_FIRST_FRAME_TIMEOUT_MS = 20_000L
        const val DEFAULT_STOP_TIMEOUT_MS = 200L
        const val MIN_CLIENT_LEAD_US = 1_000L
        const val MAX_MISSED_LEAD_RETRIES = 3
        const val RESYNC_WINDOW_MS = 30_000L
        const val MAX_RESYNCS_BEFORE_FOLD_BACK = 4

        val HEARTBEAT_LEASE_STATES = setOf("starting", "awaiting_go", "playing", "paused", "stalled")
        val FOLD_BACK_ERROR_CODES = setOf("codec_unsupported", "speaker_offline_critical", "jellyfin_auth_failed")
        val ACTIVE_STATES = setOf(
            SplitAvController.State.Connecting,
            SplitAvController.State.AwaitingMaster,
            SplitAvController.State.Playing,
            SplitAvController.State.Degraded,
        )
    }
}
