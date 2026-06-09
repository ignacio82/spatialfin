package dev.jdtech.jellyfin.film.repository

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.models.SpatialFinImages
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.Rating
import dev.jdtech.jellyfin.models.SpatialFinChapter
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class MusicAssistantRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private fun getCredentials(): Pair<String, String>? {
        val prefs = context.getSharedPreferences("sendspin_music_assistant", Context.MODE_PRIVATE)
        val keys = prefs.all.keys
        android.util.Log.e("MusicAssistantRepo", "all keys in prefs: $keys")
        val serverUrl = keys.firstOrNull { it.startsWith("token_url:") }?.removePrefix("token_url:")
        android.util.Log.e("MusicAssistantRepo", "serverUrl: $serverUrl")
        if (serverUrl == null) return null
        val token = prefs.getString("token_url:$serverUrl", "")
        android.util.Log.e("MusicAssistantRepo", "token isBlank: ${token?.isBlank()}")
        if (token.isNullOrBlank()) return null
        return Pair(serverUrl, token)
    }

    /**
     * Execute an MA API command. The Music Assistant HTTP API accepts:
     * POST /api  { "command": "music/tracks/library_items", "args": { "limit": 25 } }
     */
    private suspend fun executeCommand(command: String, args: JSONObject? = null): JSONArray? = withContext(Dispatchers.IO) {
        val (serverUrl, token) = getCredentials() ?: return@withContext null
        val payload = JSONObject().put("command", command)
        if (args != null) payload.put("args", args)

        android.util.Log.e("MusicAssistantRepo", "Executing: $command with args: $args")

        try {
            val url = URL("${serverUrl.trimEnd('/')}/api")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            android.util.Log.e("MusicAssistantRepo", "response code: $responseCode")
            if (responseCode in 200..299) {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                android.util.Log.e("MusicAssistantRepo", "response body (first 500): ${body.take(500)}")
                // Response can be a raw JSON array or a JSON object with "result" or "data" array
                val trimmed = body.trim()
                if (trimmed.startsWith("[")) {
                    JSONArray(trimmed)
                } else {
                    val obj = JSONObject(trimmed)
                    obj.optJSONArray("result") ?: obj.optJSONArray("data") ?: JSONArray()
                }
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
                android.util.Log.e("MusicAssistantRepo", "error ($responseCode): $errorBody")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("MusicAssistantRepo", "Error executing MA command $command", e)
            null
        }
    }

    /**
     * Get recently added tracks from the library, sorted by timestamp_added descending.
     */
    suspend fun getRecentlyPlayed(): List<SpatialFinItem> {
        val args = JSONObject()
            .put("limit", 25)
            .put("order_by", "timestamp_added DESC")
        val array = executeCommand("music/tracks/library_items", args) ?: return emptyList()
        return parseItems(array)
    }

    /**
     * Get albums from the library, sorted by most recently added.
     */
    suspend fun getPlaylists(): List<SpatialFinItem> {
        val args = JSONObject()
            .put("limit", 25)
            .put("order_by", "timestamp_added DESC")
        val array = executeCommand("music/albums/library_items", args) ?: return emptyList()
        return parseItems(array)
    }

    /**
     * Pull MA's "recommendations" feed for the home row. MA returns this as
     * a list of folders, each with its own items array — we flatten across
     * folders for a single carousel row in Phase 2. Per-folder grouping
     * (multiple carousels) is a later phase improvement.
     *
     * Response shape (sampled from MA 2.x):
     *   [{ "id": "...", "name": "Quick picks", "items": [ ... ] }, ...]
     * or sometimes a flat array of items when there's only one folder.
     */
    suspend fun getRecommendations(): List<SpatialFinItem> {
        val raw = executeCommand("music/recommendations") ?: return emptyList()
        val flat = JSONArray()
        for (i in 0 until raw.length()) {
            val entry = raw.optJSONObject(i) ?: continue
            val nested = entry.optJSONArray("items")
            if (nested != null) {
                // Folder shape — drain its `items` array into our flat list.
                for (j in 0 until nested.length()) {
                    nested.optJSONObject(j)?.let(flat::put)
                }
            } else if (entry.has("item_id") || entry.has("uri") || entry.has("name")) {
                // Item shape — recognize by the fields parseItem actually reads.
                flat.put(entry)
            }
        }
        return parseItems(flat)
    }

    private fun parseItems(array: JSONArray): List<SpatialFinItem> {
        val items = mutableListOf<SpatialFinItem>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val item = parseItem(obj)
            if (item != null) items.add(item)
        }
        android.util.Log.e("MusicAssistantRepo", "Parsed ${items.size} items from ${array.length()} entries")
        return items
    }

    private fun parseItem(obj: JSONObject): SpatialFinItem? {
        val name = obj.optString("name", obj.optString("title", "Unknown"))
        val uriStr = obj.optString("uri", "")
        val id = obj.optString("item_id", uriStr.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString())

        // Extract image URL from the metadata.images array or image_url field
        val image = extractImageUrl(obj)

        // Extract artist name from artist metadata
        val artistName = extractArtistName(obj)

        return object : SpatialFinItem {
            override val id = UUID.nameUUIDFromBytes(id.toByteArray())
            override val name = name
            override val originalTitle = uriStr.takeIf { it.isNotBlank() }
            override val overview = artistName
            override val played = false
            override val favorite = obj.optBoolean("favorite", false)
            override val canPlay = true
            override val canDownload = false
            override val sources = emptyList<SpatialFinSource>()
            override val runtimeTicks = (obj.optDouble("duration", 0.0) * 10_000_000).toLong()
            override val playbackPositionTicks = 0L
            override val unplayedItemCount = null
            override val images = SpatialFinImages(primary = image?.let { Uri.parse(it) })
            override val chapters = emptyList<SpatialFinChapter>()
            override val ratings = emptyList<Rating>()
        }
    }

    private fun extractImageUrl(obj: JSONObject): String? {
        // Try metadata.images array first
        val metadata = obj.optJSONObject("metadata")
        val imagesArray = metadata?.optJSONArray("images")
        if (imagesArray != null && imagesArray.length() > 0) {
            val firstImage = imagesArray.optJSONObject(0)
            val path = firstImage?.optString("path")
            if (!path.isNullOrBlank()) return path
        }
        // Fallback to image or image_url
        val directImage = obj.optString("image", "")
        if (directImage.isNotBlank()) return directImage
        val imageUrl = obj.optString("image_url", "")
        if (imageUrl.isNotBlank()) return imageUrl
        return null
    }

    private fun extractArtistName(obj: JSONObject): String {
        // Try artists array
        val artists = obj.optJSONArray("artists")
        if (artists != null && artists.length() > 0) {
            val first = artists.optJSONObject(0)
            val name = first?.optString("name", "")
            if (!name.isNullOrBlank()) return name
        }
        // Try artist field
        val artist = obj.optString("artist", "")
        if (artist.isNotBlank()) return artist
        // Try album field for album items
        val album = obj.optJSONObject("album")
        val albumName = album?.optString("name", "")
        if (!albumName.isNullOrBlank()) return albumName
        return ""
    }
}
