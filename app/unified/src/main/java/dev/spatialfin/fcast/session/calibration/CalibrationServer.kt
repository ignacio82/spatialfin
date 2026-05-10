package dev.spatialfin.fcast.session.calibration

import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Tiny one-shot HTTP server that serves the calibration WAV to the receiver. Used during the
 * audio-latency wizard: the orchestrator builds [wavBytes] from [ChirpGenerator] + [WavWriter],
 * starts this server, and FCast-Plays `http://<xr-ip>:<port>/calibration.wav`. The TV's
 * HTTP client connects, downloads, and plays. The server then closes.
 *
 * Why not a full embedded server (NanoHTTPD): we need exactly one route for one MIME type
 * served once. ~80 lines of ServerSocket beats pulling in a dependency for that.
 *
 * Lifecycle: [start] returns the URL once the listener is bound. [stop] is idempotent. The
 * server auto-closes after serving the bytes; the orchestrator should still call [stop] in a
 * finally block in case the TV never connects.
 */
class CalibrationServer(
    private val wavBytes: ByteArray,
    private val resourcePath: String = DEFAULT_RESOURCE_PATH,
) {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    /** True after the WAV has been served at least once. */
    @Volatile var served: Boolean = false
        private set

    /**
     * Bind to a random free port on the LAN-facing interface, start listening. Returns the URL
     * the FCast Play message should reference.
     */
    suspend fun start(): String = withContext(Dispatchers.IO) {
        val ip = pickLocalLanIpv4()
            ?: throw IOException("No usable LAN interface for calibration server")
        val socket = ServerSocket(0)
        serverSocket = socket
        val port = socket.localPort
        serverJob = scope.launch { acceptLoop(socket) }
        val url = "http://$ip:$port$resourcePath"
        Timber.tag(TAG).i("Calibration server listening at %s", url)
        url
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
            // Closing while accept() is blocked unblocks it; the IOException is expected.
        }
        serverJob?.cancel()
        serverJob = null
        serverSocket = null
        scope.cancel()
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        try {
            while (!socket.isClosed) {
                val client = try {
                    withContext(Dispatchers.IO) { socket.accept() }
                } catch (_: IOException) {
                    return
                }
                try {
                    serveOnce(client)
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "calibration request failed")
                } finally {
                    try { client.close() } catch (_: Exception) {}
                }
            }
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun serveOnce(client: Socket) {
        // Drain the request line and headers — we don't actually inspect them. The TV's client
        // sends `GET /calibration.wav HTTP/1.1` plus headers; we accept any path.
        val reader = client.getInputStream().bufferedReader()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val out = client.getOutputStream()
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: audio/wav\r\n")
            append("Content-Length: ${wavBytes.size}\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(wavBytes)
        out.flush()
        served = true
    }

    /**
     * Pick the first non-loopback IPv4 interface address. Wi-Fi normally wins; fallbacks land
     * on Ethernet or USB tethering interfaces if those are the only LAN-facing surfaces.
     */
    private fun pickLocalLanIpv4(): String? {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (iface in ifaces.toList()) {
            if (!iface.isUp || iface.isLoopback) continue
            for (addr in iface.inetAddresses.toList()) {
                if (addr.isLoopbackAddress) continue
                if (addr.isLinkLocalAddress) continue
                if (addr is InetAddress && addr.address.size == 4) {
                    return addr.hostAddress
                }
            }
        }
        return null
    }

    companion object {
        const val DEFAULT_RESOURCE_PATH: String = "/calibration.wav"
        private const val TAG = "SplitAvCalibration"
    }
}
