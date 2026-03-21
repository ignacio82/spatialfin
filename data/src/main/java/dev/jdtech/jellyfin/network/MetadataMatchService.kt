package dev.jdtech.jellyfin.network

import dev.jdtech.jellyfin.api.TmdbApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.NetworkVideoDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber

class MetadataMatchService(
    private val tmdbApi: TmdbApi,
    private val database: ServerDatabaseDao,
) {
    /**
     * Enrich metadata for all videos in a share using TMDB.
     * Parses filenames for title/year/season/episode, searches TMDB,
     * and updates the database with the best match.
     */
    suspend fun enrichShare(shareId: String) = withContext(Dispatchers.IO) {
        if (!tmdbApi.isConfigured()) {
            Timber.d("TMDB not configured, skipping metadata enrichment")
            return@withContext
        }

        // Pre-fetch the image base URL
        tmdbApi.getImageBaseUrl()

        val videos = database.getNetworkVideosByShare(shareId)
        for (video in videos) {
            if (video.tmdbId != null) continue // already matched
            try {
                enrichVideo(video)
                delay(RATE_LIMIT_DELAY_MS) // respect TMDB rate limits
            } catch (e: Exception) {
                Timber.e(e, "Failed to enrich metadata for ${video.fileName}")
            }
        }
    }

    suspend fun enrichVideo(video: NetworkVideoDto) = withContext(Dispatchers.IO) {
        val parsed = parseMetadata(video.fileName)

        if (parsed.seasonNumber != null && parsed.episodeNumber != null) {
            // TV content — search by series name
            matchTvShow(video, parsed)
        } else {
            // Movie content
            matchMovie(video, parsed)
        }
    }

    private suspend fun matchMovie(video: NetworkVideoDto, parsed: ParsedMetadata) {
        val results = tmdbApi.searchMovies(parsed.displayTitle, parsed.productionYear)
        val best = results.firstOrNull() ?: return

        val updated = video.copy(
            tmdbId = best.id,
            tmdbType = "movie",
            title = best.title.ifBlank { video.title },
            overview = best.overview.takeIf { it.isNotBlank() },
            posterUrl = tmdbApi.posterUrl(best.posterPath),
            backdropUrl = tmdbApi.backdropUrl(best.backdropPath),
            voteAverage = best.voteAverage.takeIf { it > 0 },
            releaseYear = best.releaseDate?.take(4)?.toIntOrNull() ?: parsed.productionYear,
            metadataFetchedAtEpochMs = System.currentTimeMillis(),
        )
        database.insertNetworkVideo(updated)
    }

    private suspend fun matchTvShow(video: NetworkVideoDto, parsed: ParsedMetadata) {
        val results = tmdbApi.searchTv(parsed.displayTitle, parsed.productionYear)
        val best = results.firstOrNull() ?: return

        val updated = video.copy(
            tmdbId = best.id,
            tmdbType = "tv",
            title = best.name.ifBlank { video.title },
            overview = best.overview.takeIf { it.isNotBlank() },
            posterUrl = tmdbApi.posterUrl(best.posterPath),
            backdropUrl = tmdbApi.backdropUrl(best.backdropPath),
            voteAverage = best.voteAverage.takeIf { it > 0 },
            releaseYear = best.firstAirDate?.take(4)?.toIntOrNull() ?: parsed.productionYear,
            seasonNumber = parsed.seasonNumber,
            episodeNumber = parsed.episodeNumber,
            seriesGroupKey = "tmdb-tv-${best.id}",
            metadataFetchedAtEpochMs = System.currentTimeMillis(),
        )
        database.insertNetworkVideo(updated)
    }

    data class ParsedMetadata(
        val displayTitle: String,
        val productionYear: Int?,
        val seasonNumber: Int?,
        val episodeNumber: Int?,
    )

    companion object {
        private const val RATE_LIMIT_DELAY_MS = 100L

        /**
         * Parse metadata from a filename. Reuses the same regex logic as
         * LocalMediaRepositoryImpl.parseMetadata.
         */
        fun parseMetadata(fileName: String): ParsedMetadata {
            val withoutExtension = fileName.substringBeforeLast('.')
            val normalized = withoutExtension.replace(Regex("[._]+"), " ").trim()
            val seasonEpisode = Regex("(?i)\\bS(\\d{1,2})E(\\d{1,2})\\b").find(normalized)
            val year = Regex("\\b(19\\d{2}|20\\d{2}|21\\d{2})\\b").find(normalized)?.value?.toIntOrNull()
            val cleaned = normalized
                .replace(Regex("(?i)\\bS\\d{1,2}E\\d{1,2}\\b"), "")
                .replace(Regex("\\b(19\\d{2}|20\\d{2}|21\\d{2})\\b"), "")
                .replace(Regex("\\s{2,}"), " ")
                .trim()
                .ifBlank { withoutExtension }

            return ParsedMetadata(
                displayTitle = cleaned,
                productionYear = year,
                seasonNumber = seasonEpisode?.groupValues?.getOrNull(1)?.toIntOrNull(),
                episodeNumber = seasonEpisode?.groupValues?.getOrNull(2)?.toIntOrNull(),
            )
        }
    }
}
