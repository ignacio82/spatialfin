package dev.jdtech.jellyfin.models

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MovieVersionGroupingTest {

    @Test
    fun `alternate versions of the same release collapse to one card`() {
        val flat = movie(name = "Dune", productionYear = 2021)
        val sideBySide = movie(name = "Dune 3D SBS", productionYear = 2021)

        val result = listOf(flat, sideBySide).deduplicateMovieVersions()

        assertEquals(1, result.size)
    }

    /**
     * Regression: home-video libraries carry no scraped year, so every clip hashed to
     * "title|unknown". Two same-titled clips (IMG_0001 in two folders, Birthday.mp4 next
     * to Birthday.mov) collapsed into one and the rest of the media silently vanished.
     */
    @Test
    fun `same-titled items without a year are kept apart`() {
        val first = movie(name = "Birthday", productionYear = null)
        val second = movie(name = "Birthday", productionYear = null)

        assertNull(first.movieVersionGroupKey())
        assertEquals(2, listOf(first, second).deduplicateMovieVersions().size)
    }

    @Test
    fun `a year parsed out of the file name still groups`() {
        val first = movie(name = "Holiday (2019)", productionYear = null)
        val second = movie(name = "Holiday (2019) 1080p", productionYear = null)

        assertEquals(1, listOf(first, second).deduplicateMovieVersions().size)
    }

    private fun movie(
        name: String,
        productionYear: Int?,
        runtimeTicks: Long = 0L,
    ): SpatialFinMovie =
        SpatialFinMovie(
            id = UUID.randomUUID(),
            name = name,
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            runtimeTicks = runtimeTicks,
            playbackPositionTicks = 0L,
            premiereDate = null,
            people = emptyList(),
            genres = emptyList(),
            communityRating = null,
            officialRating = null,
            status = "Ended",
            productionYear = productionYear,
            endDate = null,
            trailer = null,
            images = SpatialFinImages(),
            chapters = emptyList(),
            trickplayInfo = null,
        )
}
