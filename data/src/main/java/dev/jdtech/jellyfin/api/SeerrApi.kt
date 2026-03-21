package dev.jdtech.jellyfin.api

import dev.jdtech.jellyfin.settings.domain.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

class SeerrApi(
    private val appPreferences: AppPreferences,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun buildRequest(path: String, method: String = "GET", body: String? = null): Request {
        val url = appPreferences.getValue(appPreferences.seerrUrl)?.trimEnd('/') ?: ""
        val apiKey = appPreferences.getValue(appPreferences.seerrApiKey) ?: ""
        
        val builder = Request.Builder()
            .url("$url/api/v1$path")
            .header("X-Api-Key", apiKey)

        if (method == "POST" && body != null) {
            builder.post(body.toRequestBody("application/json".toMediaType()))
        } else if (method == "POST") {
            builder.post("".toRequestBody())
        }
        
        return builder.build()
    }

    suspend fun search(query: String): SeerrSearchResponse? = withContext(Dispatchers.IO) {
        if (!appPreferences.getValue(appPreferences.seerrEnabled)) {
            Timber.d("Seerr search skipped: disabled in settings")
            return@withContext null
        }

        val baseUrl = appPreferences.getValue(appPreferences.seerrUrl)
        if (baseUrl.isNullOrBlank()) {
            Timber.w("Seerr search skipped: no server URL configured")
            return@withContext null
        }

        try {
            val request = buildRequest("/search?query=$query")
            Timber.v("Seerr search request: ${request.url}")
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext null
                json.decodeFromString<SeerrSearchResponse>(body)
            } else {
                Timber.e("Seerr search failed: ${response.code} ${response.message}")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Seerr search error")
            null
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
            val request = buildRequest("/request", "POST", body)
            
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
            val request = buildRequest("/tv/$tmdbId")
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext emptyList()
                val tvInfo = json.decodeFromString<SeerrTvResponse>(body)
                tvInfo.seasons.map { it.seasonNumber }.filter { it > 0 } // Exclude Season 0 (Specials) usually
            } else {
                Timber.e("Failed to get TV seasons for $tmdbId: ${response.code}")
                emptyList()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error getting TV seasons")
            emptyList()
        }
    }
}
