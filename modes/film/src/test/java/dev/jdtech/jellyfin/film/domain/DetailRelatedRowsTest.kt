package dev.jdtech.jellyfin.film.domain

import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.core.presentation.dummy.dummyShow
import dev.jdtech.jellyfin.repository.JellyfinRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins which rows each detail page gets, so XR, Beam and TV cannot drift: an
 * episode gets its season, a movie or series gets "more like this", and a failed
 * request hides the row instead of failing the page.
 */
class DetailRelatedRowsTest {
    private val repository = mockk<JellyfinRepository>()

    private fun episode(index: Int) =
        dummyEpisode.copy(id = UUID.randomUUID(), indexNumber = index, name = "Episode $index")

    private fun movie(name: String, year: Int) =
        dummyMovie.copy(id = UUID.randomUUID(), name = name, originalTitle = null, productionYear = year)

    @Test
    fun `episode gets its whole season including itself and never asks for similar`() = runTest {
        val current = episode(2)
        val season = listOf(episode(1), current, episode(3))
        coEvery { repository.getEpisodes(current.seriesId, current.seasonId, limit = any()) } returns season

        val rows = repository.loadDetailRelatedRows(current)

        assertEquals(season, rows.seasonEpisodes)
        assertTrue(rows.similar.isEmpty())
        coVerify(exactly = 0) { repository.getSimilarItems(any(), any()) }
    }

    @Test
    fun `a season holding only the current episode has nothing to offer`() = runTest {
        val current = episode(1)
        coEvery { repository.getEpisodes(any(), any(), limit = any()) } returns listOf(current)

        assertTrue(repository.loadDetailRelatedRows(current).seasonEpisodes.isEmpty())
    }

    @Test
    fun `movie similar drops the movie itself and its alternate versions`() = runTest {
        val current = movie("Alita: Battle Angel", 2019)
        val currentIn4k = movie("Alita: Battle Angel", 2019)
        val other = movie("Ghost in the Shell", 1995)
        val otherSecondCopy = movie("Ghost in the Shell", 1995)
        val another = movie("Akira", 1988)
        coEvery { repository.getSimilarItems(current.id, any()) } returns
            listOf(current, currentIn4k, other, otherSecondCopy, another)

        val rows = repository.loadDetailRelatedRows(current)

        assertEquals(listOf(other.id, another.id), rows.similar.map { it.id })
        assertTrue(rows.seasonEpisodes.isEmpty())
    }

    @Test
    fun `series gets similar series`() = runTest {
        val show = dummyShow.copy(id = UUID.randomUUID())
        val related = dummyShow.copy(id = UUID.randomUUID(), name = "Vinland Saga")
        coEvery { repository.getSimilarItems(show.id, any()) } returns listOf(show, related)

        assertEquals(listOf(related), repository.loadDetailRelatedRows(show).similar)
    }

    @Test
    fun `a failed request hides the row instead of failing the page`() = runTest {
        val current = movie("Alita: Battle Angel", 2019)
        coEvery { repository.getSimilarItems(any(), any()) } throws IOException("offline")
        coEvery { repository.getEpisodes(any(), any(), limit = any()) } throws IOException("offline")

        assertEquals(DetailRelatedRows(), repository.loadDetailRelatedRows(current))
        assertEquals(DetailRelatedRows(), repository.loadDetailRelatedRows(episode(1)))
    }

    @Test
    fun `season row opens one episode before the current one`() {
        val season = (1..6).map(::episode)

        assertEquals(0, season.initialScrollIndexFor(season[0].id))
        assertEquals(3, season.initialScrollIndexFor(season[4].id))
        assertEquals(0, season.initialScrollIndexFor(UUID.randomUUID()))
    }
}
