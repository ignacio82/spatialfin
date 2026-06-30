package dev.spatialfin.unified

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import dev.jdtech.jellyfin.data.musicassistant.api.ServiceClient
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.QueueOption
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.RepeatMode
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerMediaItem
import dev.jdtech.jellyfin.data.musicassistant.repository.MaSessionRepository
import dev.jdtech.jellyfin.data.musicassistant.repository.MusicAssistantRepository
import dev.jdtech.jellyfin.data.musicassistant.repository.PlaybackPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

    /**
     * Play [item] and, once it's actually rolling, seek to [positionMs] —
     * audiobook chapter navigation, where chapters are seek offsets within a
     * single stream rather than separate tracks. Waits (bounded) for the
     * session to report this item playing so the seek lands on a live queue.
     */
    fun playThenSeek(item: ServerMediaItem, positionMs: Long) {
        play(item)
        val uri = item.uri ?: return
        if (positionMs <= 0L) return
        scope.launch {
            val ready = withTimeoutOrNull(20_000L) {
                session.session.first { s ->
                    s.nowPlaying?.uri == uri &&
                        s.playbackPhase != PlaybackPhase.Preparing &&
                        s.selectedPlayer != null
                }
            } ?: return@launch
            ready.selectedPlayer?.id?.let { repository.seekTo(it, positionMs) }
        }
    }

    // --- Transport (targets the selected player) --------------------------

    fun playPause() = transport { repository.playPause(it) }
    fun pause() = transport { repository.pause(it) }
    fun resume() = transport { repository.play(it) }
    fun next() = transport { repository.next(it) }
    fun previous() = transport { repository.previous(it) }
    fun seekTo(positionMs: Long) = transport { repository.seekTo(it, positionMs) }

    /**
     * Nudge the selected player's volume by [deltaFraction] of full scale
     * (e.g. +0.1 = +10 points). No-op when the player reports no current level.
     */
    fun nudgeVolume(deltaFraction: Float) {
        val player = session.session.value.selectedPlayer ?: return
        val current = player.volumeLevel ?: return
        val target = (current + (deltaFraction * 100f)).toInt().coerceIn(0, 100)
        scope.launch { repository.setVolume(player.id, target) }
    }

    /**
     * Stop playback AND dismiss the now-playing UI — Stop means "I'm done", so
     * the mini-player / now-playing screen disappear (unlike Pause). The session
     * suppresses the stopped track locally until the next play.
     */
    fun stop() {
        session.dismissPlayback()
        transport { repository.stop(it) }
    }

    /** Cycle repeat: Off → All → One → Off. Targets the active queue. */
    fun cycleRepeatMode() {
        val queueId = session.session.value.activeQueueId ?: return
        val next = when (session.session.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        scope.launch { repository.setRepeatMode(queueId, next) }
    }

    /** Toggle shuffle on the active queue. */
    fun toggleShuffle() {
        val queueId = session.session.value.activeQueueId ?: return
        val enabled = !session.session.value.shuffleEnabled
        scope.launch { repository.setShuffle(queueId, enabled) }
    }

    /** Set the active queue's repeat mode to a specific value (for MediaSession). */
    fun setRepeatMode(mode: RepeatMode) {
        val queueId = session.session.value.activeQueueId ?: return
        scope.launch { repository.setRepeatMode(queueId, mode) }
    }

    /** Set the active queue's shuffle state to a specific value (for MediaSession). */
    fun setShuffle(enabled: Boolean) {
        val queueId = session.session.value.activeQueueId ?: return
        scope.launch { repository.setShuffle(queueId, enabled) }
    }

    /** Set the selected player's volume (0–100). */
    fun setVolume(level: Int) {
        val playerId = session.session.value.selectedPlayer?.id ?: return
        scope.launch { repository.setVolume(playerId, level) }
    }

    /** Toggle mute on the selected player. */
    fun toggleMute() {
        val player = session.session.value.selectedPlayer ?: return
        scope.launch { repository.setMute(player.id, !player.volumeMuted) }
    }

    /** Toggle "Don't Stop The Music" auto-continuation on the active queue. */
    fun toggleDontStopTheMusic() {
        val queueId = session.session.value.activeQueueId ?: return
        val enabled = !session.session.value.dontStopTheMusic
        scope.launch { repository.setDontStopTheMusic(queueId, enabled) }
    }

    /** Add [memberId] to [leaderId]'s sync group (multi-room). */
    fun addToSyncGroup(leaderId: String, memberId: String) {
        if (leaderId.isBlank() || memberId.isBlank()) return
        scope.launch { repository.setGroupMembers(leaderId, playersToAdd = listOf(memberId)) }
    }

    /** Remove [memberId] from [leaderId]'s sync group. */
    fun removeFromSyncGroup(leaderId: String, memberId: String) {
        if (leaderId.isBlank() || memberId.isBlank()) return
        scope.launch { repository.setGroupMembers(leaderId, playersToRemove = listOf(memberId)) }
    }

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

    /** Lyrics for the track at [uri], or null when the provider has none. */
    suspend fun lyricsFor(uri: String): String? = repository.getTrackLyrics(uri)

    /** Chapters for the audiobook at [uri]; empty when not an audiobook / no chapters. */
    suspend fun chaptersFor(uri: String): List<dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerMediaItemChapter> =
        repository.getAudiobookChapters(uri)

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
            .playMusicAssistantMedia(
                context,
                uri,
                dev.jdtech.jellyfin.api.JellyfinApi.getInstance(context).userId?.toString(),
            )
    }

}

/**
 * Composition-scoped MA play dispatcher. Returns null when unavailable —
 * surfaces (TV mini-rows, previews) that aren't under a form-factor root
 * should gate on this.
 */
val LocalMaPlayDispatcher = compositionLocalOf<MaPlayDispatcher?> { null }
