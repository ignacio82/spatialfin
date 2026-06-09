package dev.spatialfin.unified

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import dev.jdtech.jellyfin.data.musicassistant.api.ServiceClient
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.QueueOption
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerMediaItem
import dev.jdtech.jellyfin.data.musicassistant.repository.MaSessionRepository
import dev.jdtech.jellyfin.data.musicassistant.repository.MusicAssistantRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
    // Plain repository wrapper over the same WS client — no Hilt needed, so the
    // form-factor roots don't have to thread another dependency in.
    private val repository = MusicAssistantRepository(serviceClient)

    // Owns the short-lived RPC coroutines for context-menu actions. The
    // dispatcher is `remember`ed in each root, so this scope lives for the
    // composition. Main.immediate so the optional result callbacks land on the
    // UI thread for snackbars / favorite-icon flips.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Play by ServerMediaItem — the rich case with full metadata. */
    fun play(item: ServerMediaItem) {
        val uri = item.uri ?: return
        dispatchMusicAssistantPlay(context, session, serviceClient, item, uri)
    }

    // --- Transport (targets the selected player) --------------------------

    fun playPause() = transport { repository.playPause(it) }
    fun next() = transport { repository.next(it) }
    fun previous() = transport { repository.previous(it) }
    fun stop() = transport { repository.stop(it) }
    fun seekTo(positionMs: Long) = transport { repository.seekTo(it, positionMs) }

    private fun transport(action: suspend (playerId: String) -> Unit) {
        val playerId = session.session.value.selectedPlayer?.id ?: return
        scope.launch { action(playerId) }
    }

    /** Queue [item] to play right after the current track. */
    fun playNext(item: ServerMediaItem) = enqueue(item, QueueOption.NEXT)

    /** Append [item] to the end of the active queue. */
    fun addToQueue(item: ServerMediaItem) = enqueue(item, QueueOption.ADD)

    private fun enqueue(item: ServerMediaItem, option: QueueOption) {
        val uri = item.uri ?: return
        val queueId = session.session.value.activeQueueId
        // With nothing playing there's no queue to attach to — NEXT/ADD would
        // be a no-op, so fall back to starting playback.
        if (queueId.isNullOrBlank()) {
            play(item)
            return
        }
        scope.launch { repository.enqueue(uri, queueId, option) }
    }

    // --- Bulk variants for multi-select -----------------------------------

    /** Queue [items] to play right after the current track, preserving order. */
    fun playNext(items: List<ServerMediaItem>) = enqueueMany(items, QueueOption.NEXT)

    /** Append [items] to the end of the active queue, preserving order. */
    fun addToQueue(items: List<ServerMediaItem>) = enqueueMany(items, QueueOption.ADD)

    private fun enqueueMany(items: List<ServerMediaItem>, option: QueueOption) {
        val uris = items.mapNotNull { it.uri }.filter { it.isNotBlank() }
        if (uris.isEmpty()) return
        val queueId = session.session.value.activeQueueId
        // Nothing playing → no queue to attach to; just start the first item.
        if (queueId.isNullOrBlank()) {
            items.firstOrNull()?.let { play(it) }
            return
        }
        scope.launch { repository.enqueueUris(uris, queueId, option) }
    }

    /** Set the favorite flag on every item in [items]. */
    fun toggleFavorite(
        items: List<ServerMediaItem>,
        makeFavorite: Boolean,
        onDone: () -> Unit = {},
    ) {
        scope.launch {
            items.forEach { repository.setFavorite(it, makeFavorite) }
            onDone()
        }
    }

    /** Append every item in [items] to [playlistId] in a single RPC. */
    fun addToPlaylist(
        playlistId: String,
        items: List<ServerMediaItem>,
        onResult: (Boolean) -> Unit = {},
    ) {
        val uris = items.mapNotNull { it.uri }.filter { it.isNotBlank() }
        if (uris.isEmpty()) {
            onResult(false)
            return
        }
        scope.launch { onResult(repository.addTracksToPlaylist(playlistId, uris)) }
    }

    /**
     * Toggle [item]'s favorite flag. [onResult] receives the resulting state
     * (on the main thread) so the caller can flip its icon; on RPC failure it
     * receives the *unchanged* prior state.
     */
    fun toggleFavorite(
        item: ServerMediaItem,
        makeFavorite: Boolean,
        onResult: (Boolean) -> Unit = {},
    ) {
        scope.launch {
            val newState = repository.setFavorite(item, makeFavorite)
            onResult(newState ?: !makeFavorite)
        }
    }

    /** Editable (user-owned) playlists, for the "Add to playlist" picker. */
    suspend fun editablePlaylists(): List<ServerMediaItem> =
        repository.getLibraryPlaylists().filter { it.isEditable == true }

    /** Append [item] to [playlistId]. [onResult] reports success on the main thread. */
    fun addToPlaylist(
        playlistId: String,
        item: ServerMediaItem,
        onResult: (Boolean) -> Unit = {},
    ) {
        val uri = item.uri
        if (uri.isNullOrBlank()) {
            onResult(false)
            return
        }
        scope.launch { onResult(repository.addTracksToPlaylist(playlistId, listOf(uri))) }
    }

    /** Create a new playlist named [name]; returns the created item or null. */
    suspend fun createPlaylist(name: String): ServerMediaItem? =
        repository.createPlaylist(name)

    /**
     * Toggle the selected player's Party-plugin source. Entering selects the
     * party source; leaving switches back to the player's default source (when
     * one is known — otherwise leaving is a no-op and the user picks a source
     * elsewhere). No-op when the selected player exposes no party source.
     */
    fun togglePartyMode() {
        val party = session.session.value.partySource ?: return
        val target = if (party.active) party.defaultSourceId else party.id
        if (target.isNullOrBlank()) return
        scope.launch { repository.selectSource(party.playerId, target) }
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
