package dev.spatialfin.companion.wear.presentation

import dev.spatialfin.companion.protocol.WearChapterInfo
import dev.spatialfin.companion.wear.presentation.components.activeIndexAt
import dev.spatialfin.companion.wear.presentation.components.splitStreamName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The redesign's two-line rows and current-chapter marker are pure string and index
 * work, derived rather than pushed over the wire — so they are worth pinning down
 * here rather than discovering on a watch.
 */
class WearSheetFormattingTest {

    @Test
    fun `stream name splits on Jellyfin's separator`() {
        val (label, format) = "English - EAC3 - 5.1".splitStreamName()
        assertEquals("English", label)
        assertEquals("EAC3 · 5.1", format)
    }

    @Test
    fun `stream name without a separator keeps the whole string`() {
        val (label, format) = "Commentary".splitStreamName()
        assertEquals("Commentary", label)
        assertNull(format)
    }

    @Test
    fun `stream name with an empty tail is not split`() {
        val (label, format) = "English - ".splitStreamName()
        assertEquals("English - ", label)
        assertNull(format)
    }

    @Test
    fun `active chapter is the last one at or behind the playhead`() {
        val chapters = listOf(
            WearChapterInfo("Opening", 0),
            WearChapterInfo("The Forest", 108),
            WearChapterInfo("Campfire", 276),
        )
        assertEquals(0, chapters.activeIndexAt(positionSeconds = 12, currentChapterName = null))
        assertEquals(1, chapters.activeIndexAt(positionSeconds = 108, currentChapterName = null))
        assertEquals(2, chapters.activeIndexAt(positionSeconds = 999, currentChapterName = null))
    }

    @Test
    fun `host's own current chapter wins over the positional guess`() {
        val chapters = listOf(
            WearChapterInfo("Opening", 0),
            WearChapterInfo("The Forest", 108),
        )
        // The host knows about segments the start-time arithmetic cannot see.
        assertEquals(
            1,
            chapters.activeIndexAt(positionSeconds = 4, currentChapterName = "The Forest"),
        )
    }

    @Test
    fun `an unknown current chapter name falls back to position`() {
        val chapters = listOf(WearChapterInfo("Opening", 0), WearChapterInfo("Forest", 108))
        assertEquals(1, chapters.activeIndexAt(positionSeconds = 200, currentChapterName = "Gone"))
    }

    @Test
    fun `empty chapter list has no active index`() {
        assertEquals(-1, emptyList<WearChapterInfo>().activeIndexAt(10, null))
    }

    @Test
    fun `remaining time rounds up to whole minutes`() {
        assertEquals("14 min left", formatRemaining(14 * 60L))
        // 13:01 is "14 min left", not "13" — rounding down reads as already-finished.
        assertEquals("14 min left", formatRemaining(13 * 60L + 1))
        assertEquals("1 min left", formatRemaining(1))
    }

    @Test
    fun `remaining time crosses into hours`() {
        assertEquals("1 h 12 min left", formatRemaining(72 * 60L))
        assertEquals("2 h 0 min left", formatRemaining(120 * 60L))
    }
}
