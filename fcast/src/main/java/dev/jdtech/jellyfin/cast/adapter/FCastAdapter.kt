package dev.jdtech.jellyfin.cast.adapter

import dev.jdtech.jellyfin.cast.CastConnectionState
import dev.jdtech.jellyfin.cast.CastDeps
import dev.jdtech.jellyfin.cast.CastMedia
import dev.jdtech.jellyfin.cast.CastMediaState
import dev.jdtech.jellyfin.cast.CastProtocol
import dev.jdtech.jellyfin.cast.CastReceiver
import dev.jdtech.jellyfin.cast.CastSessionEvent
import dev.jdtech.jellyfin.cast.ProtocolAdapter
import dev.jdtech.jellyfin.cast.toFCastReceiver
import dev.jdtech.jellyfin.fcast.protocol.PlaybackState
import dev.jdtech.jellyfin.fcast.protocol.PlaybackUpdateMessage
import dev.jdtech.jellyfin.fcast.protocol.VolumeUpdateMessage
import dev.jdtech.jellyfin.fcast.sender.FCastSenderClient
import dev.jdtech.jellyfin.fcast.sender.PlayMessageBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * [ProtocolAdapter] implementation for FCast. Thin wrapper over [FCastSenderClient] that maps the
 * existing typed surface onto the protocol-agnostic interface and re-emits inbound state as
 * [CastSessionEvent]s.
 *
 * Lifecycle mirrors the underlying client: single-use, construct a new adapter per session.
 */
class FCastAdapter(
    override val receiver: CastReceiver,
    deps: CastDeps = CastDeps(),
) : ProtocolAdapter {

    init {
        require(receiver.protocol == CastProtocol.FCast) {
            "FCastAdapter constructed with non-FCast receiver: ${receiver.protocol}"
        }
    }

    private val ownsScope: Boolean = deps.parentScope == null
    private val scope: CoroutineScope = deps.parentScope
        ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = FCastSenderClient(receiver.toFCastReceiver(), parentScope = scope)

    private val _events = MutableSharedFlow<CastSessionEvent>(
        replay = 0,
        extraBufferCapacity = 32,
    )
    override val events: SharedFlow<CastSessionEvent> = _events.asSharedFlow()

    private var observerJob: Job? = null
    private var lastReportedMediaState: CastMediaState? = null
    private var lastReportedDurationMs: Long? = null
    private var lastReportedSpeed: Float? = null

    override suspend fun connect(): Result<Unit> = runCatching {
        _events.emit(CastSessionEvent.ConnectionStateChanged(CastConnectionState.Connecting))
        try {
            client.connect()
            observerJob = scope.launch {
                // Children launched into `this` are children of observerJob, so a single
                // observerJob.cancel() tears them all down.
                launch {
                    client.playbackUpdates.collect { update -> emitPlaybackUpdate(update) }
                }
                launch {
                    client.volumeUpdates.collect { update -> emitVolumeUpdate(update) }
                }
                launch {
                    client.errors.collect { reason ->
                        _events.emit(CastSessionEvent.Error(reason))
                    }
                }
            }
            _events.emit(CastSessionEvent.ConnectionStateChanged(CastConnectionState.Connected))
        } catch (e: Exception) {
            _events.emit(CastSessionEvent.Error(e.message ?: "FCast connect failed"))
            _events.emit(CastSessionEvent.ConnectionStateChanged(CastConnectionState.Failed))
            throw e
        }
    }

    override suspend fun disconnect() {
        observerJob?.cancel()
        observerJob = null
        runCatching { client.stop() }
        client.close()
        _events.emit(CastSessionEvent.ConnectionStateChanged(CastConnectionState.Disconnected))
        if (ownsScope) scope.cancel()
    }

    override suspend fun load(media: CastMedia): Result<Unit> = runCatching {
        val play = PlayMessageBuilder.build(
            url = media.url,
            container = media.contentType,
            positionSeconds = media.startPositionMs / 1000.0,
            title = media.title,
            thumbnailUrl = media.posterUrl,
        )
        client.play(play)
    }

    override suspend fun play(): Result<Unit> = runCatching { client.resume() }

    override suspend fun pause(): Result<Unit> = runCatching { client.pause() }

    override suspend fun seek(positionMs: Long): Result<Unit> =
        runCatching { client.seek(positionMs / 1000.0) }

    override suspend fun stop(): Result<Unit> = runCatching { client.stop() }

    override suspend fun setVolume(volume: Float): Result<Unit> =
        runCatching { client.setVolume(volume.coerceIn(0f, 1f).toDouble()) }

    override suspend fun setSpeed(speed: Float): Result<Unit> =
        runCatching { client.setSpeed(speed.toDouble()) }

    private suspend fun emitPlaybackUpdate(update: PlaybackUpdateMessage) {
        val mediaState = when (update.playbackState) {
            PlaybackState.Playing -> CastMediaState.Playing
            PlaybackState.Paused -> CastMediaState.Paused
            PlaybackState.Idle -> CastMediaState.Idle
            null -> null
        }
        if (mediaState != null && mediaState != lastReportedMediaState) {
            _events.emit(CastSessionEvent.MediaStateChanged(mediaState))
            lastReportedMediaState = mediaState
            if (mediaState == CastMediaState.Idle) {
                _events.emit(CastSessionEvent.Ended)
            }
        }
        update.time?.let { _events.emit(CastSessionEvent.PositionChanged((it * 1000.0).toLong())) }
        update.duration?.let { duration ->
            val durationMs = (duration * 1000.0).toLong()
            if (durationMs != lastReportedDurationMs) {
                _events.emit(CastSessionEvent.DurationChanged(durationMs))
                lastReportedDurationMs = durationMs
            }
        }
        update.speed?.let { speed ->
            val fSpeed = speed.toFloat()
            if (fSpeed != lastReportedSpeed) {
                _events.emit(CastSessionEvent.SpeedChanged(fSpeed))
                lastReportedSpeed = fSpeed
            }
        }
    }

    private suspend fun emitVolumeUpdate(update: VolumeUpdateMessage) {
        _events.emit(CastSessionEvent.VolumeChanged(update.volume.toFloat()))
    }

    /**
     * Escape hatch for the existing [dev.spatialfin.fcast.session.FCastSessionManager] code path,
     * which still drives the wire client directly during PR 1 (the manager rename is deferred so
     * we don't have to migrate every caller in this PR). Returns the wrapped client so the
     * session manager can keep using its split-A/V + calibration code without reflection.
     *
     * Do not use this from new code — talk to the [ProtocolAdapter] surface instead. Removed
     * once the session manager is migrated in PR 2.
     */
    internal fun underlyingClient(): FCastSenderClient = client
}
