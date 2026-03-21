package dev.jdtech.jellyfin.api

import dev.jdtech.jellyfin.settings.domain.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

class TmdbApi(
    private val appPreferences: AppPreferences,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    private var cachedImageBaseUrl: String? = null

    private fun apiKey(): String? = appPreferences.getValue(appPreferences.tmdbApiKey)

    fun isConfigured(): Boolean = !apiKey().isNullOrBlank()

    private fun buildRequest(path: String): Request? {
        val key = apiKey() ?: return null
        return Request.Builder()
            .url("$BASE_URL$path${if ('?' in path) '&' else '?'}api_key=$key")
            .build()
    }

    suspend fun searchMovies(
        query: String,
        year: Int? = null,
    ): List<TmdbMovieResult> = withContext(Dispatchers.IO) {
        val yearParam = year?.let { "&year=$it" } ?: ""
        val request = buildRequest("/search/movie?query=${encode(query)}$yearParam") ?: return@withContext emptyList()
        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext emptyList()
                json.decodeFromString<TmdbSearchResponse<TmdbMovieResult>>(body).results
            } else {
                Timber.e("TMDB movie search failed: ${response.code}")
                emptyList()
            }
        } catch (e: Exception) {
            Timber.e(e, "TMDB movie search error")
            emptyList()
        }
    }

    suspend fun searchTv(
        query: String,
        year: Int? = null,
    ): List<TmdbTvResult> = withContext(Dispatchers.IO) {
        val yearParam = year?.let { "&first_air_date_year=$it" } ?: ""
        val request = buildRequest("/search/tv?query=${encode(query)}$yearParam") ?: return@withContext emptyList()
        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext emptyList()
                json.decodeFromString<TmdbSearchResponse<TmdbTvResult>>(body).results
            } else {
                Timber.e("TMDB TV search failed: ${response.code}")
                emptyList()
            }
        } catch (e: Exception) {
            Timber.e(e, "TMDB TV search error")
            emptyList()
        }
    }

    suspend fun getMovieDetails(tmdbId: Int): TmdbMovieDetails? = withContext(Dispatchers.IO) {
        val request = buildRequest("/movie/$tmdbId?append_to_response=credits") ?: return@withContext null
        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext null
                json.decodeFromString<TmdbMovieDetails>(body)
            } else {
                Timber.e("TMDB movie details failed: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "TMDB movie details error")
            null
        }
    }

    suspend fun getTvDetails(tmdbId: Int): TmdbTvDetails? = withContext(Dispatchers.IO) {
        val request = buildRequest("/tv/$tmdbId?append_to_response=credits") ?: return@withContext null
        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext null
                json.decodeFromString<TmdbTvDetails>(body)
            } else {
                Timber.e("TMDB TV details failed: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "TMDB TV details error")
            null
        }
    }

    suspend fun getImageBaseUrl(): String {
        cachedImageBaseUrl?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val request = buildRequest("/configuration") ?: return@withContext DEFAULT_IMAGE_BASE
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext DEFAULT_IMAGE_BASE
                    val config = json.decodeFromString<TmdbConfiguration>(body)
                    val base = config.images.secureBaseUrl
                    cachedImageBaseUrl = base
                    base
                } else {
                    DEFAULT_IMAGE_BASE
                }
            } catch (e: Exception) {
                Timber.e(e, "TMDB configuration error")
                DEFAULT_IMAGE_BASE
            }
        }
    }

    fun posterUrl(posterPath: String?): String? {
        posterPath ?: return null
        val base = cachedImageBaseUrl ?: DEFAULT_IMAGE_BASE
        return "${base}w500$posterPath"
    }

    fun backdropUrl(backdropPath: String?): String? {
        backdropPath ?: return null
        val base = cachedImageBaseUrl ?: DEFAULT_IMAGE_BASE
        return "${base}w1280$backdropPath"
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    private companion object {
        private const val BASE_URL = "https://api.themoviedb.org/3"
        private const val DEFAULT_IMAGE_BASE = "https://image.tmdb.org/t/p/"
    }
}
