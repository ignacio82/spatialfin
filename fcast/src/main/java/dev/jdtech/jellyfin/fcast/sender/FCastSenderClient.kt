package dev.jdtech.jellyfin.fcast.sender

import dev.jdtech.jellyfin.fcast.protocol.FCAST_PROTOCOL_VERSION
import dev.jdtech.jellyfin.fcast.protocol.FCastFrame
import dev.jdtech.jellyfin.fcast.protocol.FCastMessage
import dev.jdtech.jellyfin.fcast.protocol.InitialSenderMessage
import dev.jdtech.jellyfin.fcast.protocol.PlayMessage
import dev.jdtech.jellyfin.fcast.protocol.PlaybackUpdateMessage
import dev.jdtech.jellyfin.fcast.protocol.SeekMessage
import dev.jdtech.jellyfin.fcast.protocol.SetSpeedMessage
import dev.jdtech.jellyfin.fcast.protocol.SetVolumeMessage
import dev.jdtech.jellyfin.fcast.protocol.VersionMessage
import dev.jdtech.jellyfin.fcast.protocol.VolumeUpdateMessage
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
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

    suspend fun play(payload: PlayMessage) = sendInternal(FCastMessage.Play(payload))
    suspend fun pause() = sendInternal(FCastMessage.Pause)
    suspend fun resume() = sendInternal(FCastMessage.Resume)
    suspend fun stop() = sendInternal(FCastMessage.Stop)
    suspend fun seek(seconds: Double) = sendInternal(FCastMessage.Seek(SeekMessage(seconds)))
    suspend fun setVolume(volume: Double) =
        sendInternal(FCastMessage.SetVolume(SetVolumeMessage(volume.coerceIn(0.0, 1.0))))
    suspend fun setSpeed(speed: Double) = sendInternal(FCastMessage.SetSpeed(SetSpeedMessage(speed)))
    suspend fun ping() = sendInternal(FCastMessage.Ping)

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
            FCastMessage.Ping -> sendInternal(FCastMessage.Pong)
            // Pong, Initial, PlayUpdate, Event, etc. are observed but not yet acted on.
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
