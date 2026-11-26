package dev.jdtech.jellyfin.fcast.sender

import dev.jdtech.jellyfin.fcast.protocol.FCastFrame
import dev.jdtech.jellyfin.fcast.protocol.FCastMessage
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of probing an FCast receiver over TCP and reading its proactive handshake frames. */
data class FCastProbeResult(
    val reachable: Boolean,
    val displayName: String? = null,
    val appName: String? = null,
    val appVersion: String? = null,
)

/**
 * Handshake-aware reachability probe against an FCast receiver's TCP port. Connects within
 * [timeoutMs], then reads up to two proactive frames (`Version` followed by `Initial`) with a
 * short `soTimeout` so receivers missing mDNS TXT metadata (or remembered/manual entries) still
 * expose `appName` and `appVersion` for Split A/V filtering.
 */
suspend fun probeFCastReceiverDetails(
    host: String,
    port: Int,
    timeoutMs: Int = 700,
): FCastProbeResult =
    withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                if (!s.isConnected) return@runCatching FCastProbeResult(reachable = false)
                s.soTimeout = timeoutMs.coerceIn(300, 1_000)
                var displayName: String? = null
                var appName: String? = null
                var appVersion: String? = null
                runCatching {
                    val input = DataInputStream(s.getInputStream())
                    repeat(2) {
                        val msg = FCastFrame.read(input) ?: return@repeat
                        if (msg is FCastMessage.Initial) {
                            displayName = msg.payload.displayName?.takeIf { it.isNotBlank() }
                            appName = msg.payload.appName?.takeIf { it.isNotBlank() }
                            appVersion = msg.payload.appVersion?.takeIf { it.isNotBlank() }
                            return@runCatching
                        }
                    }
                }
                FCastProbeResult(
                    reachable = true,
                    displayName = displayName,
                    appName = appName,
                    appVersion = appVersion,
                )
            }
        }.getOrDefault(FCastProbeResult(reachable = false))
    }

/**
 * Lightweight TCP connect probe. Returns true iff the receiver accepts a connection within
 * [timeoutMs]. Used by the picker to flip cached entries from "Probing" to "Online" / "Offline"
 * before the full mDNS scan completes — saves the user the 1.5–4s scan wait when they already
 * have a known receiver.
 */
suspend fun probeFCastReceiver(host: String, port: Int, timeoutMs: Int = 700): Boolean =
    probeFCastReceiverDetails(host = host, port = port, timeoutMs = timeoutMs).reachable
