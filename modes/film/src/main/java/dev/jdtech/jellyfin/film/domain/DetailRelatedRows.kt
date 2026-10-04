package dev.jdtech.jellyfin.film.domain

import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.models.deduplicateMovieVersions
import dev.jdtech.jellyfin.models.movieVersionGroupKey
import dev.jdtech.jellyfin.repository.JellyfinRepository
import kotlin.coroutines.cancellation.CancellationException
import timber.log.Timber

/**
 * The browse rows under a detail hero (Fladder-style): "More from this season"
 * on an episode, "More like this" on a movie or series. XR, Beam and TV all load
 * them through [loadDetailRelatedRows] so the three shells offer the same rows
 * for the same item.
 */
data class DetailRelatedRows(
    /**
     * The whole season the episode belongs to, in order, *including* the episode
     * itself — the row reads as "the season, with you-are-here marked" rather than
     * a list with a gap in it. Empty when the season has nothing else to offer.
     */
    val seasonEpisodes: List<SpatialFinEpisode> = emptyList(),
    /** Server-side "similar" items, minus the item itself and its alternate versions. */
    val similar: List<SpatialFinItem> = emptyList(),
)

const val DETAIL_SIMILAR_LIMIT = 16
private const val SEASON_EPISODE_LIMIT = 200

/**
 * Loads the related rows for [item]. Never throws (except cancellation): these
 * rows are garnish under a hero that has already rendered, so a failed request
 * must leave the row hidden, not fail the page.
 */
suspend fun JellyfinRepository.loadDetailRelatedRows(item: SpatialFinItem): DetailRelatedRows =
    when (item) {
        is SpatialFinEpisode -> {
            val episodes =
                orEmptyOnFailure("season episodes for ${item.id}") {
                    getEpisodes(
                        seriesId = item.seriesId,
                        seasonId = item.seasonId,
                        limit = SEASON_EPISODE_LIMIT,
                    )
                }
            DetailRelatedRows(seasonEpisodes = episodes.takeIf { it.size > 1 }.orEmpty())
        }
        is SpatialFinMovie,
        is SpatialFinShow -> {
            val similar =
                orEmptyOnFailure("similar items for ${item.id}") {
                    getSimilarItems(item.id, limit = DETAIL_SIMILAR_LIMIT)
                }
            DetailRelatedRows(similar = similar.relatedTo(item))
        }
        else -> DetailRelatedRows()
    }

/**
 * Jellyfin's similarity ranking happily returns the 4K copy of the movie you are
 * looking at, and two copies of the same other movie. Neither is "more like this".
 */
internal fun List<SpatialFinItem>.relatedTo(item: SpatialFinItem): List<SpatialFinItem> {
    val ownVersionKey = item.movieVersionGroupKey()
    return filter { candidate ->
            candidate.id != item.id &&
                (ownVersionKey == null || candidate.movieVersionGroupKey() != ownVersionKey)
        }
        .deduplicateMovieVersions()
}

/** Index the season row should open on, so the current episode is in view. */
fun List<SpatialFinEpisode>.initialScrollIndexFor(currentEpisodeId: java.util.UUID): Int =
    (indexOfFirst { it.id == currentEpisodeId } - 1).coerceAtLeast(0)

private suspend fun <T> orEmptyOnFailure(what: String, block: suspend () -> List<T>): List<T> =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Couldn't load %s", what)
        emptyList()
    }
