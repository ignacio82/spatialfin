package dev.jdtech.jellyfin.settings.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRowLayoutTest {
    private data class Row(val id: String)

    private val naturalOrder =
        listOf(
            Row(HomeRowIds.SUGGESTIONS),
            Row(HomeRowIds.CONTINUE_WATCHING),
            Row(HomeRowIds.NEXT_UP),
            Row(HomeRowIds.latest("movies")),
        )

    @Test
    fun `default layout keeps the shipped order and shows every row`() {
        val arranged = HomeRowLayout().arrange(naturalOrder) { it.id }

        assertEquals(naturalOrder, arranged)
    }

    @Test
    fun `a hidden row never renders`() {
        val layout = HomeRowLayout(hidden = setOf(HomeRowIds.SUGGESTIONS))

        assertFalse(layout.isVisible(HomeRowIds.SUGGESTIONS))
        assertEquals(
            listOf(HomeRowIds.CONTINUE_WATCHING, HomeRowIds.NEXT_UP, HomeRowIds.latest("movies")),
            layout.arrange(naturalOrder) { it.id }.map { it.id },
        )
    }

    @Test
    fun `the global latest switch hides every per-library latest row`() {
        val layout = HomeRowLayout(hidden = setOf(HomeRowIds.ALL_LATEST))

        assertFalse(layout.isVisible(HomeRowIds.latest("movies")))
        assertFalse(layout.isVisible(HomeRowIds.latest("shows")))
        assertTrue(layout.isVisible(HomeRowIds.NEXT_UP))
    }

    @Test
    fun `saved order wins and unknown rows keep their natural order at the end`() {
        val layout =
            HomeRowLayout(order = listOf(HomeRowIds.NEXT_UP, HomeRowIds.CONTINUE_WATCHING))

        assertEquals(
            listOf(
                HomeRowIds.NEXT_UP,
                HomeRowIds.CONTINUE_WATCHING,
                HomeRowIds.SUGGESTIONS,
                HomeRowIds.latest("movies"),
            ),
            layout.arrange(naturalOrder) { it.id }.map { it.id },
        )
    }

    @Test
    fun `hidden rows the shell can name are offered back`() {
        val layout =
            HomeRowLayout(
                hidden =
                    setOf(
                        HomeRowIds.SUGGESTIONS,
                        HomeRowIds.musicAssistant("favorites"),
                        HomeRowIds.latest("movies"),
                        HomeRowIds.plugin("com.example", "featured"),
                    )
            )

        val restorable =
            layout.restorableHiddenRows(mapOf(HomeRowIds.latest("movies") to "Movies"))

        assertEquals(HomeRowIds.SUGGESTIONS to "Suggestions", restorable.first())
        assertEquals(
            setOf(
                HomeRowIds.SUGGESTIONS,
                HomeRowIds.musicAssistant("favorites"),
                HomeRowIds.latest("movies"),
                HomeRowIds.plugin("com.example", "featured"),
            ),
            restorable.map { it.first }.toSet(),
        )
        assertEquals("Movies", restorable.single { it.first == HomeRowIds.latest("movies") }.second)
    }
}
