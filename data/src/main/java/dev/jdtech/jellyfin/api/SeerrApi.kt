package dev.jdtech.jellyfin.api

import dev.jdtech.jellyfin.settings.domain.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

data class SeerrSearchOutcome(
    val response: SeerrSearchResponse? = null,
    val errorMessage: String? = null,
)

class SeerrApi(
    private val appPreferences: AppPreferences,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun buildRequest(
        pathSegments: List<String>,
        queryParams: Map<String, String> = emptyMap(),
        method: String = "GET",
        body: String? = null,
    ): Request? {
        val baseUrl = appPreferences.getValue(appPreferences.seerrUrl)?.trim().orEmpty().trimEnd('/')
        val apiKey = appPreferences.getValue(appPreferences.seerrApiKey)?.trim().orEmpty()
        val parsedBaseUrl = baseUrl.toHttpUrlOrNull()
        if (parsedBaseUrl == null) {
            Timber.w("Seerr request skipped: invalid base URL `%s`", baseUrl)
            return null
        }

        val url =
            parsedBaseUrl.newBuilder()
                .addPathSegment("api")
                .addPathSegment("v1")
                .apply {
                    pathSegments.forEach(::addPathSegment)
                    queryParams.forEach { (key, value) -> addQueryParameter(key, value) }
                }
                .build()

        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-Api-Key", apiKey)
            .header("X-API-KEY", apiKey)

        if (method == "POST" && body != null) {
            builder.post(body.toRequestBody("application/json".toMediaType()))
        } else if (method == "POST") {
            builder.post("".toRequestBody())
        }
        
        return builder.build()
    }

    suspend fun search(query: String): SeerrSearchResponse? = searchDetailed(query).response

    suspend fun searchDetailed(query: String): SeerrSearchOutcome = withContext(Dispatchers.IO) {
        if (!appPreferences.getValue(appPreferences.seerrEnabled)) {
            Timber.d("Seerr search skipped: disabled in settings")
            return@withContext SeerrSearchOutcome()
        }

        val baseUrl = appPreferences.getValue(appPreferences.seerrUrl)
        if (baseUrl.isNullOrBlank()) {
            Timber.w("Seerr search skipped: no server URL configured")
            return@withContext SeerrSearchOutcome(
                errorMessage = "Jellyseerr is enabled, but no server URL is configured.",
            )
        }

        val apiKey = appPreferences.getValue(appPreferences.seerrApiKey)?.trim().orEmpty()
        if (apiKey.isBlank()) {
            Timber.w("Seerr search skipped: no API key configured for %s", baseUrl)
            return@withContext SeerrSearchOutcome(
                errorMessage = "Jellyseerr is enabled, but no API key is configured.",
            )
        }

        try {
            val request =
                buildRequest(
                    pathSegments = listOf("search"),
                    queryParams = mapOf("query" to query),
                ) ?: return@withContext SeerrSearchOutcome(
                    errorMessage = "Jellyseerr URL is invalid. Check the server URL in settings.",
                )
            // Intentionally don't log request.url — it can land in logcat or
            // the companion log upload and, for any provider that puts the
            // credential in the URL (TMDB / OMDb-style ?apikey=…), would
            // exfiltrate the key. Seerr uses X-Api-Key today, but the same
            // log-shape must stay key-safe if we ever switch transports.
            Timber.i("Seerr search request keyConfigured=%b query=%s", apiKey.isNotBlank(), query)
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext SeerrSearchOutcome()
                SeerrSearchOutcome(response = json.decodeFromString<SeerrSearchResponse>(body))
            } else {
                val errorBody = response.body?.string().orEmpty()
                Timber.e(
                    "Seerr search failed http=%d message=%s body=%s",
                    response.code,
                    response.message,
                    errorBody.take(400),
                )
                val bodyMessage = Regex("\"error\"\\s*:\\s*\"([^\"]+)\"")
                    .find(errorBody)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                val userMessage =
                    when (response.code) {
                        401, 403 -> {
                            val detail = bodyMessage ?: response.message
                            "Jellyseerr rejected the configured API key (${
                                response.code
                            } ${response.message}). $detail"
                        }
                        else -> bodyMessage
                            ?: "Jellyseerr search failed (${response.code} ${response.message})."
                    }
                SeerrSearchOutcome(errorMessage = userMessage)
            }
        } catch (e: Exception) {
            Timber.e(e, "Seerr search error query=%s baseUrl=%s", query, baseUrl)
            SeerrSearchOutcome(
                errorMessage = e.message ?: "Jellyseerr search failed unexpectedly.",
            )
        }
    }

    suspend fun createRequest(
        mediaType: String,
        tmdbId: Int,
        is4k: Boolean = false,
        tvdbId: Int? = null,
        seasons: List<Int>? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (!appPreferences.getValue(appPreferences.seerrEnabled)) {
            Timber.d("Seerr request skipped: disabled in settings")
            return@withContext false
        }

        try {
            val apiKey = appPreferences.getValue(appPreferences.seerrApiKey)?.trim().orEmpty()
            if (apiKey.isBlank()) {
                Timber.w("Seerr request skipped: no API key configured")
                return@withContext false
            }
            val finalSeasons = if (mediaType == "tv") {
                seasons ?: getTvSeasons(tmdbId)
            } else null

            val createRequest = SeerrCreateRequest(
                mediaType = mediaType,
                mediaId = tmdbId,
                tvdbId = tvdbId,
                seasons = finalSeasons,
                is4k = is4k
            )
            val body = json.encodeToString(SeerrCreateRequest.serializer(), createRequest)
            val request =
                buildRequest(
                    pathSegments = listOf("request"),
                    method = "POST",
                    body = body,
                ) ?: return@withContext false
            
            Timber.i("Creating Seerr request for $mediaType $tmdbId (TVDB: $tvdbId, 4K: $is4k, Seasons: $finalSeasons)")
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Timber.i("Seerr request successful")
                true
            } else {
                Timber.e("Seerr request failed: ${response.code} ${response.message}")
                val errorBody = response.body?.string()
                if (!errorBody.isNullOrBlank()) {
                    Timber.e("Seerr error body: $errorBody")
                }
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "Seerr request error")
            false
        }
    }

    private suspend fun getTvSeasons(tmdbId: Int): List<Int> = withContext(Dispatchers.IO) {
        try {
            val request =
                buildRequest(
                    pathSegments = listOf("tv", tmdbId.toString()),
                ) ?: return@withContext emptyList()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext emptyList()
                val tvInfo = json.decodeFromString<SeerrTvResponse>(body)
                tvInfo.seasons.map { it.seasonNumber }.filter { it > 0 } // Exclude Season 0 (Specials) usually
            } else {
                Timber.e("Failed to get TV seasons for %d: http=%d message=%s", tmdbId, response.code, response.message)
                emptyList()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error getting TV seasons")
            emptyList()
        }
    }
}
