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
        if (!appPreferences.getValue(appPreferences.seerrEnabled)) return@withContext null

        try {
            val request = buildRequest("/search?query=$query")
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext null
                json.decodeFromString<SeerrSearchResponse>(body)
            } else {
                Timber.e("Seerr search failed: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Seerr search error")
            null
        }
    }

    suspend fun createRequest(mediaType: String, mediaId: Int): Boolean = withContext(Dispatchers.IO) {
        if (!appPreferences.getValue(appPreferences.seerrEnabled)) return@withContext false

        try {
            val createRequest = SeerrCreateRequest(mediaType = mediaType, mediaId = mediaId)
            val body = json.encodeToString(SeerrCreateRequest.serializer(), createRequest)
            val request = buildRequest("/request", "POST", body)
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                true
            } else {
                Timber.e("Seerr request failed: ${response.code}")
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "Seerr request error")
            false
        }
    }
}
