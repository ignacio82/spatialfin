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
        val serverUrl = prefs.all.keys
            .firstOrNull { it.startsWith("token_url:") }
            ?.removePrefix("token_url:")
            ?: return null
        val token = prefs.getString("token_url:$serverUrl", "")
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
            if (responseCode in 200..299) {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
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
                Timber.w("MA command %s failed (%d): %s", command, responseCode, errorBody)
                null
            }
        } catch (e: Exception) {
            Timber.w(e, "Error executing MA command %s", command)
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
     * Favorite tracks, most-recently-added first. The `favorite` filter on
     * `library_items` is the same one the MA web UI uses; empty when the user
     * has no favorites, which hides the home row.
     */
    suspend fun getFavorites(): List<SpatialFinItem> {
        val args = JSONObject()
            .put("favorite", true)
            .put("limit", 25)
            .put("order_by", "timestamp_added DESC")
        val array = executeCommand("music/tracks/library_items", args) ?: return emptyList()
        return parseItems(array)
    }

    /**
     * Library audiobooks, newest first. Empty when the server has no audiobook
     * providers configured — the home row then hides itself.
     */
    suspend fun getAudiobooks(): List<SpatialFinItem> {
        val args = JSONObject()
            .put("limit", 25)
            .put("order_by", "timestamp_added DESC")
        val array = executeCommand("music/audiobooks/library_items", args) ?: return emptyList()
        return parseItems(array)
    }

    /**
     * Library podcasts, newest first. Empty when the server has no podcast
     * providers configured — the home row then hides itself.
     */
    suspend fun getPodcasts(): List<SpatialFinItem> {
        val args = JSONObject()
            .put("limit", 25)
            .put("order_by", "timestamp_added DESC")
        val array = executeCommand("music/podcasts/library_items", args) ?: return emptyList()
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
                // Folder shape — drain its `items` array, keeping only the real
                // playable leaves. Folders nest folders (e.g. "In progress"
                // containing audiobook shelves), and those non-playable
                // placeholders have no artwork and aren't tappable, so they'd
                // only render as dead tiles in the row.
                for (j in 0 until nested.length()) {
                    nested.optJSONObject(j)?.takeIf { it.isPlayableLeaf() }?.let(flat::put)
                }
            } else if (entry.isPlayableLeaf()) {
                flat.put(entry)
            }
        }
        // Drop items that still resolve to no artwork — a poster-less tile is
        // just an empty rectangle in the carousel.
        return parseItems(flat).filter { it.images.primary != null }
    }

    /**
     * A directly playable music item suitable for the recommendations row.
     * Excludes folders/shelves, non-playable placeholders, and non-music media
     * (audiobooks, podcasts) — MA's recommendation folders mix those in, and
     * they typically have no cover art, so they'd render as dead, blank tiles
     * in a row billed as "Music Assistant".
     */
    private fun JSONObject.isPlayableLeaf(): Boolean {
        if (has("items")) return false
        if (!optBoolean("is_playable", true)) return false
        if (optString("media_type") !in MUSIC_MEDIA_TYPES) return false
        return has("item_id") || has("uri") || has("name")
    }

    private fun parseItems(array: JSONArray): List<SpatialFinItem> {
        // The image proxy needs the server base URL; resolve it once per batch.
        val serverUrl = getCredentials()?.first
        val items = mutableListOf<SpatialFinItem>()
        // Dedupe by the resolved id. MA's recommendations feed can surface the
        // same track across multiple folders, and library feeds can repeat an
        // item — two SpatialFinItems with the same UUID in one section's
        // LazyRow crash Compose with a duplicate-key IllegalArgumentException.
        val seen = HashSet<UUID>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val item = parseItem(obj, serverUrl) ?: continue
            if (seen.add(item.id)) items.add(item)
        }
        return items
    }

    private fun parseItem(obj: JSONObject, serverUrl: String?): SpatialFinItem? {
        val name = obj.optString("name", obj.optString("title", "Unknown"))
        val uriStr = obj.optString("uri", "")
        val id = obj.optString("item_id", uriStr.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString())

        // Extract image URL from the metadata.images array or image_url field
        val image = extractImageUrl(obj, serverUrl)

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

    private fun extractImageUrl(obj: JSONObject, serverUrl: String?): String? {
        // Try metadata.images array first
        val metadata = obj.optJSONObject("metadata")
        val imagesArray = metadata?.optJSONArray("images")
        if (imagesArray != null && imagesArray.length() > 0) {
            val firstImage = imagesArray.optJSONObject(0)
            val path = firstImage?.optString("path")
            if (!path.isNullOrBlank()) {
                val provider = firstImage.optString("provider", "")
                val remote = firstImage.optBoolean("remotely_accessible", false)
                return resolveImageUrl(path, provider, remote, serverUrl)
            }
        }
        // Fallback to image or image_url (already-resolved URLs). Guard with
        // isNull(): a JSON `null` value makes optString() return the literal
        // string "null", which would sail past a blank check and render as a
        // broken (blank) tile.
        if (!obj.isNull("image")) {
            val directImage = obj.optString("image", "")
            if (directImage.isNotBlank()) return directImage
        }
        if (!obj.isNull("image_url")) {
            val imageUrl = obj.optString("image_url", "")
            if (imageUrl.isNotBlank()) return imageUrl
        }
        return null
    }

    /**
     * Turn an MA image descriptor into a loadable URL. Remotely-accessible
     * http(s) paths are usable as-is; everything else (local library art,
     * provider-relative paths) must go through the server's image proxy —
     * otherwise [path] is not a real URL and the tile renders blank. Mirrors
     * OkHttpServiceClient.resolveImageUrl used by the search/detail screens.
     */
    private fun resolveImageUrl(path: String, provider: String, remotelyAccessible: Boolean, serverUrl: String?): String? {
        if (remotelyAccessible && path.startsWith("http")) return path
        val base = serverUrl?.trimEnd('/') ?: return path.takeIf { it.startsWith("http") }
        val encodedPath = Uri.encode(path)
        return "$base/imageproxy?path=$encodedPath&provider=$provider"
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

    private companion object {
        // MA media_type values that belong in a music-focused row. Audiobooks,
        // podcasts, and folders are intentionally excluded from recommendations.
        val MUSIC_MEDIA_TYPES = setOf("track", "album", "artist", "playlist", "radio")
    }
}
