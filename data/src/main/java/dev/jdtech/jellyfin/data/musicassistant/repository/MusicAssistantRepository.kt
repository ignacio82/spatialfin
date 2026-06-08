package dev.jdtech.jellyfin.data.musicassistant.repository

import dev.jdtech.jellyfin.data.musicassistant.api.APICommands
import dev.jdtech.jellyfin.data.musicassistant.api.Request
import dev.jdtech.jellyfin.data.musicassistant.api.ServiceClient
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.QueueOption
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.SearchResult
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerMediaItem
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
}
