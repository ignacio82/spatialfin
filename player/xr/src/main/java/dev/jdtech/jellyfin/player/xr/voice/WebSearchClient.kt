package dev.jdtech.jellyfin.player.xr.voice

import dev.jdtech.jellyfin.models.companion.CompanionEndpoint
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.network.newBuilderWithSsrfGuard
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

internal data class WebSearchHit(
    val title: String,
    val snippet: String,
    val url: String,
)

internal class WebSearchClient(
    private val appPreferences: AppPreferences,
    private val client: OkHttpClient = voiceHttpClient,
) {
    internal val companionClient: OkHttpClient =
        client.newBuilderWithSsrfGuard(UrlTargetPolicy.LAN_ONLY).build()

    fun isConfigured(): Boolean {
        if (!appPreferences.getValue(appPreferences.voiceAssistantWebSearchEnabled)) return false
        if (resolvedDirectSearxngUrl() != null) return true
        return CompanionEndpoint.fromPreferences(appPreferences) != null
    }

    suspend fun search(query: String, maxResults: Int = 5): List<WebSearchHit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext emptyList()
        val trimmed = query.trim()
        if (trimmed.isEmpty() || trimmed.length > 256) return@withContext emptyList()

        val direct = resolvedDirectSearxngUrl()
        val useCompanion = direct == null
        val request = if (direct != null) {
            buildDirectSearxngRequest(direct, trimmed)
        } else {
            buildCompanionRequest(trimmed)
        } ?: return@withContext emptyList()

        val activeClient = if (useCompanion) companionClient else client
        try {
            activeClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("WebSearch: %s returned HTTP %d", request.url.toString().substringBefore('?'), response.code)
                    return@use emptyList()
                }
                val body = response.body.string().ifBlank { return@use emptyList() }
                parseResults(body).take(maxResults)
            }
        } catch (e: Exception) {
            Timber.w(e, "WebSearch: request failed for %s", request.url.toString().substringBefore('?'))
            emptyList()
        }
    }

    private fun resolvedDirectSearxngUrl(): String? =
        sanitizeSearxngUrl(appPreferences.getValue(appPreferences.voiceAssistantSearxngUrl))

    private fun buildDirectSearxngRequest(baseUrl: String, query: String): Request? {
        val httpUrl = baseUrl.toHttpUrlOrNull() ?: return null
        val withPath = if (httpUrl.pathSegments.lastOrNull().isNullOrBlank()) {
            httpUrl.newBuilder().addPathSegment("search").build()
        } else httpUrl
        val finalUrl = withPath.newBuilder()
            .setQueryParameter("q", query)
            .setQueryParameter("format", "json")
            .setQueryParameter("safesearch", "0")
            .build()
        return Request.Builder()
            .url(finalUrl)
            .header("Accept", "application/json")
            .get()
            .build()
    }

    private fun buildCompanionRequest(query: String): Request? {
        val endpoint = CompanionEndpoint.fromPreferences(appPreferences) ?: return null
        return endpoint.authorizedRequest(endpoint.searchUrl(query))
            .header("Accept", "application/json")
            .get()
            .build()
    }

    /**
     * Accepts both SearXNG JSON (`results: [{title, content, url, ...}]`) and
     * the trimmed companion shape (`results: [{title, snippet, url}]`).
     */
    private fun parseResults(body: String): List<WebSearchHit> {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val array = json.optJSONArray("results") ?: return emptyList()
        val out = mutableListOf<WebSearchHit>()
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val title = entry.optString("title").trim()
            val url = entry.optString("url").trim()
            if (title.isBlank() || url.isBlank()) continue
            val snippet = sequenceOf("snippet", "content", "pretty_url")
                .mapNotNull { key -> entry.optString(key).takeIf { it.isNotBlank() } }
                .firstOrNull()
                ?.replace(WHITESPACE_REGEX, " ")
                ?.trim()
                ?.take(SNIPPET_CHAR_CAP)
                .orEmpty()
            out.add(WebSearchHit(title = title, snippet = snippet, url = url))
        }
        return out
    }

    companion object {
        private const val SNIPPET_CHAR_CAP = 320
        private val WHITESPACE_REGEX = Regex("\\s+")

        /**
         * Validate and normalize a user-pasted SearXNG base URL. The assistant
         * query (which can echo the user's library and chat context) is sent in
         * the URL, so cleartext over the LAN is a real exfiltration / MITM
         * surface. Policy: require `https`, with the sole exception of a
         * loopback host (`127.0.0.1` / `::1` / `localhost`) where `http` is not
         * remotely observable — that covers the supported companion-sidecar
         * topology. Returns null for blank, unparseable, or non-conforming URLs
         * (which degrades [search] to the companion path or a graceful no-op).
         */
        internal fun sanitizeSearxngUrl(raw: String?): String? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isBlank()) return null
            val httpUrl = trimmed.toHttpUrlOrNull() ?: return null
            if (!httpUrl.isHttps) {
                Timber.w("WebSearch: ignoring non-HTTPS SearXNG URL host=%s", httpUrl.host)
                return null
            }
            return httpUrl.toString()
        }
    }
}
