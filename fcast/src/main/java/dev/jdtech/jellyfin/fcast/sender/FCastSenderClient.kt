package dev.jdtech.jellyfin.fcast.sender

import dev.jdtech.jellyfin.fcast.protocol.FCAST_PROTOCOL_VERSION
import dev.jdtech.jellyfin.fcast.protocol.FCastFrame
import dev.jdtech.jellyfin.fcast.protocol.FCastMessage
import dev.jdtech.jellyfin.fcast.protocol.InitialReceiverMessage
import dev.jdtech.jellyfin.fcast.protocol.InitialSenderMessage
import dev.jdtech.jellyfin.fcast.protocol.PlayMessage
import dev.jdtech.jellyfin.fcast.protocol.PingMessage
import dev.jdtech.jellyfin.fcast.protocol.PlaybackUpdateMessage
import dev.jdtech.jellyfin.fcast.protocol.PongMessage
import dev.jdtech.jellyfin.fcast.protocol.ResumeMessage
import dev.jdtech.jellyfin.fcast.protocol.SeekMessage
import dev.jdtech.jellyfin.fcast.protocol.SetSpeedMessage
import dev.jdtech.jellyfin.fcast.protocol.SetVolumeMessage
import dev.jdtech.jellyfin.fcast.protocol.VersionMessage
import dev.jdtech.jellyfin.fcast.protocol.VolumeUpdateMessage
import android.os.SystemClock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Coroutine-based FCast sender. Owns a single TCP connection to one [receiver] at a time and
 * exposes typed suspend functions for the v1/v2 control opcodes plus a flow of inbound state
 * updates.
 *
 * Lifecycle: construct → [connect] → call play/pause/etc → [close]. The client is **not**
 * reusable after [close]. Construct a new one for a new target.
 *
 * Threading: all socket I/O runs on [Dispatchers.IO]. The reader loop is a child of the supplied
 * [parentScope] (or an internal scope if absent) so cancellation is structured.
 */
class FCastSenderClient(
    val receiver: FCastReceiver,
    parentScope: CoroutineScope? = null,
    private val senderInfo: InitialSenderMessage = InitialSenderMessage(
        displayName = "SpatialFin",
        appName = "SpatialFin",
    ),
    private val connectTimeoutMs: Int = 4_000,
    /**
     * Monotonic clock for RTT measurement. Defaults to [SystemClock.elapsedRealtime]; never
     * `System.currentTimeMillis()`, which can step (NTP/DST) between a Ping send and its Pong
     * and yield a negative or wildly inflated RTT that poisons split-A/V drift correction.
     * Injectable for tests.
     */
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) {

    enum class State { Idle, Connecting, Connected, Disconnected, Failed }

    private val ownsScope: Boolean = parentScope == null
    private val scope: CoroutineScope = parentScope
        ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private var input: DataInputStream? = null
    private var readerJob: Job? = null
    private val writeMutex = Mutex()

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _negotiatedVersion = MutableStateFlow<Int?>(null)
    val negotiatedVersion: StateFlow<Int?> = _negotiatedVersion.asStateFlow()

    private val _playbackUpdates = MutableSharedFlow<PlaybackUpdateMessage>(
        replay = 1, extraBufferCapacity = 32,
    )
    val playbackUpdates: SharedFlow<PlaybackUpdateMessage> = _playbackUpdates.asSharedFlow()

    private val _volumeUpdates = MutableSharedFlow<VolumeUpdateMessage>(
        replay = 1, extraBufferCapacity = 32,
    )
    val volumeUpdates: SharedFlow<VolumeUpdateMessage> = _volumeUpdates.asSharedFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    /**
     * Emitted once when the receiver replies with its [FCastMessage.Initial] frame. Adapters
     * (`FCastAdapter`) observe this to learn the peer's `appName` / `appVersion` so they can
     * widen the post-handshake capability set — e.g. granting [NativeAss] / [EmbeddedFonts]
     * only when the peer is another SpatialFin install. `replay = 1` so a late subscriber that
     * connects after the handshake still sees the value.
     */
    private val _initialReceiver = MutableSharedFlow<InitialReceiverMessage>(
        replay = 1, extraBufferCapacity = 1,
    )
    val initialReceiver: SharedFlow<InitialReceiverMessage> = _initialReceiver.asSharedFlow()

    /**
     * Observation emitted when a [FCastMessage.Pong] arrives in response to one of our
     * [ping] calls. Pairs the monotonic time of our original Ping send with the monotonic
     * time we received the Pong so consumers can compute round-trip time. Used by split-A/V
     * sync to estimate network one-way delay.
     */
    data class PongObservation(
        /** Monotonic clock at Ping send (NTP t1). */
        val pingSentWallMs: Long,
        /** Monotonic clock at Pong receipt (NTP t4). */
        val pongReceivedWallMs: Long,
        /** Receiver monotonic clock when it read the Ping (NTP t2). Null on a legacy
         *  body-less Pong (pre-v4 / non-SpatialFin peer). */
        val receiverRecvMs: Long? = null,
        /** Receiver monotonic clock when it wrote the Pong (NTP t3). Null on legacy Pong. */
        val receiverSendMs: Long? = null,
    ) {
        val rttMs: Long get() = pongReceivedWallMs - pingSentWallMs

        /** True when the four-timestamp NTP set is present (v4 SpatialFin peer). */
        val hasClockSync: Boolean get() = receiverRecvMs != null && receiverSendMs != null
    }

    private val _pongs = MutableSharedFlow<PongObservation>(extraBufferCapacity = 8)
    val pongs: SharedFlow<PongObservation> = _pongs.asSharedFlow()

    /** Wall-clock at which the most recent Ping was sent. Used as fallback for legacy Pongs. */
    private var lastPingSentWallMs: Long? = null

    /** In-flight pings for robust pairing (v4). Map of send-time -> send-time. */
    private val pendingPings = ConcurrentHashMap<Long, Long>()

    /**
     * Open the socket, send Version + Initial. Suspends until the handshake is written; the
     * background reader is launched and starts feeding [playbackUpdates] / [volumeUpdates] etc.
     * Throws [IOException] on connect failure.
     */
    suspend fun connect() {
        check(_state.value == State.Idle) { "FCastSenderClient already connected (state=${_state.value})" }
        _state.value = State.Connecting
        try {
            val s = withContext(Dispatchers.IO) {
                Socket().apply {
                    tcpNoDelay = true
                    keepAlive = true
                    connect(InetSocketAddress(receiver.host, receiver.port), connectTimeoutMs)
                }
            }
            socket = s
            output = DataOutputStream(s.getOutputStream().buffered())
            input = DataInputStream(s.getInputStream().buffered())

            // Send Version then Initial per spec ordering (Version first so the peer can downgrade).
            sendInternal(FCastMessage.Version(VersionMessage(FCAST_PROTOCOL_VERSION)))
            sendInternal(FCastMessage.Initial(
                dev.jdtech.jellyfin.fcast.protocol.InitialReceiverMessage(
                    displayName = senderInfo.displayName,
                    appName = senderInfo.appName,
                    appVersion = senderInfo.appVersion,
                ),
            ))

            readerJob = scope.launch { readLoop() }
            _state.value = State.Connected
        } catch (e: Exception) {
            _state.value = State.Failed
            closeQuietly()
            throw e
        }
    }

    /** True once the Version handshake settled on protocol ≥ 4 (peer is a v4 SpatialFin
     *  build). Gates emission of every v4 optional body so we never send one to a peer that
     *  negotiated v3 — keeps the wire byte-identical to v3 for them. */
    private fun negotiatedV4(): Boolean = (_negotiatedVersion.value ?: 0) >= 4

    suspend fun play(payload: PlayMessage) = sendInternal(FCastMessage.Play(payload))
    suspend fun pause() = sendInternal(FCastMessage.Pause)
    suspend fun resume() = sendInternal(FCastMessage.Resume())

    /**
     * v4 synchronized resume: ask the receiver to begin playback when *its* monotonic clock
     * reaches [atReceiverMonotonicMs]. Degrades to a plain resume-now against a pre-v4 peer
     * (which would mis-parse an unexpected body).
     */
    suspend fun resumeAt(atReceiverMonotonicMs: Long) =
        if (negotiatedV4()) {
            sendInternal(FCastMessage.Resume(ResumeMessage(atReceiverMonotonicMs)))
        } else {
            sendInternal(FCastMessage.Resume())
        }

    suspend fun stop() = sendInternal(FCastMessage.Stop)
    suspend fun seek(seconds: Double) = sendInternal(FCastMessage.Seek(SeekMessage(seconds)))
    suspend fun setVolume(volume: Double) =
        sendInternal(FCastMessage.SetVolume(SetVolumeMessage(volume.coerceIn(0.0, 1.0))))
    suspend fun setSpeed(speed: Double) = sendInternal(FCastMessage.SetSpeed(SetSpeedMessage(speed)))

    /**
     * Send a Ping. Captures the wall-clock time of the send so the corresponding Pong (if any)
     * can be paired with it via [pongs] for RTT measurement. Capturing immediately before
     * write is acceptable: the kernel buffer write is microseconds and not user-perceivable.
     */
    suspend fun ping() {
        val t1 = nowMs()
        if (negotiatedV4()) {
            pendingPings[t1] = t1
            sendInternal(FCastMessage.Ping(PingMessage(t1)))
        } else {
            lastPingSentWallMs = t1
            sendInternal(FCastMessage.Ping(null))
        }
    }

    /**
     * Send any [FCastMessage]. Public for protocol features the typed surface doesn't cover yet
     * (event subscriptions, playlist navigation). Suspends until the bytes are flushed.
     */
    suspend fun send(message: FCastMessage) = sendInternal(message)

    private suspend fun sendInternal(message: FCastMessage) {
        val out = output ?: throw IOException("FCast sender not connected")
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                FCastFrame.write(out, message)
            }
        }
    }

    private suspend fun readLoop() {
        val stream = input ?: return
        try {
            while (true) {
                val msg = withContext(Dispatchers.IO) { FCastFrame.read(stream) } ?: break
                dispatch(msg)
            }
        } catch (e: IOException) {
            Timber.tag(TAG).w(e, "FCast read loop terminated")
            _errors.tryEmit(e.message ?: "read error")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "FCast read loop crashed")
            _errors.tryEmit(e.message ?: "decode error")
        } finally {
            _state.value = State.Disconnected
            closeQuietly()
        }
    }

    private suspend fun dispatch(msg: FCastMessage) {
        when (msg) {
            is FCastMessage.PlaybackUpdate -> _playbackUpdates.emit(msg.payload)
            is FCastMessage.VolumeUpdate -> _volumeUpdates.emit(msg.payload)
            is FCastMessage.PlaybackError -> _errors.emit(msg.payload.message)
            is FCastMessage.Version -> {
                val peer = msg.payload.version
                _negotiatedVersion.value = minOf(FCAST_PROTOCOL_VERSION, peer)
            }
            is FCastMessage.Ping -> {
                // A peer can ping us too; echo NTP timestamps when it sent them so it can
                // solve offset against us, otherwise reply body-less (legacy).
                val t1 = msg.payload?.t1
                if (t1 != null) {
                    val t2 = nowMs()
                    sendInternal(FCastMessage.Pong(PongMessage(t1 = t1, t2 = t2, t3 = nowMs())))
                } else {
                    sendInternal(FCastMessage.Pong())
                }
            }
            is FCastMessage.Pong -> {
                val t4 = nowMs()
                val p = msg.payload
                // v4: trust the echoed t1 (pairs even if pings overlapped); legacy: fall
                // back to the single outstanding-ping slot.
                val sent = if (p?.t1 != null) {
                    pendingPings.remove(p.t1)
                } else {
                    val fallback = lastPingSentWallMs
                    lastPingSentWallMs = null
                    fallback
                }
                if (sent != null) {
                    _pongs.tryEmit(
                        PongObservation(
                            pingSentWallMs = sent,
                            pongReceivedWallMs = t4,
                            receiverRecvMs = p?.t2,
                            receiverSendMs = p?.t3,
                        ),
                    )
                }
            }
            is FCastMessage.Initial -> _initialReceiver.tryEmit(msg.payload)
            // PlayUpdate, Event, etc. are observed but not yet acted on.
            else -> Unit
        }
    }

    /** Close the socket and stop the reader. Idempotent. */
    fun close() {
        readerJob?.cancel()
        readerJob = null
        closeQuietly()
        _state.value = State.Disconnected
        if (ownsScope) scope.cancel()
    }

    private fun closeQuietly() {
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        input = null
        output = null
        socket = null
    }

    private companion object {
        const val TAG = "FCastSender"
    }
}
