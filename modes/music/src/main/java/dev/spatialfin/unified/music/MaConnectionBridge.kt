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
import dev.jdtech.jellyfin.sendspin.receiver.SendspinUserScope
import dev.jdtech.jellyfin.session.SessionMode
import dev.jdtech.jellyfin.session.SessionNavigationPolicy
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Opens the app-side Music Assistant WebSocket keyed on `(sessionMode, activeSessionUserId,
 * sendspinState.userScopeGeneration, serverUrl, token)` whenever MA overlay is allowed and the
 * hydrated [SendspinUserScope] matches the active session. Intentionally has no unconditional
 * `DisposableEffect` disconnect so configuration changes and background playback stay connected
 * while unlocked (App Lock `LOCKED` and session switches disconnect via `CompanionUserExtrasApplier`).
 */
@Composable
fun MaConnectionBridge(
    serviceClient: ServiceClient,
    sessionMode: SessionMode = SessionMode.SERVER_AUTHENTICATED,
    activeSessionUserId: String? = null,
) {
    val state by SendspinReceiverSession.state.collectAsStateWithLifecycle()
    val overlayAllowed = SessionNavigationPolicy.isMaOverlayAllowed(sessionMode)
    val scopeMatches = when (val activeScope = state.activeUserScope) {
        SendspinUserScope.Disabled -> false
        SendspinUserScope.Standalone -> sessionMode == SessionMode.STANDALONE_SOURCES
        is SendspinUserScope.Authenticated ->
            sessionMode == SessionMode.SERVER_AUTHENTICATED &&
                activeSessionUserId != null &&
                activeScope.userId == activeSessionUserId
    }
    val authed = overlayAllowed && scopeMatches &&
        state.musicAssistantAuthState == SendspinMusicAssistantAuthState.AUTHENTICATED
    val serverUrl = state.musicAssistantServerUrl?.takeIf { it.isNotBlank() && authed }
    val token = state.musicAssistantToken?.takeIf { authed }

    LaunchedEffect(
        sessionMode,
        activeSessionUserId,
        state.userScopeGeneration,
        serverUrl,
        token,
    ) {
        val info = serverUrl?.let { parseConnectionInfo(it, token) } ?: return@LaunchedEffect
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
