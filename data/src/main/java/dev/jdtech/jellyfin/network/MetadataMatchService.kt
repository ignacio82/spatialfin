package dev.jdtech.jellyfin.network

import dev.jdtech.jellyfin.api.OmdbApi
import dev.jdtech.jellyfin.api.TmdbApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.NetworkVideoDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber

class MetadataMatchService(
    private val tmdbApi: TmdbApi,
    private val omdbApi: OmdbApi,
    private val database: ServerDatabaseDao,
) {
    /**
     * Enrich metadata for all videos in a share using TMDB and/or OMDb.
     * Parses filenames for title/year/season/episode, searches both services,
     * and updates the database with the best combined result.
     */
    suspend fun enrichShare(shareId: String) = withContext(Dispatchers.IO) {
        if (!tmdbApi.isConfigured() && !omdbApi.isConfigured()) {
            Timber.d("Neither TMDB nor OMDb configured, skipping metadata enrichment")
            return@withContext
        }

        if (tmdbApi.isConfigured()) {
            tmdbApi.getImageBaseUrl()
        }

        val videos = database.getNetworkVideosByShare(shareId)
        for (video in videos) {
            val alreadyEnriched = video.genres != null &&
                (!omdbApi.isConfigured() || video.imdbId != null)
            if (alreadyEnriched) continue
            try {
                enrichVideo(video)
                delay(RATE_LIMIT_DELAY_MS)
            } catch (e: Exception) {
                Timber.e(e, "Failed to enrich metadata for ${video.fileName}")
            }
        }
    }

    suspend fun enrichVideo(video: NetworkVideoDto) = withContext(Dispatchers.IO) {
        val parsed = parseMetadata(video.fileName)

        // Skip if the cleaned title is too short to produce meaningful search results
        if (parsed.displayTitle.length < 3) {
            Timber.d("Skipping metadata enrichment for '${video.fileName}': cleaned title '${parsed.displayTitle}' too short")
            return@withContext
        }

        if (parsed.seasonNumber != null && parsed.episodeNumber != null) {
            matchTvShow(video, parsed)
        } else {
            matchMovie(video, parsed)
        }
    }

    private suspend fun matchMovie(video: NetworkVideoDto, parsed: ParsedMetadata) {
        // --- TMDB ---
        var tmdbId = video.tmdbId
        if (tmdbApi.isConfigured() && tmdbId == null) {
            var results = tmdbApi.searchMovies(parsed.displayTitle, parsed.productionYear)
            // Retry without year if no results — year in filename may differ from release year
            if (results.isEmpty() && parsed.productionYear != null) {
                delay(RATE_LIMIT_DELAY_MS)
                results = tmdbApi.searchMovies(parsed.displayTitle, null)
            }
            tmdbId = results.firstOrNull()?.id
        }
        val details = if (tmdbApi.isConfigured() && tmdbId != null) {
            delay(RATE_LIMIT_DELAY_MS)
            tmdbApi.getMovieDetails(tmdbId)
        } else null

        // --- OMDb ---
        val omdb = if (omdbApi.isConfigured()) {
            // Prefer the TMDB-resolved title for a more precise OMDb match
            val searchTitle = details?.title?.ifBlank { null } ?: parsed.displayTitle
            omdbApi.searchMovie(searchTitle, parsed.productionYear)
        } else null

        if (tmdbId == null && omdb == null) return // nothing found

        val tmdbGenres = details?.genres?.joinToString(",") { it.name }?.takeIf { it.isNotBlank() }
        val omdbGenres = omdb?.genre?.takeIf { it.isNotBlank() && it != "N/A" }

        val updated = video.copy(
            tmdbId = tmdbId,
            tmdbType = "movie",
            title = details?.title?.ifBlank { null }
                ?: omdb?.title?.ifBlank { null }
                ?: video.title,
            overview = details?.overview?.takeIf { it.isNotBlank() }
                ?: omdb?.plot?.takeIf { it.isNotBlank() && it != "N/A" },
            posterUrl = tmdbApi.posterUrl(details?.posterPath)
                ?: omdb?.poster?.takeIf { it.isNotBlank() && it != "N/A" },
            backdropUrl = tmdbApi.backdropUrl(details?.backdropPath),
            voteAverage = details?.voteAverage?.takeIf { it > 0 },
            releaseYear = details?.releaseDate?.take(4)?.toIntOrNull()
                ?: omdb?.year?.take(4)?.toIntOrNull()
                ?: parsed.productionYear,
            durationMs = details?.runtime?.let { it * 60_000L } ?: video.durationMs,
            genres = tmdbGenres ?: omdbGenres,
            director = details?.credits?.crew?.firstOrNull { it.job == "Director" }?.name
                ?: omdb?.director?.takeIf { it.isNotBlank() && it != "N/A" },
            writers = details?.credits?.crew
                ?.filter { it.department == "Writing" }
                ?.map { it.name }
                ?.distinct()
                ?.joinToString(",")
                ?.takeIf { it.isNotBlank() }
                ?: omdb?.writer?.takeIf { it.isNotBlank() && it != "N/A" },
            imdbId = omdb?.imdbId?.takeIf { it.isNotBlank() && it != "N/A" },
            imdbRating = omdb?.imdbRating?.takeIf { it.isNotBlank() && it != "N/A" },
            metadataFetchedAtEpochMs = System.currentTimeMillis(),
        )
        database.insertNetworkVideo(updated)
    }

    private suspend fun matchTvShow(video: NetworkVideoDto, parsed: ParsedMetadata) {
        // --- TMDB ---
        var tmdbId = video.tmdbId
        if (tmdbApi.isConfigured() && tmdbId == null) {
            var results = tmdbApi.searchTv(parsed.displayTitle, parsed.productionYear)
            // Retry without year if no results
            if (results.isEmpty() && parsed.productionYear != null) {
                delay(RATE_LIMIT_DELAY_MS)
                results = tmdbApi.searchTv(parsed.displayTitle, null)
            }
            tmdbId = results.firstOrNull()?.id
        }
        val details = if (tmdbApi.isConfigured() && tmdbId != null) {
            delay(RATE_LIMIT_DELAY_MS)
            tmdbApi.getTvDetails(tmdbId)
        } else null

        // --- OMDb ---
        val omdb = if (omdbApi.isConfigured()) {
            val searchTitle = details?.name?.ifBlank { null } ?: parsed.displayTitle
            omdbApi.searchSeries(searchTitle, parsed.productionYear)
        } else null

        if (tmdbId == null && omdb == null) return

        val tmdbGenres = details?.genres?.joinToString(",") { it.name }?.takeIf { it.isNotBlank() }
        val omdbGenres = omdb?.genre?.takeIf { it.isNotBlank() && it != "N/A" }

        val updated = video.copy(
            tmdbId = tmdbId,
            tmdbType = "tv",
            title = details?.name?.ifBlank { null }
                ?: omdb?.title?.ifBlank { null }
                ?: video.title,
            overview = details?.overview?.takeIf { it.isNotBlank() }
                ?: omdb?.plot?.takeIf { it.isNotBlank() && it != "N/A" },
            posterUrl = tmdbApi.posterUrl(details?.posterPath)
                ?: omdb?.poster?.takeIf { it.isNotBlank() && it != "N/A" },
            backdropUrl = tmdbApi.backdropUrl(details?.backdropPath),
            voteAverage = details?.voteAverage?.takeIf { it > 0 },
            releaseYear = details?.firstAirDate?.take(4)?.toIntOrNull()
                ?: omdb?.year?.take(4)?.toIntOrNull()
                ?: parsed.productionYear,
            seasonNumber = parsed.seasonNumber,
            episodeNumber = parsed.episodeNumber,
            seriesGroupKey = if (tmdbId != null) "tmdb-tv-$tmdbId"
                else "series:${parsed.displayTitle.lowercase().trim()}",
            genres = tmdbGenres ?: omdbGenres,
            director = details?.credits?.crew?.firstOrNull { it.job == "Director" }?.name
                ?: omdb?.director?.takeIf { it.isNotBlank() && it != "N/A" },
            writers = details?.credits?.crew
                ?.filter { it.department == "Writing" }
                ?.map { it.name }
                ?.distinct()
                ?.joinToString(",")
                ?.takeIf { it.isNotBlank() }
                ?: omdb?.writer?.takeIf { it.isNotBlank() && it != "N/A" },
            imdbId = omdb?.imdbId?.takeIf { it.isNotBlank() && it != "N/A" },
            imdbRating = omdb?.imdbRating?.takeIf { it.isNotBlank() && it != "N/A" },
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
         * Common quality/source/codec tags that appear in filenames but are not part of the title.
         * Stripped before searching TMDB/OMDb.
         */
        private val QUALITY_TAG_REGEX = Regex(
            """(?i)\b(""" +
                """480p|720p|1080p|2160p|4k|uhd|""" +           // resolution
                """bluray|bdrip|brrip|dvdrip|hdrip|webrip|web[-.]dl|webdl|hdtv|""" + // source
                """amzn|nf|dsnp|hmax|atvp|pcok|""" +            // streaming providers
                """x264|x265|h264|h265|hevc|avc|xvid|divx|av1|mpeg2|mpeg4|mpeg|""" + // video codec
                """aac|ac3|dts|ddp|truehd|atmos|mp3|""" +       // audio codec
                """hdr|hdr10|dv|dovi|sdr|""" +                  // hdr
                """extended|theatrical|remastered|proper|repack|unrated|directors.cut|nondegad""" + // editions
                """)\b""",
        )

        /**
         * Parse metadata from a filename, stripping quality/codec tags so that
         * "Big.Buck.Bunny.2008.1080p.BluRay.x264.mkv" yields title "Big Buck Bunny", year 2008.
         */
        fun parseMetadata(fileName: String): ParsedMetadata {
            val withoutExtension = fileName.substringBeforeLast('.')
            // Preserve apostrophes in contractions/possessives (e.g. "Elephant's" → "Elephants")
            // before dot/underscore normalization so they survive separator stripping
            val apostropheNormalized = withoutExtension
                .replace(Regex("'s\\b"), "s")   // possessive: Elephant's → Elephants
                .replace(Regex("'"), "")         // other apostrophes: won't → wont
            // Normalise separators
            val normalized = apostropheNormalized.replace(Regex("[._]+"), " ").trim()
            // Extract S##E## before stripping anything
            val seasonEpisode = Regex("(?i)\\bS(\\d{1,2})E(\\d{1,2})\\b").find(normalized)
            // Extract year
            val year = Regex("\\b(19\\d{2}|20\\d{2}|21\\d{2})\\b").find(normalized)?.value?.toIntOrNull()
            // Strip season/episode, year, and quality tags
            val cleaned = normalized
                .replace(Regex("(?i)\\bS\\d{1,2}E\\d{1,2}\\b.*"), "") // drop everything from S##E## onward
                .replace(Regex("\\b(19\\d{2}|20\\d{2}|21\\d{2})\\b"), "")
                .replace(QUALITY_TAG_REGEX, "")
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
