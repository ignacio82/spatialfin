package dev.spatialfin.unified

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import dev.jdtech.jellyfin.data.musicassistant.api.ServiceClient
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerMediaItem
import dev.jdtech.jellyfin.data.musicassistant.repository.MaSessionRepository

/**
 * "Play this on the SendSpin receiver, and tell every UI surface it's
 * happening" — bundled so nested screens can fire the play action without
 * threading `MaSessionRepository` + `ServiceClient` + Context through every
 * intermediate composable.
 *
 * Installed once by each form-factor root (Beam, XR, TV) via
 * [LocalMaPlayDispatcher]. ViewModels take the two repository deps directly
 * (CompositionLocals aren't reachable from non-Compose code) and call
 * [dispatchMusicAssistantPlay] explicitly.
 */
class MaPlayDispatcher(
    private val context: Context,
    private val session: MaSessionRepository,
    private val serviceClient: ServiceClient,
) {
    /** Play by ServerMediaItem — the rich case with full metadata. */
    fun play(item: ServerMediaItem) {
        val uri = item.uri ?: return
        dispatchMusicAssistantPlay(context, session, serviceClient, item, uri)
    }

    /**
     * Play a raw MA URI when the caller doesn't have a typed
     * [ServerMediaItem] (e.g., a SpatialFinItem-backed card whose
     * `originalTitle` is the URI). Optimistic hint falls back to URI-derived
     * title.
     */
    fun playUri(uri: String, title: String? = null, artworkUrl: String? = null) {
        if (uri.isBlank()) return
        session.reportOptimisticPlay(
            uri = uri,
            title = title ?: uri.substringAfterLast('/'),
            artist = null,
            artworkUrl = artworkUrl,
            targetPlayerId = null,
        )
        dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverService
            .playMusicAssistantMedia(context, uri)
    }

    /**
     * Override which MA player future `play` calls target. Persists per MA
     * server id so the choice survives process death. Pass [playerId] = null
     * to clear the override and return to auto-detection (which prefers the
     * Universal-Player wrapper for THIS device's SendSpin endpoint).
     *
     * Writes to both the in-memory session (so UI surfaces reflect the
     * choice immediately) and SendSpin's shared prefs (so the next
     * `play_media` resolver pass honours it without restarting the service).
     */
    fun setPreferredPlayer(serverId: String?, playerId: String?) {
        session.setSelectedPlayer(playerId)
        dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverService
            .setPreferredPlayer(context, serverId, playerId)
    }
}

/**
 * Composition-scoped MA play dispatcher. Returns null when unavailable —
 * surfaces (TV mini-rows, previews) that aren't under a form-factor root
 * should gate on this.
 */
val LocalMaPlayDispatcher = compositionLocalOf<MaPlayDispatcher?> { null }
