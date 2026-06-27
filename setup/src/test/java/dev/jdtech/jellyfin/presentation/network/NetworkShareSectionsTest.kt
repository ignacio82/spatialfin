package dev.jdtech.jellyfin.presentation.network

import dev.jdtech.jellyfin.models.NetworkVideoItem
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.SpatialFinSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkShareSectionsTest {

    @Test
    fun `uncategorized items are not also classified as movies`() {
        val uncategorized = video(
            networkVideoId = "uncategorized-1",
            tmdbType = null,
            seriesGroupKey = null,
        )

        val sections = categorizeNetworkVideos(listOf(uncategorized))

        assertTrue(sections.movies.isEmpty())
        assertEquals(listOf(uncategorized), sections.uncategorized)
    }

    @Test
    fun `deduplicates repeated network video ids`() {
        val movie = video(
            networkVideoId = "movie-1",
            tmdbType = "movie",
            seriesGroupKey = null,
        )

        val sections = categorizeNetworkVideos(listOf(movie, movie))

        assertEquals(listOf(movie), sections.movies)
        assertTrue(sections.uncategorized.isEmpty())
    }

    @Test
    fun `groups tv episodes separately`() {
        val episode = video(
            networkVideoId = "episode-1",
            tmdbType = null,
            seriesGroupKey = "series:show",
        )

        val sections = categorizeNetworkVideos(listOf(episode))

        assertEquals(listOf(episode), sections.tvShows["series:show"])
        assertTrue(sections.movies.isEmpty())
        assertTrue(sections.uncategorized.isEmpty())
    }

    private fun video(
        networkVideoId: String,
        tmdbType: String?,
        seriesGroupKey: String?,
    ) = NetworkVideoItem(
        networkVideoId = networkVideoId,
        shareId = "share-1",
        filePath = "$networkVideoId.mkv",
        fileName = "$networkVideoId.mkv",
        sizeBytes = 1L,
        tmdbId = null,
        tmdbType = tmdbType,
        seriesGroupKey = seriesGroupKey,
        seasonNumber = null,
        episodeNumber = null,
        releaseYear = null,
        name = networkVideoId,
        sources = listOf(
            SpatialFinSource(
                id = networkVideoId,
                name = networkVideoId,
                type = SpatialFinSourceType.NETWORK,
                path = "$networkVideoId.mkv",
                size = 1L,
                mediaStreams = emptyList(),
            )
        ),
        runtimeTicks = 0L,
        playbackPositionTicks = 0L,
    )
}
