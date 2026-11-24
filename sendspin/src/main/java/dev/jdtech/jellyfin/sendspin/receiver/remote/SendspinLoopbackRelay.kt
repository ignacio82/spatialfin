package dev.jdtech.jellyfin.sendspin.receiver.remote

import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverService.Companion.TAG
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshakeBuilder
import org.java_websocket.server.WebSocketServer
import timber.log.Timber
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * In-process loopback WebSocket server that lets the **unmodified** `sendspin-jvm`
 * `SendSpinClient` speak its protocol over a WebRTC data channel — no library fork.
 *
 * The client dials `ws://127.0.0.1:<port>/?token=<128-bit-hex>` ([loopbackUrl]) believing it's a
 * normal SendSpin server. Every frame is shuttled across the WebRTC `sendspin` data channel:
 *
 * ```
 *  SendSpinClient ──(ws text/binary)──▶ this relay ──[onOutbound*]──▶ DataChannel.send
 *  SendSpinClient ◀──(ws text/binary)── this relay ◀──[deliver*]──── DataChannel.onMessage
 * ```
 *
 * Security invariants:
 * - Binds `127.0.0.1` only.
 * - Requires a per-instance 128-bit CSPRNG hex token validated in constant time during the
 *   WebSocket upgrade handshake (`onWebsocketHandshakeReceivedAsServer`) before HTTP 101.
 * - Enforces single-connection exclusivity under [connLock], rejecting competing upgrades
 *   before HTTP 101 and ignoring frames from any socket other than the active [conn].
 */
class SendspinLoopbackRelay(
    private val onOutboundText: (String) -> Unit,
    private val onOutboundBinary: (ByteArray) -> Unit,
) {
    private val token: String = generateToken()
    private val expectedTokenBytes: ByteArray = token.toByteArray(Charsets.US_ASCII)
    private val connLock = Any()

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

                override fun onWebsocketHandshakeReceivedAsServer(
                    c: WebSocket,
                    draft: Draft,
                    request: ClientHandshake,
                ): ServerHandshakeBuilder {
                    if (c.isFlushAndClose || c.isClosing || c.isClosed) {
                        throw InvalidDataException(
                            CloseFrame.POLICY_VALIDATION,
                            "Loopback relay connection is closing",
                        )
                    }
                    val provided = extractToken(request.resourceDescriptor)
                    val validToken = provided != null &&
                        MessageDigest.isEqual(
                            expectedTokenBytes,
                            provided.toByteArray(Charsets.US_ASCII),
                        )
                    if (!validToken) {
                        throw InvalidDataException(
                            CloseFrame.POLICY_VALIDATION,
                            "Invalid loopback relay token",
                        )
                    }
                    val builder = super.onWebsocketHandshakeReceivedAsServer(c, draft, request)
                    synchronized(connLock) {
                        if (conn != null) {
                            throw InvalidDataException(
                                CloseFrame.POLICY_VALIDATION,
                                "Loopback relay already has an active connection",
                            )
                        }
                        conn = c
                    }
                    return builder
                }

                override fun onOpen(c: WebSocket, handshake: ClientHandshake?) {
                    val accepted = synchronized(connLock) { conn === c }
                    if (!accepted) {
                        runCatching { c.close(CloseFrame.POLICY_VALIDATION, "Unauthorized") }
                    }
                }

                override fun onClose(c: WebSocket, code: Int, reason: String?, remote: Boolean) {
                    synchronized(connLock) {
                        if (c === conn) conn = null
                    }
                }

                override fun onMessage(c: WebSocket, message: String) {
                    if (synchronized(connLock) { c !== conn }) return
                    onOutboundText(message)
                }

                override fun onMessage(c: WebSocket, message: ByteBuffer) {
                    if (synchronized(connLock) { c !== conn }) return
                    val arr = ByteArray(message.remaining())
                    message.get(arr)
                    onOutboundBinary(arr)
                }

                override fun onError(c: WebSocket?, ex: Exception) {
                    if (c != null) {
                        synchronized(connLock) {
                            if (c === conn && !c.isOpen) conn = null
                        }
                    }
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
    fun loopbackUrl(): String? = boundPort.takeIf { it > 0 }?.let { "ws://127.0.0.1:$it/?token=$token" }

    /** Bound loopback host:port target suitable for an explicit SSRF allowlist. */
    internal fun loopbackTarget(): String? = boundPort.takeIf { it > 0 }?.let { "127.0.0.1:$it" }

    /** Deliver an inbound data-channel TEXT frame to the SendSpinClient. */
    fun deliverText(text: String) {
        val active = synchronized(connLock) { conn } ?: return
        runCatching { active.send(text) }
    }

    /** Deliver an inbound data-channel BINARY frame (audio) to the SendSpinClient. */
    fun deliverBinary(bytes: ByteArray) {
        val active = synchronized(connLock) { conn } ?: return
        runCatching { active.send(bytes) }
    }

    fun stop() {
        val active = synchronized(connLock) {
            val prev = conn
            conn = null
            prev
        }
        runCatching { active?.close() }
        runCatching { server?.stop(0) }
        server = null
        boundPort = -1
    }

    private companion object {
        const val START_TIMEOUT_SECONDS = 5L
        private val secureRandom = SecureRandom()

        fun generateToken(): String {
            val bytes = ByteArray(16)
            secureRandom.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun extractToken(resourceDescriptor: String?): String? {
            if (resourceDescriptor.isNullOrBlank()) return null
            val query = resourceDescriptor.substringAfter('?', missingDelimiterValue = "")
            if (query.isEmpty()) return null
            for (part in query.split('&')) {
                val eq = part.indexOf('=')
                if (eq > 0 && part.substring(0, eq) == "token") {
                    return part.substring(eq + 1)
                }
            }
            return null
        }
    }
}
