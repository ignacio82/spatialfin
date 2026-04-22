package dev.jdtech.jellyfin.player.xr.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder

internal data class WikipediaSummary(
    val title: String,
    val extract: String,
    val canonicalUrl: String?,
)

/**
 * Truncate [text] to at most [maxChars] characters, preferring a sentence
 * boundary when one sits reasonably close to the cap. Unlike `String.take()`,
 * which happily cuts mid-word or mid-clause, this keeps spoken replies from
 * ending on "a film directed by" or "the story of a" — the kind of dangling
 * truncation that makes the assistant sound broken. When no clean boundary
 * exists in the tail window, hard-trim and append an ellipsis so a listener
 * can hear that the thought was cut rather than mistaking the cutoff for the
 * speaker's final word.
 */
internal fun trimToSentenceBoundary(text: String, maxChars: Int): String {
    val trimmed = text.trim()
    if (trimmed.length <= maxChars) return trimmed
    val slice = trimmed.substring(0, maxChars)
    val lastEnd = maxOf(slice.lastIndexOf('.'), slice.lastIndexOf('!'), slice.lastIndexOf('?'))
    // Accept a sentence-end anywhere in the last quarter of the slice; any
    // earlier and we'd throw away too much of the tail.
    return if (lastEnd >= (maxChars * 3) / 4) {
        slice.substring(0, lastEnd + 1).trim()
    } else {
        slice.trim().trimEnd(',', ';', ':', '-', ' ') + "…"
    }
}

internal class WikipediaSummaryClient(
    private val client: OkHttpClient = OkHttpClient(),
) {
    suspend fun getSummary(query: String): WikipediaSummary? = withContext(Dispatchers.IO) {
        val normalized = query.trim()
        if (normalized.isBlank()) return@withContext null

        fetchPageSummary(normalized)
            ?: searchAndFetchSummary(normalized)
    }

    private fun fetchPageSummary(query: String): WikipediaSummary? {
        val encodedTitle = encode(query.replace(' ', '_'))
        val url = "$WIKIPEDIA_SUMMARY_URL/$encodedTitle"
        val request =
            Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                // REST v1 summary endpoint requires a user-agent that identifies the
                // client: without one Wikipedia happily returns 200 to curl but has
                // been observed to return 403/HTML from OkHttp's default UA.
                .header("User-Agent", USER_AGENT)
                .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("VOICE: wikipedia summary %s returned HTTP %d", url, response.code)
                    return@use null
                }
                val body = response.body.string()
                if (body.isBlank()) {
                    Timber.w("VOICE: wikipedia summary %s returned empty body", url)
                    return@use null
                }
                val json = JSONObject(body)
                val type = json.optString("type")
                // Disambiguation pages produce extracts like
                // "Arrival(s) or The Arrival(s) may refer to:" — a sentence that
                // dangles mid-thought and sounds broken when spoken aloud. Let
                // the search fallback land on a concrete article instead.
                if (type == "disambiguation") {
                    Timber.i("VOICE: wikipedia summary %s is a disambiguation page, skipping", url)
                    return@use null
                }
                val extract = json.optString("extract").trim()
                if (extract.isBlank()) {
                    Timber.w("VOICE: wikipedia summary %s extract was blank (type=%s)", url, type)
                    return@use null
                }
                Timber.i("VOICE: wikipedia summary ok title=%s extractLen=%d", json.optString("title"), extract.length)
                WikipediaSummary(
                    title = json.optString("title").ifBlank { query },
                    extract = extract,
                    canonicalUrl =
                        json.optJSONObject("content_urls")
                            ?.optJSONObject("desktop")
                            ?.optString("page")
                            ?.takeIf { it.isNotBlank() },
                )
            }
        }.getOrElse {
            Timber.w(it, "VOICE: wikipedia summary lookup threw for %s", url)
            null
        }
    }

    private fun searchAndFetchSummary(query: String): WikipediaSummary? {
        val url = buildSearchUrl(query)
        val request =
            Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                // Same reason as [fetchPageSummary] — Wikipedia's action API also
                // 403s OkHttp's default UA.
                .header("User-Agent", USER_AGENT)
                .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("VOICE: wikipedia search %s returned HTTP %d", url, response.code)
                    return@use null
                }
                val body = response.body.string()
                if (body.isBlank()) return@use null
                val hits =
                    JSONObject(body)
                        .optJSONObject("query")
                        ?.optJSONArray("search")
                        ?: JSONArray()
                val titles = (0 until hits.length())
                    .asSequence()
                    .mapNotNull { index -> hits.optJSONObject(index)?.optString("title") }
                    .filter { it.isNotBlank() }
                    .take(SEARCH_FALLBACK_HITS)
                    .toList()
                if (titles.isEmpty()) {
                    Timber.w("VOICE: wikipedia search %s returned no hits", url)
                    return@use null
                }
                // Walk the top hits. The first one is often a disambiguation
                // page — `fetchPageSummary` returns null for those, so we keep
                // going until we find an article with real content.
                for (title in titles) {
                    val summary = fetchPageSummary(title)
                    if (summary != null) {
                        Timber.i("VOICE: wikipedia search fell back to title=%s", title)
                        return@use summary
                    }
                }
                Timber.w("VOICE: wikipedia search %s exhausted %d candidates with no concrete article", url, titles.size)
                null
            }
        }.getOrElse {
            Timber.d(it, "VOICE: wikipedia search lookup failed for %s", query)
            null
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val WIKIPEDIA_SUMMARY_URL = "https://en.wikipedia.org/api/rest_v1/page/summary"
        private const val WIKIPEDIA_SEARCH_URL = "https://en.wikipedia.org/w/api.php?action=query&list=search"
        // How many search hits to probe when the top one is a disambiguation
        // page. Three covers the common case (disambiguation → concrete
        // article on 2nd/3rd slot) without an unbounded crawl per query.
        private const val SEARCH_FALLBACK_HITS = 3
        // See https://en.wikipedia.org/api/rest_v1/#/ — the policy asks clients to
        // identify themselves. OkHttp's default UA gets throttled on some endpoints.
        private const val USER_AGENT =
            "SpatialFin/1.0 (voice assistant; https://github.com/ignacio82/SpatialFin)"

        /**
         * Build the `action=query&list=search` URL for the given query. Exposed
         * for unit testing — a previous revision joined with `?srsearch=` instead
         * of `&srsearch=`, which made Wikipedia's API read `list=search?srsearch=...`
         * as one broken parameter and silently return zero hits. A snapshot test
         * against the string here is the cheapest defence against that recurring.
         */
        internal fun buildSearchUrl(query: String): String =
            "$WIKIPEDIA_SEARCH_URL&srsearch=${URLEncoder.encode(query, "UTF-8")}&format=json"
    }
}
