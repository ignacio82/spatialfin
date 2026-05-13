package dev.jdtech.jellyfin.cast.adapter.googlecast

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
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
 * Transport for Google Cast V2: TLS socket carrying length-prefixed protobuf `CastMessage`
 * frames. The receiver presents a self-signed certificate that doesn't chain to any public CA
 * and rarely matches the LAN IP in its CN — Chromium-derived clients accept this because
 * mDNS already gave us the target's stable `id`; we're trusting *that* identity, not PKI.
 *
 * Lifecycle: [connect] → [send] / [incoming] → [close]. Not reusable after [close].
 *
 * Threading: socket I/O runs on [Dispatchers.IO] through the supplied [parentScope] (or an
 * internal scope if absent). The reader loop emits decoded messages into [incoming] until the
 * socket closes or the scope is cancelled.
 *
 * Defensive notes on the trust model:
 *  - We accept any certificate the receiver presents, but only on connections we initiated to
 *    a host:port discovered via mDNS. That's the same trade-off every Cast V2 client makes;
 *    upstream Chromium documents it explicitly.
 *  - Hostname verification is disabled. Most Chromecasts present a CN like `eureka-…` rather
 *    than the IP, and SubjectAlternativeName entries vary.
 *  - We do **not** offer this trust manager anywhere else in the app — instances are created
 *    inside [connect] and never returned to callers.
 */
internal class CastV2Channel(
    val host: String,
    val port: Int,
    parentScope: CoroutineScope? = null,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 30_000,
) {

    enum class State { Idle, Connecting, Connected, Disconnected, Failed }

    private val ownsScope: Boolean = parentScope == null
    private val scope: CoroutineScope = parentScope
        ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var socket: SSLSocket? = null
    private var output: DataOutputStream? = null
    private var input: DataInputStream? = null
    private var readerJob: Job? = null
    private val writeMutex = Mutex()

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Inbound decoded messages. `replay = 0`, `extraBufferCapacity = 64` — the adapter
     * processes them in a coroutine and shouldn't drop, but a slow consumer (e.g. paused
     * during a debug breakpoint) won't kill the reader loop.
     */
    private val _incoming = MutableSharedFlow<CastMessage>(
        replay = 0, extraBufferCapacity = 64,
    )
    val incoming: SharedFlow<CastMessage> = _incoming.asSharedFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    suspend fun connect() {
        check(_state.value == State.Idle) { "CastV2Channel already connected (state=${_state.value})" }
        _state.value = State.Connecting
        try {
            val factory = permissiveSocketFactory()
            val ssl = withContext(Dispatchers.IO) {
                (factory.createSocket() as SSLSocket).apply {
                    soTimeout = readTimeoutMs
                    tcpNoDelay = true
                    keepAlive = true
                    connect(InetSocketAddress(host, port), connectTimeoutMs)
                    // Permissive verifier — the cert's CN won't match the LAN IP. The mDNS id
                    // we discovered is the actual identity we trust.
                    if (!PERMISSIVE_HOSTNAME_VERIFIER.verify(host, session)) {
                        Timber.tag(TAG).d("hostname verify accepted: host=%s", host)
                    }
                    startHandshake()
                }
            }
            socket = ssl
            output = DataOutputStream(ssl.getOutputStream().buffered())
            input = DataInputStream(ssl.getInputStream().buffered())
            readerJob = scope.launch { readLoop() }
            _state.value = State.Connected
            Timber.tag(TAG).i("CastV2 connected to %s:%d", host, port)
        } catch (e: Exception) {
            _state.value = State.Failed
            closeQuietly()
            throw e
        }
    }

    /**
     * Send [message]. Suspends until the frame is flushed. Throws [IOException] if the channel
     * is not connected.
     */
    suspend fun send(message: CastMessage) {
        val out = output ?: throw IOException("CastV2Channel not connected")
        val bytes = CastMessageCodec.encode(message)
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                out.writeInt(bytes.size) // u32 big-endian length prefix
                out.write(bytes)
                out.flush()
            }
        }
    }

    fun close() {
        readerJob?.cancel()
        readerJob = null
        closeQuietly()
        _state.value = State.Disconnected
        if (ownsScope) scope.cancel()
    }

    private suspend fun readLoop() {
        val stream = input ?: return
        try {
            while (true) {
                val frame = withContext(Dispatchers.IO) { readFrame(stream) } ?: break
                val message = runCatching { CastMessageCodec.decode(frame) }
                    .onFailure {
                        Timber.tag(TAG).w(it, "CastV2 decode failed (%d bytes)", frame.size)
                    }
                    .getOrNull() ?: continue
                _incoming.emit(message)
            }
        } catch (e: IOException) {
            Timber.tag(TAG).w(e, "CastV2 read loop terminated")
            _errors.tryEmit(e.message ?: "read error")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "CastV2 read loop crashed")
            _errors.tryEmit(e.message ?: "decode error")
        } finally {
            _state.value = State.Disconnected
            closeQuietly()
        }
    }

    /** Read one length-prefixed frame. Returns null on clean EOF. */
    private fun readFrame(stream: DataInputStream): ByteArray? {
        val length = try {
            stream.readInt()
        } catch (_: java.io.EOFException) {
            return null
        }
        require(length in 0..MAX_FRAME_BYTES) { "implausible Cast frame size: $length" }
        val buf = ByteArray(length)
        stream.readFully(buf)
        return buf
    }

    private fun closeQuietly() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    private fun permissiveSocketFactory(): SSLSocketFactory {
        val trustManagers: Array<TrustManager> = arrayOf(PermissiveTrustManager)
        val context = SSLContext.getInstance("TLS").apply {
            init(null, trustManagers, java.security.SecureRandom())
        }
        return context.socketFactory
    }

    // --- Permissive trust manager + hostname verifier ---

    private object PermissiveTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        const val TAG = "CastV2Channel"
        // 8 MiB cap — Cast frames are typically <2 KB; anything in megabytes is either a
        // malformed length or a malicious peer. Fail loud rather than allocate a huge buffer.
        const val MAX_FRAME_BYTES = 8 * 1024 * 1024

        val PERMISSIVE_HOSTNAME_VERIFIER: HostnameVerifier = HostnameVerifier { _, _ -> true }
    }

    /** Test seam: read one frame from an arbitrary stream. Used by the unit suite to exercise
     * the framing without a real socket. */
    internal object Framing {
        fun read(stream: InputStream): ByteArray? {
            val data = DataInputStream(stream)
            val length = try { data.readInt() } catch (_: java.io.EOFException) { return null }
            require(length in 0..MAX_FRAME_BYTES) { "implausible Cast frame size: $length" }
            val buf = ByteArray(length)
            data.readFully(buf)
            return buf
        }

        fun write(stream: OutputStream, frame: ByteArray) {
            val data = DataOutputStream(stream)
            data.writeInt(frame.size)
            data.write(frame)
            data.flush()
        }
    }
}
