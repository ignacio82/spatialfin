package dev.jdtech.jellyfin.fcast.sender

import dev.jdtech.jellyfin.fcast.protocol.PlayMessage
import dev.jdtech.jellyfin.fcast.protocol.PlaybackState
import dev.jdtech.jellyfin.fcast.protocol.PlaybackUpdateMessage
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
import timber.log.Timber

/**
 * Owns the lifecycle of a single FCast sender connection. The player layer talks to this
 * controller; it manages the underlying [FCastSenderClient] and exposes the active receiver and
 * remote playback state.
 *
 * Concurrency: every public mutator goes through [mutex] so that "stop and start a new cast" is
 * atomic and we never end up with two clients fighting over a target.
 */
open class FCastCastingController {

    enum class Status { Idle, Connecting, Casting, Failed }

    private val supervisor: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private var client: FCastSenderClient? = null
    private var observerJob: Job? = null

    private val _status = MutableStateFlow(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _activeReceiver = MutableStateFlow<FCastReceiver?>(null)
    val activeReceiver: StateFlow<FCastReceiver?> = _activeReceiver.asStateFlow()

    private val _remoteState = MutableStateFlow<PlaybackUpdateMessage?>(null)
    val remoteState: StateFlow<PlaybackUpdateMessage?> = _remoteState.asStateFlow()

    /**
     * Pong observations from the active client, re-emitted here so consumers can subscribe
     * once and survive reconnects. Empty until at least one Ping has round-tripped on the
     * current connection.
     */
    private val _pongs = MutableSharedFlow<FCastSenderClient.PongObservation>(
        extraBufferCapacity = 8,
    )
    val pongs: SharedFlow<FCastSenderClient.PongObservation> = _pongs.asSharedFlow()

    /**
     * Begin (or replace) casting to [receiver] with [play] as the initial Play message.
     * Closes any existing connection first. On failure the controller transitions to [Status.Failed]
     * and the exception is rethrown so the caller can surface it.
     */
    suspend fun startCast(receiver: FCastReceiver, play: PlayMessage) {
        mutex.withLock {
            stopInternal()
            _status.value = Status.Connecting
            _activeReceiver.value = receiver
            val newClient = FCastSenderClient(receiver, parentScope = supervisor)
            try {
                newClient.connect()
                newClient.play(play)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "FCast connect to ${receiver.host}:${receiver.port} failed")
                _status.value = Status.Failed
                newClient.close()
                throw e
            }
            client = newClient
            observerJob = supervisor.launch { observe(newClient) }
            // Pong forwarding runs in parallel with playback observation. Cancelled when the
            // observer job (and therefore the supervisor scope's child) terminates on stop.
            supervisor.launch {
                newClient.pongs.collect { _pongs.emit(it) }
            }
            _status.value = Status.Casting
        }
    }

    suspend fun pause() = withClient { it.pause() }
    suspend fun resume() = withClient { it.resume() }
    suspend fun seek(seconds: Double) = withClient { it.seek(seconds) }
    suspend fun setVolume(volume: Double) = withClient { it.setVolume(volume) }
    suspend fun setSpeed(speed: Double) = withClient { it.setSpeed(speed) }
    suspend fun ping() = withClient { it.ping() }

    /** Send Stop to the receiver and tear down the connection. Idempotent. */
    suspend fun stopCast() {
        mutex.withLock {
            stopInternal()
        }
    }

    /** Synchronously close everything. Safe to call from process shutdown. */
    fun shutdown() {
        observerJob?.cancel()
        observerJob = null
        client?.close()
        client = null
        _status.value = Status.Idle
        _activeReceiver.value = null
        _remoteState.value = null
        supervisor.cancel()
    }

    private suspend fun stopInternal() {
        try {
            client?.takeIf { _status.value == Status.Casting }?.stop()
        } catch (_: Exception) {
            // Best-effort: peer may have dropped already.
        }
        observerJob?.cancel()
        observerJob = null
        client?.close()
        client = null
        _status.value = Status.Idle
        _activeReceiver.value = null
        _remoteState.value = null
    }

    private suspend fun observe(c: FCastSenderClient) {
        c.playbackUpdates.collect { update ->
            _remoteState.value = update
            // If the remote stops, drop our active flag so the UI can dismiss the casting chrome.
            if (update.playbackState == PlaybackState.Idle) {
                _status.value = Status.Idle
            }
        }
    }

    private suspend inline fun withClient(action: (FCastSenderClient) -> Unit) {
        val c = client ?: return
        action(c)
    }

    private companion object {
        const val TAG = "FCastCasting"
    }
}
