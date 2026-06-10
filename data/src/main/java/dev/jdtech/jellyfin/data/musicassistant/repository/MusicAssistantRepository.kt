package dev.jdtech.jellyfin.data.musicassistant.repository

import dev.jdtech.jellyfin.data.musicassistant.api.APICommands
import dev.jdtech.jellyfin.data.musicassistant.api.Request
import dev.jdtech.jellyfin.data.musicassistant.api.ServiceClient
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.MediaType
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.RepeatMode
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.QueueOption
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.SearchResult
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerMediaItem
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerQueueItem
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class MusicAssistantRepository(
    private val serviceClient: ServiceClient
) {
    suspend fun search(query: String, mediaTypes: List<String> = listOf("artist", "album", "track", "playlist"), limit: Int = 20): SearchResult? {
        val payload = buildJsonObject {
            put("search_query", query)
            put("media_types", buildJsonArray { mediaTypes.forEach { add(it) } })
            put("limit", limit)
        }
        val request = Request(command = APICommands.MUSIC_SEARCH, args = payload)
        val response = serviceClient.sendRequest(request)
        if (response.isSuccess) {
            return response.getOrNull()?.resultAs<SearchResult>()
        }
        return null
    }

    /**
     * Queue [item] on the specified MA player/queue.
     *
     * The MA server requires a non-null `queue_id`. Callers must resolve this
     * — for `PlayerType.PROTOCOL` players (SendSpin endpoints) MA does NOT
     * create a queue, so the id has to come from the wrapping Universal
     * Player's `protocol_parent_id`. For all other player types pass the
     * player's own id and the server handles `active_group` / `synced_to`
     * redirection.
     *
     * When you're playing on this device specifically (the SendSpin
     * receiver), prefer [dev.jdtech.jellyfin.sendspin.receiver
     * .SendspinReceiverService.playMusicAssistantMedia] — it owns the
     * receiver identity and protocol-link race handling.
     */
    suspend fun playMedia(
        item: ServerMediaItem,
        queueOrPlayerId: String,
        option: QueueOption = QueueOption.PLAY,
        radioMode: Boolean = false,
    ) {
        val uri = item.uri ?: return
        serviceClient.sendRequest(
            Request.Library.play(
                media = listOf(uri),
                queueOrPlayerId = queueOrPlayerId,
                option = option,
                radioMode = radioMode,
            ),
        )
    }

    suspend fun getLibraryTracks(): List<ServerMediaItem> {
        val request = Request(command = APICommands.MUSIC_TRACKS_LIBRARY_ITEMS)
        val response = serviceClient.sendRequest(request)
        if (response.isSuccess) {
            return response.getOrNull()?.resultAs<List<ServerMediaItem>>() ?: emptyList()
        }
        return emptyList()
    }
    
    suspend fun getLibraryAlbums(): List<ServerMediaItem> {
        val request = Request(command = APICommands.MUSIC_ALBUMS_LIBRARY_ITEMS)
        val response = serviceClient.sendRequest(request)
        if (response.isSuccess) {
            return response.getOrNull()?.resultAs<List<ServerMediaItem>>() ?: emptyList()
        }
        return emptyList()
    }

    suspend fun getLibraryArtists(): List<ServerMediaItem> {
        val request = Request(command = APICommands.MUSIC_ARTISTS_LIBRARY_ITEMS)
        val response = serviceClient.sendRequest(request)
        if (response.isSuccess) {
            return response.getOrNull()?.resultAs<List<ServerMediaItem>>() ?: emptyList()
        }
        return emptyList()
    }

    suspend fun getLibraryPlaylists(): List<ServerMediaItem> {
        val request = Request(command = APICommands.MUSIC_PLAYLISTS_LIBRARY_ITEMS)
        val response = serviceClient.sendRequest(request)
        if (response.isSuccess) {
            return response.getOrNull()?.resultAs<List<ServerMediaItem>>() ?: emptyList()
        }
        return emptyList()
    }

    /** Library audiobooks, newest first. Empty when the server has no audiobook providers. */
    suspend fun getLibraryAudiobooks(limit: Int = 25): List<ServerMediaItem> {
        val response = serviceClient.sendRequest(
            Request.Audiobook.listLibrary(limit = limit, orderBy = "timestamp_added DESC"),
        )
        return if (response.isSuccess) {
            response.getOrNull()?.resultAs<List<ServerMediaItem>>().orEmpty()
        } else emptyList()
    }

    /** Library podcasts, newest first. Empty when the server has no podcast providers. */
    suspend fun getLibraryPodcasts(limit: Int = 25): List<ServerMediaItem> {
        val response = serviceClient.sendRequest(
            Request.Podcast.listLibrary(limit = limit, orderBy = "timestamp_added DESC"),
        )
        return if (response.isSuccess) {
            response.getOrNull()?.resultAs<List<ServerMediaItem>>().orEmpty()
        } else emptyList()
    }

    // -----------------------------------------------------------------------
    // Detail fetchers (Phase 2). All take the [ServerMediaItem] returned by
    // search / library / recommendations and dispatch via that item's
    // provider+item_id pair — MA's detail endpoints require both because the
    // same logical album can live in multiple providers (Tidal + Local), and
    // the server can only resolve the metadata + child list within one provider
    // at a time.
    // -----------------------------------------------------------------------

    suspend fun getAlbum(item: ServerMediaItem): ServerMediaItem? =
        getDetail(item) { id, prov -> Request.Album.get(id, prov) }

    suspend fun getAlbumTracks(item: ServerMediaItem, inLibraryOnly: Boolean = false): List<ServerMediaItem> =
        getList(item) { id, prov -> Request.Album.getTracks(id, prov, inLibraryOnly) }

    suspend fun getArtist(item: ServerMediaItem): ServerMediaItem? =
        getDetail(item) { id, prov -> Request.Artist.get(id, prov) }

    suspend fun getArtistAlbums(item: ServerMediaItem, inLibraryOnly: Boolean = false): List<ServerMediaItem> =
        getList(item) { id, prov -> Request.Artist.getAlbums(id, prov, inLibraryOnly) }

    suspend fun getArtistTracks(item: ServerMediaItem, inLibraryOnly: Boolean = false): List<ServerMediaItem> =
        getList(item) { id, prov -> Request.Artist.getTracks(id, prov, inLibraryOnly) }

    suspend fun getPlaylist(item: ServerMediaItem): ServerMediaItem? =
        getDetail(item) { id, prov -> Request.Playlist.get(id, prov) }

    suspend fun getPlaylistTracks(item: ServerMediaItem): List<ServerMediaItem> =
        getList(item) { id, prov -> Request.Playlist.getTracks(id, prov) }

    /** Full podcast header (carries description + provider metadata). */
    suspend fun getPodcast(item: ServerMediaItem): ServerMediaItem? =
        getDetail(item) { id, prov -> Request.Podcast.get(id, prov) }

    /** Episodes of a podcast, newest-first as the server returns them. */
    suspend fun getPodcastEpisodes(item: ServerMediaItem): List<ServerMediaItem> =
        getList(item) { id, prov -> Request.Podcast.getEpisodes(id, prov) }

    /**
     * Full audiobook header. Unlike podcasts, an audiobook is a single playable
     * item — its `metadata.chapters` are seek targets within that one stream,
     * not separate playable children.
     */
    suspend fun getAudiobook(item: ServerMediaItem): ServerMediaItem? =
        getDetail(item) { id, prov -> Request.Audiobook.get(id, prov) }

    suspend fun getRecommendations(): List<ServerMediaItem> {
        val request = Request(command = APICommands.MUSIC_RECOMMENDATIONS)
        val response = serviceClient.sendRequest(request)
        return if (response.isSuccess) {
            response.getOrNull()?.resultAs<List<ServerMediaItem>>().orEmpty()
        } else emptyList()
    }

    // -----------------------------------------------------------------------
    // Transport (Phase 5). `players/cmd/*` commands target a player id; MA
    // forwards them to that player's active queue. These back the Now Playing
    // transport buttons and the system MediaSession.
    // -----------------------------------------------------------------------

    suspend fun playPause(playerId: String): Boolean = playerCommand(playerId, "play_pause")
    suspend fun play(playerId: String): Boolean = playerCommand(playerId, "play")
    suspend fun pause(playerId: String): Boolean = playerCommand(playerId, "pause")
    suspend fun stop(playerId: String): Boolean = playerCommand(playerId, "stop")
    suspend fun next(playerId: String): Boolean = playerCommand(playerId, "next")
    suspend fun previous(playerId: String): Boolean = playerCommand(playerId, "previous")

    private suspend fun playerCommand(playerId: String, command: String): Boolean {
        if (playerId.isBlank()) return false
        return serviceClient.sendRequest(Request.Player.simpleCommand(playerId, command)).isSuccess
    }

    /** Seek the active queue to [positionMs]. */
    suspend fun seekTo(playerId: String, positionMs: Long): Boolean {
        if (playerId.isBlank()) return false
        return serviceClient.sendRequest(
            Request.Player.seek(playerId, (positionMs / 1000).coerceAtLeast(0)),
        ).isSuccess
    }

    /** Set the selected player's volume (0–100). */
    suspend fun setVolume(playerId: String, volumeLevel: Int): Boolean {
        if (playerId.isBlank()) return false
        return serviceClient.sendRequest(
            Request.Player.setVolume(playerId, volumeLevel.coerceIn(0, 100).toDouble()),
        ).isSuccess
    }

    /** Mute / unmute the selected player. */
    suspend fun setMute(playerId: String, muted: Boolean): Boolean {
        if (playerId.isBlank()) return false
        return serviceClient.sendRequest(Request.Player.setMute(playerId, muted)).isSuccess
    }

    /** Set the active queue's repeat mode (off / one / all). */
    suspend fun setRepeatMode(queueId: String, repeatMode: RepeatMode): Boolean {
        if (queueId.isBlank()) return false
        return serviceClient.sendRequest(Request.Queue.setRepeatMode(queueId, repeatMode)).isSuccess
    }

    /** Toggle shuffle on the active queue. */
    suspend fun setShuffle(queueId: String, enabled: Boolean): Boolean {
        if (queueId.isBlank()) return false
        return serviceClient.sendRequest(Request.Queue.setShuffle(queueId, enabled)).isSuccess
    }

    // -----------------------------------------------------------------------
    // Queue management (Phase 3). All take the resolved `queueId` from
    // [MaSessionRepository.MaSession.activeQueueId] — never a raw player id
    // unless that player has no separate queue (the session already resolves
    // active_source → current_media.queue_id → player_id in that order).
    // -----------------------------------------------------------------------

    /** Ordered items in [queueId]. Empty on failure. */
    suspend fun getQueueItems(queueId: String): List<ServerQueueItem> {
        if (queueId.isBlank()) return emptyList()
        val response = serviceClient.sendRequest(Request.Queue.items(queueId))
        return if (response.isSuccess) {
            response.getOrNull()?.resultAs<List<ServerQueueItem>>().orEmpty()
        } else emptyList()
    }

    /** Move [queueItemId] by [positionShift] places (negative = earlier). */
    suspend fun moveQueueItem(queueId: String, queueItemId: String, positionShift: Int): Boolean {
        if (positionShift == 0) return true
        return serviceClient.sendRequest(
            Request.Queue.moveItem(queueId, queueItemId, positionShift),
        ).isSuccess
    }

    /** Remove [queueItemId] from [queueId]. */
    suspend fun removeQueueItem(queueId: String, queueItemId: String): Boolean =
        serviceClient.sendRequest(Request.Queue.removeItem(queueId, queueItemId)).isSuccess

    /** Jump playback to [queueItemId] within [queueId]. */
    suspend fun playQueueIndex(queueId: String, queueItemId: String): Boolean =
        serviceClient.sendRequest(Request.Queue.playIndex(queueId, queueItemId)).isSuccess

    /**
     * Enqueue [uri] on [queueOrPlayerId] with the given [option]
     * (PLAY / NEXT / ADD) — the backing call for the track context menu's
     * "Play next" / "Add to queue" actions.
     */
    suspend fun enqueue(
        uri: String,
        queueOrPlayerId: String,
        option: QueueOption,
        radioMode: Boolean = false,
    ): Boolean = enqueueUris(listOf(uri), queueOrPlayerId, option, radioMode)

    /**
     * Enqueue several [uris] in one `play_media` call — the bulk path for
     * multi-select. MA preserves list order, so NEXT/ADD insert the whole batch
     * in the order given.
     */
    suspend fun enqueueUris(
        uris: List<String>,
        queueOrPlayerId: String,
        option: QueueOption,
        radioMode: Boolean = false,
    ): Boolean {
        val clean = uris.filter { it.isNotBlank() }
        if (clean.isEmpty() || queueOrPlayerId.isBlank()) return false
        return serviceClient.sendRequest(
            Request.Library.play(
                media = clean,
                queueOrPlayerId = queueOrPlayerId,
                option = option,
                radioMode = radioMode,
            ),
        ).isSuccess
    }

    // -----------------------------------------------------------------------
    // Favorites (Phase 3).
    // -----------------------------------------------------------------------

    suspend fun addFavorite(uri: String): Boolean {
        if (uri.isBlank()) return false
        return serviceClient.sendRequest(Request.Library.addFavorite(uri)).isSuccess
    }

    suspend fun removeFavorite(itemId: String, mediaType: MediaType): Boolean {
        if (itemId.isBlank()) return false
        return serviceClient.sendRequest(Request.Library.removeFavorite(itemId, mediaType)).isSuccess
    }

    // -----------------------------------------------------------------------
    // Party mode (Phase 4). The MA Party plugin registers a selectable source
    // on a player; activating it via select_source switches that player to the
    // vote-driven party queue.
    // -----------------------------------------------------------------------

    suspend fun selectSource(playerId: String, sourceId: String): Boolean {
        if (playerId.isBlank() || sourceId.isBlank()) return false
        return serviceClient.sendRequest(Request.Player.selectSource(playerId, sourceId)).isSuccess
    }

    /**
     * The Party plugin's guest join URL (the QR target). The plugin builds it as
     * `https://app.music-assistant.io/?remote_id=…&join=<code>` when remote
     * access is on, else `<base_url>/?join=<code>`. Returns null when the plugin
     * isn't loaded or has no party configured.
     */
    suspend fun getPartyUrl(): String? = try {
        val response = serviceClient.sendRequest(Request(command = APICommands.PARTY_URL))
        if (response.isSuccess) {
            response.getOrNull()?.resultAs<String>()?.takeIf { it.isNotBlank() }
        } else null
    } catch (e: Exception) {
        null
    }

    /**
     * Resolve the share-QR state in one shot so the UI can give the user an
     * actionable message instead of a blank panel:
     *
     *  - [PartyJoinInfo.url] non-null  → render the QR.
     *  - url null, [PartyJoinInfo.pluginAvailable] true → the Party plugin is
     *    loaded but guest access is OFF (the only reason `party/url` returns
     *    null for an authenticated user) → tell the user to enable it.
     *  - url null, pluginAvailable false → the Party plugin isn't installed /
     *    loaded on this server.
     *
     * `party/config` is the probe: it succeeds whenever the provider is loaded,
     * regardless of guest-access state.
     */
    suspend fun getPartyJoinInfo(): PartyJoinInfo {
        getPartyUrl()?.let { return PartyJoinInfo(url = it, pluginAvailable = true) }
        val pluginAvailable = try {
            serviceClient.sendRequest(Request(command = APICommands.PARTY_CONFIG)).isSuccess
        } catch (e: Exception) {
            false
        }
        return PartyJoinInfo(url = null, pluginAvailable = pluginAvailable)
    }

    /** Convenience: toggle [item]'s favorite flag. Returns the new state, or null on failure. */
    suspend fun setFavorite(item: ServerMediaItem, favorite: Boolean): Boolean? {
        val ok = if (favorite) {
            item.uri?.let { addFavorite(it) } ?: false
        } else {
            MediaType.fromServer(item.mediaType)
                ?.let { removeFavorite(item.itemId, it) } ?: false
        }
        return if (ok) favorite else null
    }

    // -----------------------------------------------------------------------
    // Playlist editing (Phase 3). `db_playlist_id` is the library item id of an
    // *editable* playlist (ServerMediaItem.isEditable == true).
    // -----------------------------------------------------------------------

    /** Create an empty playlist named [name]; returns the new playlist item. */
    suspend fun createPlaylist(name: String): ServerMediaItem? {
        if (name.isBlank()) return null
        val response = serviceClient.sendRequest(Request.Playlist.create(name))
        return if (response.isSuccess) response.getOrNull()?.resultAs<ServerMediaItem>() else null
    }

    suspend fun addTracksToPlaylist(playlistId: String, trackUris: List<String>): Boolean {
        if (playlistId.isBlank() || trackUris.isEmpty()) return false
        return serviceClient.sendRequest(
            Request.Playlist.addTracks(playlistId, trackUris),
        ).isSuccess
    }

    suspend fun removeTracksFromPlaylist(playlistId: String, positions: List<Int>): Boolean {
        if (playlistId.isBlank() || positions.isEmpty()) return false
        return serviceClient.sendRequest(
            Request.Playlist.removeTracks(playlistId, positions),
        ).isSuccess
    }

    /**
     * Remove a library item entirely — used to delete an (editable) playlist.
     * MA has no dedicated delete-playlist command; removing it from the library
     * is the delete path.
     */
    suspend fun removeFromLibrary(itemId: String, mediaType: MediaType): Boolean {
        if (itemId.isBlank()) return false
        return serviceClient.sendRequest(Request.Library.remove(itemId, mediaType)).isSuccess
    }

    private suspend fun getDetail(
        item: ServerMediaItem,
        request: (itemId: String, providerInstance: String) -> Request,
    ): ServerMediaItem? {
        val provider = item.providerInstanceOrDomain() ?: return null
        val response = serviceClient.sendRequest(request(item.itemId, provider))
        return if (response.isSuccess) response.getOrNull()?.resultAs<ServerMediaItem>() else null
    }

    private suspend fun getList(
        item: ServerMediaItem,
        request: (itemId: String, providerInstance: String) -> Request,
    ): List<ServerMediaItem> {
        val provider = item.providerInstanceOrDomain() ?: return emptyList()
        val response = serviceClient.sendRequest(request(item.itemId, provider))
        return if (response.isSuccess) {
            response.getOrNull()?.resultAs<List<ServerMediaItem>>().orEmpty()
        } else emptyList()
    }

    /**
     * MA's child-fetcher endpoints (`/album/tracks`, `/artist/albums`, etc.)
     * key off provider_instance_id_or_domain. The top-level [ServerMediaItem]
     * exposes a `provider` field which is sometimes the instance id and
     * sometimes the domain — fall back to the first entry in
     * `provider_mappings` so locally-imported items still resolve.
     */
    private fun ServerMediaItem.providerInstanceOrDomain(): String? =
        provider.takeIf { it.isNotBlank() }
            ?: providerMappings?.firstOrNull()?.providerInstance
}

/**
 * Resolved state for the party share-QR. See
 * [MusicAssistantRepository.getPartyJoinInfo].
 */
data class PartyJoinInfo(
    val url: String?,
    val pluginAvailable: Boolean,
)
