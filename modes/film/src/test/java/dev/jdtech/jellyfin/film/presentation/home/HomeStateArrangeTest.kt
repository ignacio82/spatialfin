package dev.jdtech.jellyfin.film.presentation.home

import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.HomeItem
import dev.jdtech.jellyfin.models.View
import dev.jdtech.jellyfin.settings.domain.HomeRowIds
import dev.jdtech.jellyfin.settings.domain.HomeRowLayout
import java.util.UUID
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the read-time filter the home screens depend on: a row the user hid
 * must not survive in [HomeState], whichever load path produced it — including
 * the process-wide home cache.
 */
class HomeStateArrangeTest {
    private val musicId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val moviesId = UUID.fromString("22222222-2222-2222-2222-222222222222")

    private fun viewItem(id: UUID, name: String) =
        HomeItem.ViewItem(
            View(id = id, name = name, items = emptyList(), type = CollectionType.Movies)
        )

    private val state =
        HomeState(views = persistentListOf(viewItem(musicId, "Music"), viewItem(moviesId, "Movies")))

    @Test
    fun `a latest row's id is derived from its library id`() {
        assertEquals("latest:$musicId", viewItem(musicId, "Music").rowId)
    }

    @Test
    fun `hiding one latest row drops only that row`() {
        val arranged = state.arrangedBy(HomeRowLayout(hidden = setOf(HomeRowIds.latest(musicId))))

        assertEquals(listOf("Movies"), arranged.views.map { it.view.name })
    }

    @Test
    fun `saved order reorders the latest rows`() {
        val layout =
            HomeRowLayout(order = listOf(HomeRowIds.latest(moviesId), HomeRowIds.latest(musicId)))

        assertEquals(listOf("Movies", "Music"), state.arrangedBy(layout).views.map { it.view.name })
    }

    @Test
    fun `the global latest switch drops every latest row`() {
        val arranged = state.arrangedBy(HomeRowLayout(hidden = setOf(HomeRowIds.ALL_LATEST)))

        assertEquals(emptyList<String>(), arranged.views.map { it.view.name })
    }

    @Test
    fun `a cached state carrying a disabled suggestions row is filtered on read`() {
        val cached =
            state.copy(
                suggestionsSection =
                    HomeItem.Suggestions(id = UUID.randomUUID(), items = emptyList())
            )

        assertNull(cached.arrangedBy(HomeRowLayout(hidden = setOf(HomeRowIds.SUGGESTIONS))).suggestionsSection)
    }
}
