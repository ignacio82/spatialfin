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

    suspend fun getRecommendations(): List<ServerMediaItem> {
        val request = Request(command = APICommands.MUSIC_RECOMMENDATIONS)
        val response = serviceClient.sendRequest(request)
        return if (response.isSuccess) {
            response.getOrNull()?.resultAs<List<ServerMediaItem>>().orEmpty()
        } else emptyList()
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
