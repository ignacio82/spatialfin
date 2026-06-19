package dev.jdtech.jellyfin.sendspin.receiver.remote

import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverService.Companion.TAG
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import timber.log.Timber
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * In-process loopback WebSocket server that lets the **unmodified** `sendspin-jvm`
 * `SendSpinClient` speak its protocol over a WebRTC data channel — no library fork.
 *
 * The client dials `ws://127.0.0.1:<port>` ([loopbackUrl]) believing it's a normal
 * SendSpin server. Every frame is shuttled across the WebRTC `sendspin` data channel:
 *
 * ```
 *  SendSpinClient ──(ws text/binary)──▶ this relay ──[onOutbound*]──▶ DataChannel.send
 *  SendSpinClient ◀──(ws text/binary)── this relay ◀──[deliver*]──── DataChannel.onMessage
 * ```
 *
 * The server binds `127.0.0.1` only, so it is never reachable off-device. `org.java-websocket`
 * is already a module dependency (it backs `SendSpinServerHost`), so this adds nothing new.
 *
 * Outbound callbacks fire on the java_websocket worker thread; the owner ([MaWebRtcConnection])
 * marshals them onto the WebRTC thread before touching the data channel.
 */
class SendspinLoopbackRelay(
    private val onOutboundText: (String) -> Unit,
    private val onOutboundBinary: (ByteArray) -> Unit,
) {
    @Volatile private var server: WebSocketServer? = null
    @Volatile private var conn: WebSocket? = null
    @Volatile private var boundPort: Int = -1
    private val started = CountDownLatch(1)

    /** Bind on an ephemeral loopback port and return that port, or -1 on failure. */
    fun start(): Int {
        if (server != null) return boundPort
        val srv =
            object : WebSocketServer(InetSocketAddress("127.0.0.1", 0)) {
                override fun onStart() {
                    boundPort = port
                    started.countDown()
                    Timber.tag(TAG).i("Loopback relay listening on 127.0.0.1:%d", boundPort)
                }

                override fun onOpen(c: WebSocket, handshake: ClientHandshake?) {
                    conn = c
                }

                override fun onClose(c: WebSocket, code: Int, reason: String?, remote: Boolean) {
                    if (c === conn) conn = null
                }

                override fun onMessage(c: WebSocket, message: String) {
                    onOutboundText(message)
                }

                override fun onMessage(c: WebSocket, message: ByteBuffer) {
                    val arr = ByteArray(message.remaining())
                    message.get(arr)
                    onOutboundBinary(arr)
                }

                override fun onError(c: WebSocket?, ex: Exception) {
                    Timber.tag(TAG).w(ex, "Loopback relay error")
                }
            }
        srv.isReuseAddr = true
        srv.connectionLostTimeout = 0 // we own the lifecycle; no java_websocket ping probing
        server = srv
        return try {
            srv.start()
            if (!started.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Timber.tag(TAG).w("Loopback relay failed to bind within timeout")
                -1
            } else {
                boundPort
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Loopback relay start failed")
            -1
        }
    }

    /** Loopback ws URL the SendSpinClient should dial, or null if not bound. */
    fun loopbackUrl(): String? = boundPort.takeIf { it > 0 }?.let { "ws://127.0.0.1:$it" }

    /** Deliver an inbound data-channel TEXT frame to the SendSpinClient. */
    fun deliverText(text: String) {
        runCatching { conn?.send(text) }
    }

    /** Deliver an inbound data-channel BINARY frame (audio) to the SendSpinClient. */
    fun deliverBinary(bytes: ByteArray) {
        runCatching { conn?.send(bytes) }
    }

    fun stop() {
        conn = null
        runCatching { server?.stop(0) }
        server = null
        boundPort = -1
    }

    private companion object {
        const val START_TIMEOUT_SECONDS = 5L
    }
}
