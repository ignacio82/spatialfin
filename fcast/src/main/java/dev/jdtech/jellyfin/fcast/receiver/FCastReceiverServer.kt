package dev.jdtech.jellyfin.fcast.receiver

import android.os.SystemClock
import dev.jdtech.jellyfin.fcast.protocol.FCAST_DEFAULT_PORT
import dev.jdtech.jellyfin.fcast.protocol.InitialReceiverMessage
import dev.jdtech.jellyfin.fcast.protocol.PlaybackUpdateMessage
import dev.jdtech.jellyfin.fcast.protocol.VolumeUpdateMessage
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Listens for incoming FCast sender connections on [config]'s port and creates a
 * [FCastReceiverSession] per accepted socket. Designed to be embedded in either an Android
 * Service ([FCastReceiverService]) or a JVM test harness — it has no Android dependencies.
 *
 * Lifecycle: construct → [start] → ... → [stop]. The server is **not** reusable after [stop].
 */
class FCastReceiverServer(
    private val config: Config,
    private val routerFactory: () -> FCastIngressRouter,
    parentScope: CoroutineScope? = null,
) {

    data class Config(
        val port: Int = FCAST_DEFAULT_PORT,
        /** Receiver display name surfaced via the FCast Initial message and (later) mDNS TXT. */
        val displayName: String = "SpatialFin",
        val appName: String = "SpatialFin",
        val appVersion: String? = null,
        /**
         * Monotonic clock for per-session NTP Ping/Pong timestamps. Defaults to
         * [SystemClock.elapsedRealtime]; injectable so JVM unit tests (no Robolectric) don't
         * hit the throwing Android stub on the v4 Ping path.
         */
        val clock: () -> Long = { SystemClock.elapsedRealtime() },
    )

    enum class State { Stopped, Starting, Running, Failed }

    private val ownsScope: Boolean = parentScope == null
    private val scope: CoroutineScope = parentScope
        ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val sessions: MutableList<FCastReceiverSession> = CopyOnWriteArrayList()

    private val _state = MutableStateFlow(State.Stopped)
    val state: StateFlow<State> = _state.asStateFlow()

    val sessionCount: Int get() = sessions.size

    /** Bind the listening socket and start accepting senders. Throws on bind failure. */
    suspend fun start() {
        check(_state.value == State.Stopped) { "FCastReceiverServer already started" }
        _state.value = State.Starting
        try {
            val socket = withContext(Dispatchers.IO) {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(config.port))
                }
            }
            serverSocket = socket
            acceptJob = scope.launch { acceptLoop(socket) }
            _state.value = State.Running
        } catch (e: Exception) {
            _state.value = State.Failed
            Timber.tag(TAG).e(e, "FCast receiver bind failed")
            throw e
        }
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        val info = InitialReceiverMessage(
            displayName = config.displayName,
            appName = config.appName,
            appVersion = config.appVersion,
        )
        try {
            while (!socket.isClosed) {
                val client = withContext(Dispatchers.IO) {
                    try { socket.accept() } catch (_: Exception) { null }
                } ?: break
                client.tcpNoDelay = true
                val session = FCastReceiverSession(
                    socket = client,
                    router = routerFactory(),
                    receiverInfo = info,
                    parentScope = scope,
                    nowMs = config.clock,
                    onDisconnect = { sessions.remove(it) },
                )
                sessions.add(session)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "FCast accept loop terminated")
        }
    }

    /** Broadcast a [PlaybackUpdateMessage] to every connected sender. */
    suspend fun broadcastPlaybackUpdate(update: PlaybackUpdateMessage) {
        pruneClosedSessions()
        for (session in sessions) session.pushPlaybackUpdate(update)
    }

    /** Broadcast a [VolumeUpdateMessage] to every connected sender. */
    suspend fun broadcastVolumeUpdate(update: VolumeUpdateMessage) {
        pruneClosedSessions()
        for (session in sessions) session.pushVolumeUpdate(update)
    }

    /** Broadcast a PlaybackError message. Useful for e.g. DRM-required streams. */
    suspend fun broadcastError(message: String) {
        pruneClosedSessions()
        for (session in sessions) session.pushError(message)
    }

    /**
     * Remove any sessions that have been closed but whose [FCastReceiverSession.onDisconnect]
     * callback hasn't cleared them from our list yet (or was missed). Prevents the session list
     * from growing indefinitely if the callback is delayed or races with a broadcast.
     */
    private fun pruneClosedSessions() {
        // sessions is a CopyOnWriteArrayList, so we can iterate and remove concurrently.
        val iterator = sessions.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next()
            // We use the session's internal closed state. Note: FCastReceiverSession.closed
            // is private; we'll need to expose it or a helper.
            if (session.isClosed) {
                sessions.remove(session)
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        for (session in sessions) session.close()
        sessions.clear()
        _state.value = State.Stopped
        if (ownsScope) scope.cancel()
    }

    private companion object {
        const val TAG = "FCastServer"
    }
}
