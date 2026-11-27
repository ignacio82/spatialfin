package dev.jdtech.jellyfin.cast.adapter.sendspinavr

import android.os.SystemClock
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrRequestValidator.ValidationResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber

/**
 * Bounded sliding-window min-RTT clock synchronizer between Android `CLOCK_BOOTTIME` (`Clock A`)
 * and `spatialfin-avr` (`Clock B`), estimating `theta_us` such that
 * `t_avr_us ≈ t_client_us + theta_us` along with a conservative `uncertainty_us`.
 */
class SendspinAvrClockSync(
    private val maxSamples: Int = DEFAULT_MAX_SAMPLES,
    private val windowUs: Long = DEFAULT_WINDOW_US,
    private val driftPpmBound: Double = DEFAULT_DRIFT_PPM_BOUND,
) {
    data class Sample(
        val t1ClientUs: Long,
        val t2AvrRxUs: Long,
        val t3AvrTxUs: Long,
        val t4ClientRxUs: Long,
        val rttUs: Long,
        val thetaUs: Long,
    )

    data class Snapshot(
        val thetaUs: Long,
        val minRttUs: Long,
        val uncertaintyUs: Long,
        val sampleCount: Int,
        val lastSampleClientUs: Long,
    )

    private val lock = Any()
    private val samples = ArrayDeque<Sample>()

    fun recordSample(
        t1ClientUs: Long,
        t2AvrRxUs: Long,
        t3AvrTxUs: Long,
        t4ClientRxUs: Long,
    ): Snapshot = synchronized(lock) {
        val rawRttUs = (t4ClientRxUs - t1ClientUs) - (t3AvrTxUs - t2AvrRxUs)
        val rttUs = rawRttUs.coerceAtLeast(0L)
        val thetaUs = ((t2AvrRxUs - t1ClientUs) + (t3AvrTxUs - t4ClientRxUs)) / 2L
        samples.addLast(
            Sample(
                t1ClientUs = t1ClientUs,
                t2AvrRxUs = t2AvrRxUs,
                t3AvrTxUs = t3AvrTxUs,
                t4ClientRxUs = t4ClientRxUs,
                rttUs = rttUs,
                thetaUs = thetaUs,
            ),
        )
        evictLocked(nowClientUs = t4ClientRxUs)
        snapshotLocked(nowClientUs = t4ClientRxUs)
    }

    fun snapshot(nowClientUs: Long): Snapshot = synchronized(lock) {
        evictLocked(nowClientUs = nowClientUs)
        snapshotLocked(nowClientUs = nowClientUs)
    }

    fun reset() = synchronized(lock) {
        samples.clear()
    }

    private fun evictLocked(nowClientUs: Long) {
        while (samples.size > 1 && nowClientUs - samples.first().t4ClientRxUs > windowUs) {
            samples.removeFirst()
        }
        while (samples.size > maxSamples) {
            samples.removeFirst()
        }
    }

    private fun snapshotLocked(nowClientUs: Long): Snapshot {
        if (samples.isEmpty()) {
            return Snapshot(
                thetaUs = 0L,
                minRttUs = 0L,
                uncertaintyUs = Long.MAX_VALUE,
                sampleCount = 0,
                lastSampleClientUs = 0L,
            )
        }
        val best = samples.minBy { it.rttUs }
        val maxRtt = samples.maxOf { it.rttUs }
        val rttSpreadUs = (maxRtt - best.rttUs).coerceAtLeast(0L)
        val ageUs = (nowClientUs - best.t4ClientRxUs).coerceAtLeast(0L)
        val ageDriftUs = ((ageUs.toDouble() / 1_000_000.0) * driftPpmBound).toLong()
        return Snapshot(
            thetaUs = best.thetaUs,
            minRttUs = best.rttUs,
            uncertaintyUs = (rttSpreadUs / 2L) + ageDriftUs,
            sampleCount = samples.size,
            lastSampleClientUs = samples.last().t4ClientRxUs,
        )
    }

    companion object {
        const val DEFAULT_MAX_SAMPLES = 16
        const val DEFAULT_WINDOW_US = 30_000_000L
        const val DEFAULT_DRIFT_PPM_BOUND = 50.0
    }
}

sealed interface SendspinAvrPushEvent {
    data class TelemetrySession(val sessionId: String?, val payload: TelemetrySessionPayload) : SendspinAvrPushEvent
    data class PlaybackResyncRequired(val sessionId: String?, val payload: PlaybackResyncRequiredPayload) : SendspinAvrPushEvent
    data class SessionReleased(val sessionId: String?, val payload: SessionReleasedPayload) : SendspinAvrPushEvent
    data class RoomStateChanged(val sessionId: String?, val payload: RoomStateChangedPayload) : SendspinAvrPushEvent
    data class RoomProfileUpdated(val payload: RoomProfileUpdatedPayload) : SendspinAvrPushEvent
    data class SpeakerDegraded(val sessionId: String?, val payload: SpeakerDegradedPayload) : SendspinAvrPushEvent
    data class SpeakerRestored(val sessionId: String?, val payload: SpeakerRestoredPayload) : SendspinAvrPushEvent
    data class DecoderFallback(val sessionId: String?, val payload: DecoderFallbackPayload) : SendspinAvrPushEvent
    data class MaStateChanged(val payload: MaStateChangedPayload) : SendspinAvrPushEvent
    data class Disconnected(val code: Int, val reason: String) : SendspinAvrPushEvent
}

class SendspinAvrRpcException(
    val code: String,
    val rpcMessage: String,
    val retryable: Boolean = false,
    val details: JsonObject? = null,
) : Exception("SendspinAvr RPC error [$code]: $rpcMessage")

/**
 * WebSocket `/ws/v1/control` transport for Spec 0 Theater Audio v1.
 *
 * Implements Bearer upgrade authentication, uniform `id`-correlated `rpc.ok` / `rpc.error`
 * replies, bounded sliding-window `clock.ping` synchronization, `5 s` `playback.ready` barriers,
 * `256 KiB` inbound frame enforcement (`1009` close), and one-way `playback.video_timing` /
 * `session.heartbeat` notifications.
 */
class SendspinAvrTransport(
    okHttpClient: OkHttpClient,
    val clockSync: SendspinAvrClockSync = SendspinAvrClockSync(),
    private val clockUs: () -> Long = DEFAULT_BOOTTIME_CLOCK_US,
    private val allowedJellyfinHost: String? = null,
) {
    private val client: OkHttpClient = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val json = SendspinAvrJson.codec
    private val nextRequestSeq = AtomicLong(1L)
    private val pendingRpcs = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()

    private var transportScope: CoroutineScope? = null
    private var maintenancePingJob: Job? = null
    @Volatile
    private var activeSocket: WebSocket? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _pushEvents = MutableSharedFlow<SendspinAvrPushEvent>(extraBufferCapacity = 64)
    val pushEvents: SharedFlow<SendspinAvrPushEvent> = _pushEvents.asSharedFlow()

    suspend fun connect(
        endpoint: SendspinAvrHttpClient.Endpoint,
        bearerToken: String,
        hello: HelloRequestPayload,
        initialClockSamples: Int = DEFAULT_INITIAL_CLOCK_SAMPLES,
        pingIntervalMs: Long = DEFAULT_PING_INTERVAL_MS,
        rpcTimeoutMs: Long = DEFAULT_RPC_TIMEOUT_MS,
    ): HelloOkPayload {
        val tokenCheck = SendspinAvrRequestValidator.validateToken(bearerToken, "avr_token")
        if (tokenCheck is ValidationResult.Invalid) {
            throw SendspinAvrRpcException(tokenCheck.errorCode, tokenCheck.reason)
        }
        val helloCheck = SendspinAvrRequestValidator.validateHello(hello)
        if (helloCheck is ValidationResult.Invalid) {
            throw SendspinAvrRpcException(helloCheck.errorCode, helloCheck.reason)
        }

        disconnect()
        clockSync.reset()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        transportScope = scope

        val request = Request.Builder()
            .url(endpoint.baseWsUrl())
            .header("Authorization", "Bearer $bearerToken")
            .build()

        val openResult = CompletableDeferred<Unit>()
        val socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    activeSocket = webSocket
                    _connected.value = true
                    openResult.complete(Unit)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.toByteArray(Charsets.UTF_8).size > TheaterAudioProtocol.MAX_WS_FRAME_BYTES) {
                        webSocket.close(WS_CLOSE_MESSAGE_TOO_BIG, "Message Too Big")
                        handleSocketClosed(WS_CLOSE_MESSAGE_TOO_BIG, "Oversized WS frame > 256 KiB")
                        return
                    }
                    handleIncomingText(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    handleSocketClosed(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val failure = SendspinAvrRpcException(
                        code = if (response?.code == 401) "auth_invalid" else "internal_error",
                        rpcMessage = t.message ?: "WebSocket connection failed",
                        retryable = response?.code != 401,
                    )
                    if (!openResult.isCompleted) {
                        openResult.completeExceptionally(failure)
                    }
                    failAllPending(failure)
                    _connected.value = false
                    _pushEvents.tryEmit(SendspinAvrPushEvent.Disconnected(1006, failure.rpcMessage))
                }
            },
        )
        activeSocket = socket

        try {
            withTimeout(rpcTimeoutMs) { openResult.await() }
            val helloOk = sendRpc(
                msgType = "hello",
                sessionId = null,
                payload = hello,
                requestSerializer = HelloRequestPayload.serializer(),
                replySerializer = HelloOkPayload.serializer(),
                timeoutMs = rpcTimeoutMs,
            )
            repeat(initialClockSamples) {
                ping(timeoutMs = rpcTimeoutMs)
            }
            if (pingIntervalMs > 0L) {
                maintenancePingJob = scope.launch {
                    while (isActive && _connected.value) {
                        delay(pingIntervalMs)
                        runCatching { ping(timeoutMs = rpcTimeoutMs) }
                            .onFailure { err ->
                                if (err is CancellationException) throw err
                                Timber.tag(TAG).d(err, "Maintenance clock.ping failed")
                            }
                    }
                }
            }
            return helloOk
        } catch (e: Throwable) {
            disconnect()
            throw e
        }
    }

    suspend fun ping(
        sessionId: String? = null,
        timeoutMs: Long = DEFAULT_RPC_TIMEOUT_MS,
    ): SendspinAvrClockSync.Snapshot {
        val t1 = clockUs()
        val reply = sendRpc(
            msgType = "clock.ping",
            sessionId = sessionId,
            payload = ClockPingPayload(t1ClientUs = t1),
            requestSerializer = ClockPingPayload.serializer(),
            replySerializer = ClockPingOkPayload.serializer(),
            timeoutMs = timeoutMs,
        )
        val t4 = clockUs()
        return clockSync.recordSample(
            t1ClientUs = reply.t1ClientUs,
            t2AvrRxUs = reply.t2AvrRxUs,
            t3AvrTxUs = reply.t3AvrTxUs,
            t4ClientRxUs = t4,
        )
    }

    suspend fun startSession(
        sessionId: String,
        payload: SessionStartPayload,
        timeoutMs: Long = DEFAULT_RPC_TIMEOUT_MS,
    ): SessionStartOkPayload {
        val check = SendspinAvrRequestValidator.validateSessionStart(payload, allowedJellyfinHost)
        if (check is ValidationResult.Invalid) {
            throw SendspinAvrRpcException(check.errorCode, check.reason)
        }
        return sendRpc(
            msgType = "session.start",
            sessionId = sessionId,
            payload = payload,
            requestSerializer = SessionStartPayload.serializer(),
            replySerializer = SessionStartOkPayload.serializer(),
            timeoutMs = timeoutMs,
        )
    }

    suspend fun loadMedia(
        sessionId: String,
        payload: PlaybackLoadPayload,
        timeoutMs: Long = DEFAULT_RPC_TIMEOUT_MS,
    ): PlaybackLoadOkPayload {
        val check = SendspinAvrRequestValidator.validatePlaybackLoad(payload, allowedJellyfinHost)
        if (check is ValidationResult.Invalid) {
            throw SendspinAvrRpcException(check.errorCode, check.reason)
        }
        return sendRpc(
            msgType = "playback.load",
            sessionId = sessionId,
            payload = payload,
            requestSerializer = PlaybackLoadPayload.serializer(),
            replySerializer = PlaybackLoadOkPayload.serializer(),
            timeoutMs = timeoutMs,
        )
    }

    suspend fun signalReady(
        sessionId: String,
        barrierSeq: Long,
        actualPtsUs: Long,
        timeoutMs: Long = PLAYBACK_READY_TIMEOUT_MS,
    ): PlaybackReadyOkPayload = sendRpc(
        msgType = "playback.ready",
        sessionId = sessionId,
        payload = PlaybackReadyPayload(barrierSeq = barrierSeq, actualPtsUs = actualPtsUs),
        requestSerializer = PlaybackReadyPayload.serializer(),
        replySerializer = PlaybackReadyOkPayload.serializer(),
        timeoutMs = timeoutMs,
    )

    suspend fun pause(
        sessionId: String,
        barrierSeq: Long,
        flush: Boolean = true,
        timeoutMs: Long = DEFAULT_RPC_TIMEOUT_MS,
    ): PlaybackPauseOkPayload = sendRpc(
        msgType = "playback.pause",
        sessionId = sessionId,
        payload = PlaybackPausePayload(barrierSeq = barrierSeq, flush = flush),
        requestSerializer = PlaybackPausePayload.serializer(),
        replySerializer = PlaybackPauseOkPayload.serializer(),
        timeoutMs = timeoutMs,
    )

    suspend fun stall(
        sessionId: String,
        barrierSeq: Long,
        timeoutMs: Long = DEFAULT_RPC_TIMEOUT_MS,
    ): PlaybackPauseOkPayload = sendRpc(
        msgType = "playback.stall",
        sessionId = sessionId,
        payload = PlaybackStallPayload(barrierSeq = barrierSeq),
        requestSerializer = PlaybackStallPayload.serializer(),
        replySerializer = PlaybackPauseOkPayload.serializer(),
        timeoutMs = timeoutMs,
    )

    suspend fun setVolume(
        sessionId: String?,
        volume: Double,
        muted: Boolean,
        timeoutMs: Long = DEFAULT_RPC_TIMEOUT_MS,
    ): RoomSetVolumeOkPayload = sendRpc(
        msgType = "room.set_volume",
        sessionId = sessionId,
        payload = RoomSetVolumePayload(volume = volume.coerceIn(0.0, 1.0), muted = muted),
        requestSerializer = RoomSetVolumePayload.serializer(),
        replySerializer = RoomSetVolumeOkPayload.serializer(),
        timeoutMs = timeoutMs,
    )

    suspend fun setEqBypass(
        sessionId: String?,
        bypass: Boolean,
        timeoutMs: Long = DEFAULT_RPC_TIMEOUT_MS,
    ): RoomSetEqBypassOkPayload = sendRpc(
        msgType = "room.set_eq_bypass",
        sessionId = sessionId,
        payload = RoomSetEqBypassPayload(bypass = bypass),
        requestSerializer = RoomSetEqBypassPayload.serializer(),
        replySerializer = RoomSetEqBypassOkPayload.serializer(),
        timeoutMs = timeoutMs,
    )

    suspend fun setUpmixMode(
        sessionId: String?,
        mode: String,
        timeoutMs: Long = DEFAULT_RPC_TIMEOUT_MS,
    ): RoomSetUpmixModeOkPayload = sendRpc(
        msgType = "room.set_upmix_mode",
        sessionId = sessionId,
        payload = RoomSetUpmixModePayload(mode = mode),
        requestSerializer = RoomSetUpmixModePayload.serializer(),
        replySerializer = RoomSetUpmixModeOkPayload.serializer(),
        timeoutMs = timeoutMs,
    )

    suspend fun centerSnap(
        sessionId: String?,
        durationMs: Int = 200,
        gainDbfs: Double = -18.0,
        timeoutMs: Long = DEFAULT_RPC_TIMEOUT_MS,
    ): CalibrationCenterSnapOkPayload = sendRpc(
        msgType = "calibration.center_snap",
        sessionId = sessionId,
        payload = CalibrationCenterSnapPayload(durationMs = durationMs, gainDbfs = gainDbfs),
        requestSerializer = CalibrationCenterSnapPayload.serializer(),
        replySerializer = CalibrationCenterSnapOkPayload.serializer(),
        timeoutMs = timeoutMs,
    )

    suspend fun lipSyncChirp(
        sessionId: String?,
        payload: CalibrationLipSyncChirpPayload,
        timeoutMs: Long = DEFAULT_RPC_TIMEOUT_MS,
    ): CalibrationLipSyncChirpOkPayload = sendRpc(
        msgType = "calibration.lip_sync_chirp",
        sessionId = sessionId,
        payload = payload,
        requestSerializer = CalibrationLipSyncChirpPayload.serializer(),
        replySerializer = CalibrationLipSyncChirpOkPayload.serializer(),
        timeoutMs = timeoutMs,
    )

    suspend fun stopSession(
        sessionId: String,
        reason: String,
        timeoutMs: Long = STOP_SESSION_TIMEOUT_MS,
    ): SessionStopOkPayload = sendRpc(
        msgType = "session.stop",
        sessionId = sessionId,
        payload = SessionStopPayload(reason = reason),
        requestSerializer = SessionStopPayload.serializer(),
        replySerializer = SessionStopOkPayload.serializer(),
        timeoutMs = timeoutMs,
    )

    fun sendVideoTiming(
        sessionId: String,
        barrierSeq: Long,
        mediaPtsUs: Long,
        visibleAvrUs: Long,
        clockUncertaintyUs: Long,
    ): Boolean = sendOneWay(
        msgType = "playback.video_timing",
        sessionId = sessionId,
        payload = PlaybackVideoTimingPayload(
            barrierSeq = barrierSeq,
            mediaPtsUs = mediaPtsUs,
            visibleAvrUs = visibleAvrUs,
            clockUncertaintyUs = clockUncertaintyUs,
        ),
        payloadSerializer = PlaybackVideoTimingPayload.serializer(),
    )

    fun sendHeartbeat(
        sessionId: String,
        barrierSeq: Long,
        state: String,
    ): Boolean = sendOneWay(
        msgType = "session.heartbeat",
        sessionId = sessionId,
        payload = SessionHeartbeatPayload(barrierSeq = barrierSeq, state = state),
        payloadSerializer = SessionHeartbeatPayload.serializer(),
    )

    fun disconnect() {
        maintenancePingJob?.cancel()
        maintenancePingJob = null
        transportScope?.cancel()
        transportScope = null
        val socket = activeSocket
        activeSocket = null
        _connected.value = false
        failAllPending(SendspinAvrRpcException("internal_error", "Transport disconnected", retryable = true))
        runCatching { socket?.close(1000, "Client disconnect") }
    }

    private fun <P> sendOneWay(
        msgType: String,
        sessionId: String?,
        payload: P,
        payloadSerializer: KSerializer<P>,
    ): Boolean {
        val socket = activeSocket ?: return false
        val envelope = SendspinAvrEnvelope(
            type = msgType,
            id = null,
            sessionId = sessionId,
            tsUs = clockUs(),
            payload = payload,
        )
        val encoded = json.encodeToString(SendspinAvrEnvelope.serializer(payloadSerializer), envelope)
        return socket.send(encoded)
    }

    private suspend fun <Req, Res> sendRpc(
        msgType: String,
        sessionId: String?,
        payload: Req,
        requestSerializer: KSerializer<Req>,
        replySerializer: KSerializer<Res>,
        timeoutMs: Long,
    ): Res {
        val socket = activeSocket ?: throw SendspinAvrRpcException("internal_error", "WebSocket not connected", retryable = true)
        val id = "req-${nextRequestSeq.getAndIncrement()}"
        val deferred = CompletableDeferred<JsonElement>()
        pendingRpcs[id] = deferred

        val envelope = SendspinAvrEnvelope(
            type = msgType,
            id = id,
            sessionId = sessionId,
            tsUs = clockUs(),
            payload = payload,
        )
        val encoded = json.encodeToString(SendspinAvrEnvelope.serializer(requestSerializer), envelope)
        if (!socket.send(encoded)) {
            pendingRpcs.remove(id)
            throw SendspinAvrRpcException("internal_error", "Failed to enqueue WS frame for $msgType", retryable = true)
        }

        val replyPayload: JsonElement = try {
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } finally {
            pendingRpcs.remove(id)
        }
        return json.decodeFromJsonElement(replySerializer, replyPayload)
    }

    private fun handleIncomingText(text: String) {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
            Timber.tag(TAG).w(it, "Ignoring malformed WS JSON frame")
            return
        }
        val msgType = root["type"]?.jsonPrimitive?.content ?: return
        val id = root["id"]?.jsonPrimitive?.content
        val sessionId = root["session_id"]?.jsonPrimitive?.content
        val payload = root["payload"] ?: return

        when (msgType) {
            "rpc.ok" -> {
                if (id != null) pendingRpcs.remove(id)?.complete(payload)
            }
            "rpc.error" -> {
                val err = runCatching { json.decodeFromJsonElement(RpcErrorPayload.serializer(), payload) }
                    .getOrNull()
                val ex = SendspinAvrRpcException(
                    code = err?.code ?: "internal_error",
                    rpcMessage = err?.message ?: "RPC error",
                    retryable = err?.retryable ?: false,
                    details = err?.details,
                )
                if (id != null) {
                    pendingRpcs.remove(id)?.completeExceptionally(ex)
                }
            }
            "telemetry.session" -> decodeAndEmit(payload, TelemetrySessionPayload.serializer()) {
                SendspinAvrPushEvent.TelemetrySession(sessionId, it)
            }
            "playback.resync_required" -> decodeAndEmit(payload, PlaybackResyncRequiredPayload.serializer()) {
                SendspinAvrPushEvent.PlaybackResyncRequired(sessionId, it)
            }
            "session.released" -> decodeAndEmit(payload, SessionReleasedPayload.serializer()) {
                SendspinAvrPushEvent.SessionReleased(sessionId, it)
            }
            "room.state_changed" -> decodeAndEmit(payload, RoomStateChangedPayload.serializer()) {
                SendspinAvrPushEvent.RoomStateChanged(sessionId, it)
            }
            "room.profile_updated" -> decodeAndEmit(payload, RoomProfileUpdatedPayload.serializer()) {
                SendspinAvrPushEvent.RoomProfileUpdated(it)
            }
            "speaker.degraded" -> decodeAndEmit(payload, SpeakerDegradedPayload.serializer()) {
                SendspinAvrPushEvent.SpeakerDegraded(sessionId, it)
            }
            "speaker.restored" -> decodeAndEmit(payload, SpeakerRestoredPayload.serializer()) {
                SendspinAvrPushEvent.SpeakerRestored(sessionId, it)
            }
            "decoder.fallback" -> decodeAndEmit(payload, DecoderFallbackPayload.serializer()) {
                SendspinAvrPushEvent.DecoderFallback(sessionId, it)
            }
            "ma.state_changed" -> decodeAndEmit(payload, MaStateChangedPayload.serializer()) {
                SendspinAvrPushEvent.MaStateChanged(it)
            }
            else -> Timber.tag(TAG).d("Ignoring unknown future WS event type=%s", msgType)
        }
    }

    private inline fun <T> decodeAndEmit(
        payload: JsonElement,
        serializer: KSerializer<T>,
        wrap: (T) -> SendspinAvrPushEvent,
    ) {
        val decoded = runCatching { json.decodeFromJsonElement(serializer, payload) }
            .onFailure { Timber.tag(TAG).w(it, "Failed to decode WS push payload") }
            .getOrNull() ?: return
        _pushEvents.tryEmit(wrap(decoded))
    }

    private fun handleSocketClosed(code: Int, reason: String) {
        activeSocket = null
        _connected.value = false
        failAllPending(SendspinAvrRpcException("internal_error", "WebSocket closed ($code): $reason", retryable = true))
        _pushEvents.tryEmit(SendspinAvrPushEvent.Disconnected(code, reason))
    }

    private fun failAllPending(error: Throwable) {
        val keys = pendingRpcs.keys.toList()
        for (key in keys) {
            pendingRpcs.remove(key)?.completeExceptionally(error)
        }
    }

    companion object {
        const val DEFAULT_INITIAL_CLOCK_SAMPLES = 8
        const val DEFAULT_PING_INTERVAL_MS = 1_000L
        const val DEFAULT_RPC_TIMEOUT_MS = 3_000L
        const val PLAYBACK_READY_TIMEOUT_MS = 5_000L
        const val STOP_SESSION_TIMEOUT_MS = 200L
        const val WS_CLOSE_MESSAGE_TOO_BIG = 1009
        private const val TAG = "SendspinAvrTransport"

        val DEFAULT_BOOTTIME_CLOCK_US: () -> Long = {
            runCatching { SystemClock.elapsedRealtimeNanos() / 1_000L }
                .getOrElse { System.nanoTime() / 1_000L }
        }
    }
}
