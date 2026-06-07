package dev.jdtech.jellyfin.data.musicassistant.repository

import dev.jdtech.jellyfin.data.musicassistant.api.APICommands
import dev.jdtech.jellyfin.data.musicassistant.api.Request
import dev.jdtech.jellyfin.data.musicassistant.api.ServiceClient
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

    suspend fun playMedia(item: ServerMediaItem) {
        val payload = buildJsonObject {
            // Need to specify the queue id, for now let's leave it out or specify the active queue?
            // "queue_id" is usually required. If we don't have it here, we should fetch it from AppPreferences?
            put("media_item", buildJsonObject {
                put("uri", item.uri)
                put("media_type", item.mediaType)
                put("item_id", item.itemId)
                put("provider", item.provider)
            })
        }
        // Let's rely on the SendSpinGroupClient to play?
        // Let's just use the API command directly.
        val request = Request(command = APICommands.PLAYER_QUEUES_PLAY_MEDIA, args = payload)
        serviceClient.sendRequest(request)
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
