package dev.jdtech.jellyfin.cast.adapter.airplay

import dev.jdtech.jellyfin.cast.CastCapability
import dev.jdtech.jellyfin.cast.CastConnectionState
import dev.jdtech.jellyfin.cast.CastDeps
import dev.jdtech.jellyfin.cast.CastMedia
import dev.jdtech.jellyfin.cast.CastMediaState
import dev.jdtech.jellyfin.cast.CastProtocol
import dev.jdtech.jellyfin.cast.CastReceiver
import dev.jdtech.jellyfin.cast.CastSessionEvent
import dev.jdtech.jellyfin.cast.ProtocolAdapter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * [ProtocolAdapter] for AirPlay v1 video.
 *
 * Compared to FCast (TCP, persistent socket, push-style state updates) and Google Cast
 * (length-prefixed protobuf over TLS, push-style media-status events), AirPlay v1 is a
 * stateless HTTP control surface: each verb is a one-shot POST/GET, and there is **no** event
 * channel that pushes playback state back to us. Some Apple TVs implement `POST /reverse`
 * as a long-lived stream but it's optional and inconsistent across firmware. So instead the
 * adapter polls `GET /playback-info` at ~1 Hz while connected and translates the resulting
 * `bplist00` into [CastSessionEvent]s.
 *
 * Scope (per the implementation brief §8):
 *  - AirPlay v1 video only. AirPlay 2 / HomeKit pairing → PR 6.
 *  - PIN-required devices (Apple TVs that respond 470) surface a typed error and disconnect.
 *    The SRP-6a pairing handshake lives in a follow-up.
 *  - No subtitle track passing. AirPlay v1 has no native subtitle slot; styled ASS arrives
 *    via the sender-side burn-in transcode from PR 2, which Cast and AirPlay both inherit.
 *
 * Capabilities populated at construct time from the receiver's TXT `features` bitmask. We
 * widen [currentCapabilities] from [receiver.capabilities] so we can later attenuate (e.g.
 * a receiver that doesn't claim Volume gets the slider hidden by the UI).
 */
class AirPlayAdapter(
    override val receiver: CastReceiver,
    deps: CastDeps = CastDeps(),
) : ProtocolAdapter {

    init {
        require(receiver.protocol == CastProtocol.AirPlay) {
            "AirPlayAdapter constructed with non-AirPlay receiver: ${receiver.protocol}"
        }
    }

    private val ownsScope: Boolean = deps.parentScope == null
    private val scope: CoroutineScope = deps.parentScope
        ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val http = AirPlayHttpClient(receiver.host, receiver.port)

    private val _events = MutableSharedFlow<CastSessionEvent>(
        replay = 0, extraBufferCapacity = 64,
    )
    override val events: SharedFlow<CastSessionEvent> = _events.asSharedFlow()

    private val _currentCapabilities = MutableStateFlow(receiver.capabilities)
    override val currentCapabilities: StateFlow<Set<CastCapability>> =
        _currentCapabilities.asStateFlow()

    private var pollerJob: Job? = null
    /** Cached title duration in seconds — captured from the first playback-info that reports
     * one. Used to convert absolute-ms seek requests into the fraction `/play` wants. */
    private var durationSeconds: Double? = null
    private var lastReportedMediaState: CastMediaState? = null
    private var lastReportedDurationMs: Long? = null

    override suspend fun connect(): Result<Unit> = runCatching {
        _events.emit(CastSessionEvent.ConnectionStateChanged(CastConnectionState.Connecting))
        try {
            // Sanity ping. The HTTP client has a 5s connect timeout — anything failing here
            // means the receiver is offline / on a different network / blocking 7000.
            withContext(Dispatchers.IO) {
                http.execute(http.buildPlaybackInfoRequest())
            }
            _events.emit(CastSessionEvent.ConnectionStateChanged(CastConnectionState.Connected))
            pollerJob = scope.launch { pollLoop() }
        } catch (e: AirPlayHttpClient.AirPlayException.PinRequired) {
            _events.emit(CastSessionEvent.Error("AirPlay device requires PIN pairing (not supported yet)"))
            _events.emit(CastSessionEvent.ConnectionStateChanged(CastConnectionState.Failed))
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _events.emit(CastSessionEvent.Error(e.message ?: "AirPlay connect failed"))
            _events.emit(CastSessionEvent.ConnectionStateChanged(CastConnectionState.Failed))
            throw e
        }
    }

    override suspend fun disconnect() {
        pollerJob?.cancel()
        pollerJob = null
        // Best-effort STOP. AirPlay receivers tolerate the call even when nothing is playing.
        runCatching { withContext(Dispatchers.IO) { http.execute(http.buildStopRequest()) } }
        _events.emit(CastSessionEvent.ConnectionStateChanged(CastConnectionState.Disconnected))
        if (ownsScope) scope.cancel()
    }

    override suspend fun load(media: CastMedia): Result<Unit> = runCatching {
        // AirPlay's /play takes Start-Position as a fraction of the title's duration. We don't
        // know the duration until the receiver tells us via /playback-info, so on first load
        // we send fraction = 0 and let the seek path resync after the first poll response.
        val durationMs = media.durationMs ?: 0L
        val fraction = if (durationMs > 0) {
            (media.startPositionMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        durationSeconds = if (durationMs > 0) durationMs / 1000.0 else null
        withContext(Dispatchers.IO) {
            http.execute(http.buildPlayRequest(media.url, fraction))
        }
        Unit
    }

    override suspend fun play(): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) { http.execute(http.buildRateRequest(1.0)) }
        Unit
    }

    override suspend fun pause(): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) { http.execute(http.buildRateRequest(0.0)) }
        Unit
    }

    override suspend fun seek(positionMs: Long): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            http.execute(http.buildScrubRequest(positionMs / 1000.0))
        }
        Unit
    }

    override suspend fun stop(): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) { http.execute(http.buildStopRequest()) }
        Unit
    }

    override suspend fun setVolume(volume: Float): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            http.execute(http.buildVolumeRequest(volume))
        }
        Unit
    }

    /** AirPlay v1 has no playback-rate primitive. */
    override suspend fun setSpeed(speed: Float): Result<Unit> = Result.failure(
        UnsupportedOperationException("AirPlay v1 does not support variable playback speed"),
    )

    /**
     * 1 Hz `/playback-info` poll. Translates the `readyToPlay`/`rate`/`position`/`duration`
     * fields into [CastSessionEvent]s. Cancellation stops the loop cleanly.
     */
    private suspend fun pollLoop() {
        while (currentCoroutineContext().isActive) {
            val bytes = runCatching {
                withContext(Dispatchers.IO) { http.execute(http.buildPlaybackInfoRequest()) }
            }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                processPlaybackInfo(bytes)
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun processPlaybackInfo(bytes: ByteArray) {
        val root = runCatching { BinaryPlist.parse(bytes) }
            .onFailure { Timber.tag(TAG).w(it, "failed to parse /playback-info bplist") }
            .getOrNull() as? Map<String, Any?> ?: return

        val readyToPlay = root["readyToPlay"] as? Boolean ?: false
        val rate = (root["rate"] as? Double) ?: 0.0
        val position = (root["position"] as? Double) ?: 0.0
        val duration = (root["duration"] as? Double)?.takeIf { it > 0 }

        val mediaState = when {
            !readyToPlay -> CastMediaState.Buffering
            rate > 0.0 -> CastMediaState.Playing
            else -> CastMediaState.Paused
        }
        if (mediaState != lastReportedMediaState) {
            _events.emit(CastSessionEvent.MediaStateChanged(mediaState))
            lastReportedMediaState = mediaState
        }
        _events.emit(CastSessionEvent.PositionChanged((position * 1000.0).toLong()))
        if (duration != null) {
            durationSeconds = duration
            val durationMs = (duration * 1000.0).toLong()
            if (durationMs != lastReportedDurationMs) {
                _events.emit(CastSessionEvent.DurationChanged(durationMs))
                lastReportedDurationMs = durationMs
            }
        }
        // EOF detection: AirPlay receivers report rate=0 and position≈duration when they hit
        // end-of-stream. They don't set readyToPlay=false at the same time, so we synthesise an
        // Ended event when we see those conditions and we previously reported Playing.
        if (duration != null && position >= duration - 0.5 && rate == 0.0) {
            _events.emit(CastSessionEvent.Ended)
        }
    }

    private companion object {
        const val TAG = "AirPlayAdapter"
        const val POLL_INTERVAL_MS = 1_000L
    }
}
