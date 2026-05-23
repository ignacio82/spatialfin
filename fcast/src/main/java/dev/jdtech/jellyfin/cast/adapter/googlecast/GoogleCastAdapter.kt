package dev.jdtech.jellyfin.cast.adapter.googlecast

import dev.jdtech.jellyfin.cast.CastCapability
import dev.jdtech.jellyfin.cast.CastConnectionState
import dev.jdtech.jellyfin.cast.CastDeps
import dev.jdtech.jellyfin.cast.CastMedia
import dev.jdtech.jellyfin.cast.CastMediaState
import dev.jdtech.jellyfin.cast.CastProtocol
import dev.jdtech.jellyfin.cast.CastReceiver
import dev.jdtech.jellyfin.cast.CastSessionEvent
import dev.jdtech.jellyfin.cast.ProtocolAdapter
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import timber.log.Timber

/**
 * [ProtocolAdapter] for Google Cast V2 (Chromecast, Google TV, Nest Hub, etc.).
 *
 * The protocol layers cleanly:
 *  - **Transport:** [CastV2Channel] handles TLS to host:8009 + length-prefixed protobuf framing.
 *  - **Session:** [GoogleCastAdapter] runs the connection / receiver / media state machine on
 *    top of that channel. Drives LAUNCH (Default Media Receiver) → LOAD → control.
 *
 * State machine (§7.3 of the implementation brief):
 *   1. TLS connect.
 *   2. Send `CONNECT` on the connection namespace (destination = receiver-0).
 *   3. Start ~5 s PING heartbeat on the heartbeat namespace.
 *   4. Send `LAUNCH` on the receiver namespace with the Default Media Receiver app id.
 *   5. Wait for `RECEIVER_STATUS` to surface `application.transportId`.
 *   6. Open a second virtual connection on the transportId destination.
 *   7. On [load], send `LOAD` on the media namespace; capture the `mediaSessionId` from the
 *      first `MEDIA_STATUS`. All subsequent PLAY / PAUSE / SEEK / SET_VOLUME / STOP use that id.
 *
 * Error model:
 *  - Connect failures → [CastSessionEvent.ConnectionStateChanged] = Failed + [Error].
 *  - LAUNCH_ERROR / INVALID_REQUEST → [Error] but session stays open (UI can retry).
 *  - TLS handshake / socket EOF → [ConnectionStateChanged] = Disconnected + [Error].
 */
class GoogleCastAdapter(
    override val receiver: CastReceiver,
    deps: CastDeps = CastDeps(),
) : ProtocolAdapter {

    init {
        require(receiver.protocol == CastProtocol.GoogleCast) {
            "GoogleCastAdapter constructed with non-GoogleCast receiver: ${receiver.protocol}"
        }
    }

    private val ownsScope: Boolean = deps.parentScope == null
    private val scope: CoroutineScope = deps.parentScope
        ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val sourceId: String =
        CastMessages.DEFAULT_SENDER_ID_PREFIX + UUID.randomUUID().toString().take(8)

    private val channel = CastV2Channel(receiver.host, receiver.port, parentScope = scope)

    private val _events = MutableSharedFlow<CastSessionEvent>(
        replay = 0, extraBufferCapacity = 64,
    )
    override val events: SharedFlow<CastSessionEvent> = _events.asSharedFlow()

    private val _currentCapabilities = MutableStateFlow(receiver.capabilities)
    override val currentCapabilities: StateFlow<Set<CastCapability>> =
        _currentCapabilities.asStateFlow()

    /** Monotonic request-id generator. Cast peers require unique ids within a session. */
    private val nextRequestId = AtomicInteger(1)
    private fun requestId(): Int = nextRequestId.getAndIncrement()

    private var transportId: String? = null
    private var sessionId: String? = null
    private var mediaSessionId: Int? = null

    private var listenerJob: Job? = null
    private var heartbeatJob: Job? = null
    private var lastReportedMediaState: CastMediaState? = null
    private var lastReportedDurationMs: Long? = null

    override suspend fun connect(): Result<Unit> = runCatching {
        _events.emit(CastSessionEvent.ConnectionStateChanged(CastConnectionState.Connecting))
        try {
            Timber.tag(TAG).e("connect: step 1 — TLS connect to %s:%d", channel.host, channel.port)
            channel.connect()
            Timber.tag(TAG).e("connect: step 2 — TLS OK, launching channel observer")
            listenerJob = scope.launch { observeChannel() }
            Timber.tag(TAG).e("connect: step 3 — sending CONNECT to receiver-0")
            sendOnConnection(CastMessages.DEFAULT_RECEIVER_ID, CastMessages.CONNECT)
            Timber.tag(TAG).e("connect: step 4 — starting heartbeat")
            startHeartbeat()
            Timber.tag(TAG).e("connect: step 5 — launching Default Media Receiver")
            launchDefaultMediaReceiver()
            Timber.tag(TAG).e("connect: step 6 — LAUNCH complete, connected!")
            _events.emit(CastSessionEvent.ConnectionStateChanged(CastConnectionState.Connected))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "connect: FAILED at some step")
            _events.emit(CastSessionEvent.Error(e.message ?: "Google Cast connect failed"))
            _events.emit(CastSessionEvent.ConnectionStateChanged(CastConnectionState.Failed))
            cleanup()
            throw e
        }
    }

    override suspend fun disconnect() {
        // Best-effort STOP on the running app + CLOSE on both virtual connections. None of these
        // are critical for our state — the socket close below is what actually ends the session.
        runCatching {
            val tid = transportId
            val mid = mediaSessionId
            if (tid != null) {
                if (mid != null) {
                    sendMedia(MediaCommand(type = CastMessages.STOP, requestId = requestId(), mediaSessionId = mid))
                }
                sendOnConnection(tid, CastMessages.CLOSE)
            }
            sendOnConnection(CastMessages.DEFAULT_RECEIVER_ID, CastMessages.CLOSE)
        }
        cleanup()
        _events.emit(CastSessionEvent.ConnectionStateChanged(CastConnectionState.Disconnected))
        if (ownsScope) scope.cancel()
    }

    override suspend fun load(media: CastMedia): Result<Unit> = runCatching {
        val tid = transportId ?: error("LAUNCH must complete before load()")
        val request = LoadRequest(
            requestId = requestId(),
            media = MediaInfo(
                contentId = media.url,
                contentType = media.contentType,
                streamType = "BUFFERED",
                duration = media.durationMs?.let { it / 1000.0 },
                metadata = MediaMetadata(
                    title = media.title,
                    subtitle = media.subtitle,
                    images = media.posterUrl?.let { listOf(MediaImage(it)) }.orEmpty(),
                ),
            ),
            autoplay = true,
            currentTime = if (media.startPositionMs == 0L) 0.1 else media.startPositionMs / 1000.0,
        )
        sendJson(
            destinationId = tid,
            namespace = CastNamespaces.MEDIA,
            json = CastJson.encodeToString(LoadRequest.serializer(), request),
        )
    }

    override suspend fun play(): Result<Unit> = mediaCommand(CastMessages.PLAY)

    override suspend fun pause(): Result<Unit> = mediaCommand(CastMessages.PAUSE)

    override suspend fun seek(positionMs: Long): Result<Unit> = runCatching {
        val tid = transportId ?: error("not connected")
        val mid = mediaSessionId ?: error("no active media session")
        val request = SeekRequest(
            requestId = requestId(),
            mediaSessionId = mid,
            currentTime = positionMs / 1000.0,
        )
        sendJson(
            destinationId = tid,
            namespace = CastNamespaces.MEDIA,
            json = CastJson.encodeToString(SeekRequest.serializer(), request),
        )
    }

    override suspend fun stop(): Result<Unit> = mediaCommand(CastMessages.STOP)

    override suspend fun setVolume(volume: Float): Result<Unit> = runCatching {
        val request = SetReceiverVolumeRequest(
            requestId = requestId(),
            volume = ReceiverVolume(level = volume.coerceIn(0f, 1f), muted = false),
        )
        sendJson(
            destinationId = CastMessages.DEFAULT_RECEIVER_ID,
            namespace = CastNamespaces.RECEIVER,
            json = CastJson.encodeToString(SetReceiverVolumeRequest.serializer(), request),
        )
    }

    /** Cast V2 has no playback-rate primitive on the Default Media Receiver. Refuse loudly. */
    override suspend fun setSpeed(speed: Float): Result<Unit> = Result.failure(
        UnsupportedOperationException(
            "Google Cast (Default Media Receiver) does not support variable playback speed",
        ),
    )

    // --- internal: state machine ---

    private suspend fun mediaCommand(type: String): Result<Unit> = runCatching {
        val tid = transportId ?: error("not connected")
        val mid = mediaSessionId ?: error("no active media session")
        sendJson(
            destinationId = tid,
            namespace = CastNamespaces.MEDIA,
            json = CastJson.encodeToString(
                MediaCommand.serializer(),
                MediaCommand(type = type, requestId = requestId(), mediaSessionId = mid),
            ),
        )
    }

    private suspend fun launchDefaultMediaReceiver() {
        val launchRequestId = requestId()
        val launch = LaunchRequest(
            requestId = launchRequestId,
            appId = CastMessages.DEFAULT_MEDIA_RECEIVER_APP_ID,
        )
        val statusDeferred = scope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            channel.incoming
                .filter { it.namespace == CastNamespaces.RECEIVER }
                .mapNotNull { it.payloadUtf8 }
                .mapNotNull {
                    runCatching {
                        CastJson.decodeFromString(ReceiverStatus.serializer(), it)
                    }.getOrNull()
                }
                .filter { it.type == CastMessages.RECEIVER_STATUS }
                .filter {
                    val app = it.status.applications.firstOrNull { a ->
                        a.appId == CastMessages.DEFAULT_MEDIA_RECEIVER_APP_ID
                    }
                    if (app == null) return@filter false
                    if (it.requestId == launchRequestId) return@filter true
                    it.requestId == 0
                }
                .first()
        }

        sendJson(
            destinationId = CastMessages.DEFAULT_RECEIVER_ID,
            namespace = CastNamespaces.RECEIVER,
            json = CastJson.encodeToString(LaunchRequest.serializer(), launch),
        )

        val status = withTimeout(LAUNCH_TIMEOUT_MS) {
            statusDeferred.await()
        }
        val app = status.status.applications.first { it.appId == CastMessages.DEFAULT_MEDIA_RECEIVER_APP_ID }
        transportId = app.transportId
        sessionId = app.sessionId
        Timber.tag(TAG).i(
            "LAUNCH ok app=%s transport=%s session=%s",
            app.appId, app.transportId, app.sessionId,
        )
        // Open the second virtual connection on the transportId destination so the receiver
        // routes media-namespace messages to the running Default Media Receiver app.
        sendOnConnection(app.transportId, CastMessages.CONNECT)
        sendJson(
            destinationId = app.transportId,
            namespace = CastNamespaces.MEDIA,
            json = CastJson.encodeToString(
                MediaGetStatusRequest.serializer(),
                MediaGetStatusRequest(requestId = requestId()),
            ),
        )
    }

    /**
     * Coroutine reader-loop side. Translates inbound frames into [CastSessionEvent]s and
     * handles the heartbeat echo. Cancellation = clean shutdown; channel-level errors are
     * surfaced via [_events] from [observeChannelErrors].
     */
    private suspend fun observeChannel() {
        scope.launch { observeChannelErrors() }
        channel.incoming.collect { message ->
            try {
                when (message.namespace) {
                    CastNamespaces.HEARTBEAT -> handleHeartbeat(message)
                    CastNamespaces.RECEIVER -> handleReceiverStatus(message)
                    CastNamespaces.MEDIA -> handleMediaStatus(message)
                    CastNamespaces.CONNECTION -> Unit // CLOSE arrives here; channel will go down
                    else -> Timber.tag(TAG).d("ignoring namespace=%s", message.namespace)
                }
            } catch (e: SerializationException) {
                Timber.tag(TAG).w(e, "decode failed namespace=%s", message.namespace)
            }
        }
    }

    private suspend fun observeChannelErrors() {
        channel.errors.collect { reason -> _events.emit(CastSessionEvent.Error(reason)) }
    }

    private suspend fun handleHeartbeat(message: CastMessage) {
        // Echo every PING with a PONG. Receivers drop the connection after ~10s with no
        // outbound activity, so this is non-optional even if we also drive our own PINGs.
        val payload = message.payloadUtf8 ?: return
        if (payload.contains("\"PING\"")) {
            channel.send(
                CastMessage(
                    sourceId = sourceId,
                    destinationId = message.sourceId.ifBlank { CastMessages.DEFAULT_RECEIVER_ID },
                    namespace = CastNamespaces.HEARTBEAT,
                    payloadUtf8 = buildPong(),
                ),
            )
        }
    }

    private fun handleReceiverStatus(message: CastMessage) {
        // RECEIVER_STATUS volume updates: receiver-level volume change (system-wide on the
        // Chromecast). Surface as VolumeChanged so the UI slider tracks.
        val payload = message.payloadUtf8 ?: return
        val status = runCatching {
            CastJson.decodeFromString(ReceiverStatus.serializer(), payload)
        }.getOrNull() ?: return
        // Capability widening: Cast V2 receivers always do Video + Audio + Volume + Seek + Subtitles.
        // We don't get post-handshake NativeAss here (Cast can't render libass) by design.
        if (_currentCapabilities.value.isEmpty()) {
            _currentCapabilities.value = setOf(
                CastCapability.Video, CastCapability.Audio,
                CastCapability.Volume, CastCapability.Seek, CastCapability.Subtitles,
            )
        }
        _events.tryEmit(CastSessionEvent.VolumeChanged(status.status.volume.level))
    }

    private suspend fun handleMediaStatus(message: CastMessage) {
        val payload = message.payloadUtf8 ?: return
        val env = runCatching {
            CastJson.decodeFromString(MediaStatusEnvelope.serializer(), payload)
        }.getOrNull() ?: return
        if (env.type != CastMessages.MEDIA_STATUS) return
        val item = env.status.firstOrNull() ?: return
        if (mediaSessionId == null) {
            mediaSessionId = item.mediaSessionId
            Timber.tag(TAG).i("media session opened id=%d", item.mediaSessionId)
        }
        val mediaState = when (item.playerState) {
            "PLAYING" -> CastMediaState.Playing
            "PAUSED" -> CastMediaState.Paused
            "BUFFERING" -> CastMediaState.Buffering
            "IDLE" -> CastMediaState.Idle
            else -> null
        }
        if (mediaState != null && mediaState != lastReportedMediaState) {
            _events.emit(CastSessionEvent.MediaStateChanged(mediaState))
            lastReportedMediaState = mediaState
            if (mediaState == CastMediaState.Idle && item.idleReason == "FINISHED") {
                _events.emit(CastSessionEvent.Ended)
            }
        }
        _events.emit(CastSessionEvent.PositionChanged((item.currentTime * 1000.0).toLong()))
        val duration = item.media?.duration
        if (duration != null) {
            val durationMs = (duration * 1000.0).toLong()
            if (durationMs != lastReportedDurationMs) {
                _events.emit(CastSessionEvent.DurationChanged(durationMs))
                lastReportedDurationMs = durationMs
            }
        }
    }

    /**
     * 5-second PING loop. Heartbeat namespace, destination = receiver-0 (no app affinity).
     * Cancellation drops out of the loop cleanly; channel errors propagate through
     * [_events] via [observeChannelErrors].
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                runCatching {
                    channel.send(
                        CastMessage(
                            sourceId = sourceId,
                            destinationId = CastMessages.DEFAULT_RECEIVER_ID,
                            namespace = CastNamespaces.HEARTBEAT,
                            payloadUtf8 = """{"type":"PING"}""",
                        ),
                    )
                }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private fun cleanup() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        listenerJob?.cancel()
        listenerJob = null
        channel.close()
        transportId = null
        sessionId = null
        mediaSessionId = null
    }

    // --- send helpers ---

    private suspend fun sendOnConnection(destinationId: String, type: String) {
        val json = CastJson.encodeToString(ConnectionMessage.serializer(), ConnectionMessage(type))
        sendJson(destinationId, CastNamespaces.CONNECTION, json)
    }

    private suspend fun sendMedia(command: MediaCommand) {
        val tid = transportId ?: return
        sendJson(
            destinationId = tid,
            namespace = CastNamespaces.MEDIA,
            json = CastJson.encodeToString(MediaCommand.serializer(), command),
        )
    }

    private suspend fun sendJson(destinationId: String, namespace: String, json: String) {
        channel.send(
            CastMessage(
                sourceId = sourceId,
                destinationId = destinationId,
                namespace = namespace,
                payloadUtf8 = json,
            ),
        )
    }

    private companion object {
        const val TAG = "GoogleCastAdapter"
        const val HEARTBEAT_INTERVAL_MS = 5_000L
        const val LAUNCH_TIMEOUT_MS = 10_000L
    }
}
