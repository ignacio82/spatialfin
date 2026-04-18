package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.SpatialFinChapter
import dev.jdtech.jellyfin.models.SpatialFinImages
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.SpatialFinSourceType
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeFilterTest {

    @Test
    fun `keeps items with played=false`() {
        val a = movie(name = "A", played = false)
        val b = movie(name = "B", played = false)

        val result = ResumeFilter.keepResumable(listOf(a, b))

        assertEquals(listOf(a, b), result)
    }

    @Test
    fun `drops items marked as played`() {
        val watched = movie(name = "Watched", played = true)

        val result = ResumeFilter.keepResumable(listOf(watched))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `drops only the played items in a mixed list`() {
        val keep1 = movie(name = "Keep 1", played = false)
        val drop1 = movie(name = "Drop 1", played = true)
        val keep2 = movie(name = "Keep 2", played = false)
        val drop2 = movie(name = "Drop 2", played = true)

        val result = ResumeFilter.keepResumable(listOf(keep1, drop1, keep2, drop2))

        assertEquals(listOf(keep1, keep2), result)
    }

    @Test
    fun `preserves order of kept items`() {
        val items = (1..5).map { movie(name = "Item $it", played = false) }

        val result = ResumeFilter.keepResumable(items)

        assertEquals(items, result)
    }

    @Test
    fun `empty input yields empty output`() {
        val result = ResumeFilter.keepResumable(emptyList<SpatialFinItem>())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `partial-progress watched item is still dropped`() {
        // Regression target: Jellyfin's resume endpoint occasionally returns
        // items that have a position > 0 AND played == true. The filter must
        // drop them — playback position alone is not enough to keep them in
        // Continue Watching.
        val partialWatched = movie(
            name = "Half-played but marked watched",
            played = true,
            playbackPositionTicks = 30L * 60L * 10_000_000L, // 30 minutes in
        )

        val result = ResumeFilter.keepResumable(listOf(partialWatched))

        assertTrue(result.isEmpty())
    }

    private fun movie(
        name: String,
        played: Boolean,
        playbackPositionTicks: Long = 0L,
    ): SpatialFinMovie = SpatialFinMovie(
        id = UUID.randomUUID(),
        name = name,
        originalTitle = null,
        overview = "$name overview",
        sources = listOf(remoteSource()),
        played = played,
        favorite = false,
        canPlay = true,
        canDownload = false,
        runtimeTicks = 90L * 60L * 10_000_000L,
        playbackPositionTicks = playbackPositionTicks,
        premiereDate = null,
        people = emptyList(),
        genres = emptyList(),
        communityRating = null,
        officialRating = null,
        status = "Released",
        productionYear = null,
        endDate = null,
        trailer = null,
        images = SpatialFinImages(),
        chapters = listOf(SpatialFinChapter(startPosition = 0L, name = "Start")),
        ratings = emptyList(),
        trickplayInfo = null,
    )

    private fun remoteSource(): SpatialFinSource = SpatialFinSource(
        id = UUID.randomUUID().toString(),
        name = "remote",
        type = SpatialFinSourceType.REMOTE,
        path = "remote",
        size = 0L,
        mediaStreams = emptyList(),
    )
}
