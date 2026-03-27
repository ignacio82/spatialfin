package dev.jdtech.jellyfin.api

import dev.jdtech.jellyfin.settings.domain.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

class OmdbApi(
    private val appPreferences: AppPreferences,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun isConfigured(): Boolean = !apiKey().isNullOrBlank()

    private fun apiKey(): String? = appPreferences.getValue(appPreferences.omdbApiKey)

    suspend fun searchMovie(title: String, year: Int? = null): OmdbResult? =
        search(title, year, type = "movie")

    suspend fun searchSeries(title: String, year: Int? = null): OmdbResult? =
        search(title, year, type = "series")

    private suspend fun search(title: String, year: Int?, type: String): OmdbResult? =
        withContext(Dispatchers.IO) {
            val key = apiKey() ?: return@withContext null
            val yearParam = year?.let { "&y=$it" } ?: ""
            val url = "$BASE_URL?t=${encode(title)}&type=$type$yearParam&apikey=$key"
            val request = Request.Builder().url(url).build()
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext null
                    val result = json.decodeFromString<OmdbResult>(body)
                    if (result.response == "True") result else null
                } else {
                    Timber.e("OMDb search failed: ${response.code}")
                    null
                }
            } catch (e: Exception) {
                Timber.e(e, "OMDb search error")
                null
            }
        }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val BASE_URL = "https://www.omdbapi.com/"
    }
}

@Serializable
data class OmdbResult(
    @SerialName("Title") val title: String = "",
    @SerialName("Year") val year: String = "",
    @SerialName("Director") val director: String = "",
    @SerialName("Writer") val writer: String = "",
    @SerialName("Genre") val genre: String = "",
    @SerialName("Plot") val plot: String = "",
    @SerialName("Poster") val poster: String = "",
    @SerialName("imdbRating") val imdbRating: String = "",
    @SerialName("imdbID") val imdbId: String = "",
    @SerialName("Response") val response: String = "",
)
