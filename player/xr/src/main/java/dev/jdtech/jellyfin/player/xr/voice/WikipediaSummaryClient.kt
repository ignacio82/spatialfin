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
        val request =
            Request.Builder()
                .url("$WIKIPEDIA_SUMMARY_URL/$encodedTitle")
                .header("Accept", "application/json")
                .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body.string()
                if (body.isBlank()) return@use null
                val json = JSONObject(body)
                val extract = json.optString("extract").trim()
                if (extract.isBlank()) return@use null
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
            Timber.d(it, "VOICE: wikipedia summary lookup failed for %s", query)
            null
        }
    }

    private fun searchAndFetchSummary(query: String): WikipediaSummary? {
        val request =
            Request.Builder()
                .url("$WIKIPEDIA_SEARCH_URL?srsearch=${encode(query)}&format=json")
                .header("Accept", "application/json")
                .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body.string()
                if (body.isBlank()) return@use null
                val hits =
                    JSONObject(body)
                        .optJSONObject("query")
                        ?.optJSONArray("search")
                        ?: JSONArray()
                val firstTitle =
                    (0 until hits.length())
                        .asSequence()
                        .mapNotNull { index -> hits.optJSONObject(index)?.optString("title") }
                        .firstOrNull { it.isNotBlank() }
                        ?: return@use null
                fetchPageSummary(firstTitle)
            }
        }.getOrElse {
            Timber.d(it, "VOICE: wikipedia search lookup failed for %s", query)
            null
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        private const val WIKIPEDIA_SUMMARY_URL = "https://en.wikipedia.org/api/rest_v1/page/summary"
        private const val WIKIPEDIA_SEARCH_URL = "https://en.wikipedia.org/w/api.php?action=query&list=search"
    }
}
