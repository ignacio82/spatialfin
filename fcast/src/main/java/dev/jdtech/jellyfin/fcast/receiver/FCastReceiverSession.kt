package dev.jdtech.jellyfin.fcast.receiver

import dev.jdtech.jellyfin.fcast.protocol.FCAST_PROTOCOL_VERSION
import dev.jdtech.jellyfin.fcast.protocol.FCastFrame
import dev.jdtech.jellyfin.fcast.protocol.FCastMessage
import dev.jdtech.jellyfin.fcast.protocol.InitialReceiverMessage
import dev.jdtech.jellyfin.fcast.protocol.PlaybackErrorMessage
import dev.jdtech.jellyfin.fcast.protocol.PlaybackUpdateMessage
import dev.jdtech.jellyfin.fcast.protocol.VersionMessage
import dev.jdtech.jellyfin.fcast.protocol.PongMessage
import dev.jdtech.jellyfin.fcast.protocol.VolumeUpdateMessage
import android.os.SystemClock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * One end of a TCP connection from a connected FCast sender. Owns the read loop and a
 * write-mutex so concurrent broadcasts (PlaybackUpdate from the player + Pong replies from the
 * read loop) don't interleave on the wire.
 *
 * Construct via [FCastReceiverServer]; the session reads frames, dispatches to [router], and
 * answers the v2/v3 handshake (Version + Initial). It is **not** responsible for player state —
 * outbound PlaybackUpdate messages come from [pushPlaybackUpdate].
 */
class FCastReceiverSession(
    private val socket: Socket,
    private val router: FCastIngressRouter,
    private val receiverInfo: InitialReceiverMessage,
    parentScope: CoroutineScope,
    /** Monotonic clock for NTP Ping/Pong timestamps; must be the same clock the player uses
     *  to stamp `PlaybackUpdateMessage.monotonicSampleMs`. Injectable for tests. */
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    /** Callback invoked when the session reader loop exits or the session is manually closed. */
    private val onDisconnect: (FCastReceiverSession) -> Unit = {},
) {

    private val output: DataOutputStream = DataOutputStream(socket.getOutputStream().buffered())
    private val input: DataInputStream = DataInputStream(socket.getInputStream().buffered())
    private val writeMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val readerJob: Job

    /** True if the session has been closed manually or its reader loop has exited. */
    val isClosed: Boolean get() = closed.get()

    val remoteAddress: String = socket.remoteSocketAddress?.toString().orEmpty()

    init {
        readerJob = parentScope.launch { runReadLoop() }
    }

    private suspend fun runReadLoop() {
        try {
            // Receivers send Version proactively per spec; do the same so v3 senders can downgrade.
            sendInternal(FCastMessage.Version(VersionMessage(FCAST_PROTOCOL_VERSION)))
            sendInternal(FCastMessage.Initial(receiverInfo))

            while (!closed.get()) {
                val msg = withContext(Dispatchers.IO) {
                    try {
                        FCastFrame.read(input)
                    } catch (e: IOException) {
                        Timber.tag(TAG).w(e, "FCast session %s read failed", remoteAddress)
                        null
                    }
                } ?: break
                handle(msg)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "FCast session %s crashed", remoteAddress)
        } finally {
            close()
        }
    }

    private suspend fun handle(message: FCastMessage) {
        when (message) {
            is FCastMessage.Play -> {
                when (val result = router.onPlay(message.payload)) {
                    is FCastIngressRouter.IngressResult.Accepted -> Unit
                    is FCastIngressRouter.IngressResult.Rejected ->
                        sendInternal(FCastMessage.PlaybackError(PlaybackErrorMessage(result.reason)))
                }
            }
            FCastMessage.Pause -> router.onPause()
            is FCastMessage.Resume -> {
                // v4: a body requests a synchronized start at a receiver-clock instant; no
                // body = legacy resume-now.
                val at = message.payload?.atReceiverMonotonicMs
                if (at != null) router.onResumeAt(at) else router.onResume()
            }
            FCastMessage.Stop -> router.onStop()
            is FCastMessage.Seek -> router.onSeek(message.payload.time)
            is FCastMessage.SetVolume -> router.onSetVolume(message.payload.volume)
            is FCastMessage.SetSpeed -> router.onSetSpeed(message.payload.speed)
            is FCastMessage.Ping -> {
                // v4 NTP exchange: echo the sender's t1 and add our monotonic receive (t2)
                // and send (t3) stamps so the sender can solve clock offset θ. A body-less
                // Ping (pre-v4 / non-SpatialFin) still gets a plain body-less Pong.
                val t1 = message.payload?.t1
                if (t1 != null) {
                    val t2 = nowMs()
                    sendInternal(FCastMessage.Pong(PongMessage(t1 = t1, t2 = t2, t3 = nowMs())))
                } else {
                    sendInternal(FCastMessage.Pong())
                }
            }
            // We don't care about senders' Version or Initial bodies for routing — the
            // handshake is informational once we've sent ours.
            is FCastMessage.Version, is FCastMessage.Initial -> Unit
            else -> {
                Timber.tag(TAG).d("FCast session %s ignored %s", remoteAddress, message.opcode)
            }
        }
    }

    suspend fun pushPlaybackUpdate(update: PlaybackUpdateMessage) =
        sendInternal(FCastMessage.PlaybackUpdate(update))

    suspend fun pushVolumeUpdate(update: VolumeUpdateMessage) =
        sendInternal(FCastMessage.VolumeUpdate(update))

    suspend fun pushError(message: String) =
        sendInternal(FCastMessage.PlaybackError(PlaybackErrorMessage(message)))

    private suspend fun sendInternal(message: FCastMessage) {
        if (closed.get()) return
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    FCastFrame.write(output, message)
                } catch (e: IOException) {
                    Timber.tag(TAG).w(e, "FCast session %s write failed; closing", remoteAddress)
                    close()
                }
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        onDisconnect(this)
        readerJob.cancel()
        try { input.close() } catch (_: Exception) {}
        try { output.close() } catch (_: Exception) {}
        try { socket.close() } catch (_: Exception) {}
    }

    private companion object {
        const val TAG = "FCastSession"
    }
}
