package dev.jdtech.jellyfin.player.xr.voice

import dev.jdtech.jellyfin.models.companion.CompanionEndpoint
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

/**
 * Fronts a web search backend for the voice chat pipeline. Prefers the paired
 * SpatialFin-Companion's `/api/v1/search` proxy (authenticated with the
 * existing setup-token), falls back to a user-pasted SearXNG URL. Graceful
 * no-op when neither is configured.
 *
 * Intentionally small: the only consumer is [ChatToolRegistry]'s `web_search`
 * action, and that consumer already caps itself at one lookup per turn, so we
 * don't need pagination, caching, or request coalescing here.
 */
internal class WebSearchClient(
    private val appPreferences: AppPreferences,
    private val client: OkHttpClient = voiceHttpClient,
) {
    /**
     * True when *some* search endpoint is available. Pure preference lookup —
     * no network probe — so this is safe to call on the hot path. The actual
     * reachability check happens inside [search] and a failure there just
     * returns an empty list.
     */
    fun isConfigured(): Boolean {
        if (!appPreferences.getValue(appPreferences.voiceAssistantWebSearchEnabled)) return false
        if (resolvedDirectSearxngUrl() != null) return true
        return companionEndpointOrNull() != null
    }

    /**
     * Issue a single query and return up to [maxResults] hits. Never throws —
     * network or parse failures just yield an empty list so callers can stay on
     * the happy path.
     */
    suspend fun search(query: String, maxResults: Int = 5): List<WebSearchHit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext emptyList()
        val trimmed = query.trim()
        if (trimmed.isEmpty() || trimmed.length > 256) return@withContext emptyList()

        val direct = resolvedDirectSearxngUrl()
        val request = if (direct != null) {
            buildDirectSearxngRequest(direct, trimmed)
        } else {
            buildCompanionRequest(trimmed)
        } ?: return@withContext emptyList()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("WebSearch: %s returned HTTP %d", request.url, response.code)
                    return@use emptyList()
                }
                val body = response.body.string().ifBlank { return@use emptyList() }
                parseResults(body).take(maxResults)
            }
        } catch (e: Exception) {
            Timber.w(e, "WebSearch: request failed for %s", request.url)
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
        val endpoint = companionEndpointOrNull() ?: return null
        return endpoint.authorizedRequest(endpoint.searchUrl(query))
            .header("Accept", "application/json")
            .get()
            .build()
    }

    private fun companionEndpointOrNull(): CompanionEndpoint? =
        runCatching {
            CompanionEndpoint.parse(
                appPreferences.getValue(appPreferences.companionUrl),
                appPreferences.getValue(appPreferences.companionToken),
            )
        }.getOrNull()

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
            val allowed = httpUrl.isHttps || isLoopbackHost(httpUrl.host)
            if (!allowed) {
                Timber.w("WebSearch: ignoring non-HTTPS SearXNG URL host=%s", httpUrl.host)
                return null
            }
            return httpUrl.toString()
        }

        private fun isLoopbackHost(host: String): Boolean =
            host.equals("localhost", ignoreCase = true) ||
                host == "127.0.0.1" ||
                host == "::1" ||
                host == "[::1]"
    }
}
