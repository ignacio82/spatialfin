package dev.spatialfin.unified.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.data.musicassistant.api.ConnectionInfo
import dev.jdtech.jellyfin.data.musicassistant.api.ServiceClient
import dev.jdtech.jellyfin.data.musicassistant.utils.SessionState
import dev.jdtech.jellyfin.sendspin.receiver.SendspinMusicAssistantAuthState
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverSession
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Open the app-side Music Assistant WebSocket so the rich MA UI (now-playing,
 * queue, party, add-to-playlist) actually has data.
 *
 * The SendSpin receiver service keeps its OWN MA connection for play_media /
 * grouping. The app UI talks through a SEPARATE [ServiceClient] that nothing
 * else connects — so [dev.jdtech.jellyfin.data.musicassistant.repository.MaSessionRepository]
 * and [dev.jdtech.jellyfin.data.musicassistant.repository.MusicAssistantRepository]
 * silently fail every RPC until this runs. That's why local playback showed the
 * bare SendSpin player and the party commands looked "not installed".
 *
 * We reuse the server URL the receiver already discovered/authenticated, parse
 * it into a [ConnectionInfo], and (re)connect whenever the socket is down.
 * MA allows unauthenticated local WebSocket clients, which is why
 * [ServiceClient.connect] carries no token.
 *
 * Install once at the activity root (covers every form factor).
 */
@Composable
fun MaConnectionBridge(serviceClient: ServiceClient) {
    val state by SendspinReceiverSession.state.collectAsStateWithLifecycle()
    val authed = state.musicAssistantAuthState == SendspinMusicAssistantAuthState.AUTHENTICATED
    val serverUrl = state.musicAssistantServerUrl?.takeIf { it.isNotBlank() && authed }
    val token = state.musicAssistantToken?.takeIf { authed }

    LaunchedEffect(serverUrl, token) {
        val info = serverUrl?.let { parseConnectionInfo(it, token) } ?: return@LaunchedEffect
        // Keep the app-side socket up: connect when disconnected, then re-check.
        // Exits once a command-ready session is live; resumes if it later drops.
        while (true) {
            if (!serviceClient.isReadyForCommands.value &&
                serviceClient.sessionState.value is SessionState.Disconnected
            ) {
                Timber.tag("MaConnect").d("connecting app-side MA client to %s", info.webUrl)
                serviceClient.connect(info)
            }
            delay(RECONNECT_POLL_MS)
        }
    }
}

private const val RECONNECT_POLL_MS = 4000L
private const val MA_DEFAULT_PORT = 8095

/** Parse `http(s)://host[:port]` (the receiver's stored MA URL) into a [ConnectionInfo]. */
internal fun parseConnectionInfo(url: String, token: String? = null): ConnectionInfo? = try {
    val uri = java.net.URI(url.trim())
    val host = uri.host ?: return null
    val isTls = uri.scheme.equals("https", ignoreCase = true)
    val port = uri.port.takeIf { it > 0 } ?: MA_DEFAULT_PORT
    ConnectionInfo(host = host, port = port, isTls = isTls, token = token)
} catch (e: Exception) {
    Timber.tag("MaConnect").w(e, "could not parse MA server url: %s", url)
    null
}
