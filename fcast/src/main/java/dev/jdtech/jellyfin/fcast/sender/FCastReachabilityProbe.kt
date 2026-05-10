package dev.jdtech.jellyfin.fcast.sender

import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lightweight TCP connect probe. Returns true iff the receiver accepts a connection within
 * [timeoutMs]. Used by the picker to flip cached entries from "Probing" to "Online" / "Offline"
 * before the full mDNS scan completes — saves the user the 1.5–4s scan wait when they already
 * have a known receiver.
 *
 * Doesn't speak the FCast handshake (Version + Initial); the connect itself is sufficient
 * evidence that something is listening on the canonical port. A receiver that accepts the socket
 * but hangs the handshake will still falsely look online, but in practice that's rare.
 */
suspend fun probeFCastReceiver(host: String, port: Int, timeoutMs: Int = 700): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.isConnected
            }
        }.getOrDefault(false)
    }
