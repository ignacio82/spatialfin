package dev.jdtech.jellyfin.player.core

import dev.jdtech.jellyfin.models.SpatialFinSegmentType
import dev.jdtech.jellyfin.player.core.domain.models.ChapterSegmentDetector
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterSegmentDetectorTest {

    @Test
    fun `detects standard TV intro and outro chapters`() {
        val totalDurationMs = 1_440_000L // 24 minutes
        val chapters = listOf(
            PlayerChapter(startPosition = 0L, name = "Cold Open"),
            PlayerChapter(startPosition = 90_000L, name = "Intro"),
            PlayerChapter(startPosition = 180_000L, name = "Act 1"),
            PlayerChapter(startPosition = 720_000L, name = "Act 2"),
            PlayerChapter(startPosition = 1_350_000L, name = "Credits"),
        )

        val segments = ChapterSegmentDetector.detectSegments(chapters, totalDurationMs)
        assertEquals(2, segments.size)

        val intro = segments.first { it.type == SpatialFinSegmentType.INTRO }
        assertEquals(90_000L, intro.startTicks)
        assertEquals(180_000L, intro.endTicks)

        val outro = segments.first { it.type == SpatialFinSegmentType.OUTRO }
        assertEquals(1_350_000L, outro.startTicks)
        assertEquals(totalDurationMs, outro.endTicks)
    }

    @Test
    fun `detects anime OP ED recap and preview chapters with prefixes`() {
        val totalDurationMs = 1_440_000L // 24 minutes
        val chapters = listOf(
            PlayerChapter(startPosition = 0L, name = "Chapter 01 - Recap"),
            PlayerChapter(startPosition = 30_000L, name = "02. OP (Creditless)"),
            PlayerChapter(startPosition = 120_000L, name = "Part A"),
            PlayerChapter(startPosition = 700_000L, name = "Part B"),
            PlayerChapter(startPosition = 1_350_000L, name = "Chapter 5: ED"),
            PlayerChapter(startPosition = 1_410_000L, name = "Preview"),
        )

        val segments = ChapterSegmentDetector.detectSegments(chapters, totalDurationMs)
        assertEquals(4, segments.size)

        val recap = segments.first { it.type == SpatialFinSegmentType.RECAP }
        assertEquals(0L, recap.startTicks)
        assertEquals(30_000L, recap.endTicks)

        val intro = segments.first { it.type == SpatialFinSegmentType.INTRO }
        assertEquals(30_000L, intro.startTicks)
        assertEquals(120_000L, intro.endTicks)

        val outro = segments.first { it.type == SpatialFinSegmentType.OUTRO }
        assertEquals(1_350_000L, outro.startTicks)
        assertEquals(1_410_000L, outro.endTicks)

        val preview = segments.first { it.type == SpatialFinSegmentType.PREVIEW }
        assertEquals(1_410_000L, preview.startTicks)
        assertEquals(totalDurationMs, preview.endTicks)
    }

    @Test
    fun `rejects intro chapter starting too late in media`() {
        val totalDurationMs = 7_200_000L // 2 hours (movie)
        val chapters = listOf(
            PlayerChapter(startPosition = 0L, name = "Prologue"),
            // Starting at 1 hour in is not an intro theme
            PlayerChapter(startPosition = 3_600_000L, name = "Introduction"),
            PlayerChapter(startPosition = 3_700_000L, name = "Act 2"),
        )

        val segments = ChapterSegmentDetector.detectSegments(chapters, totalDurationMs)
        assertTrue(segments.none { it.type == SpatialFinSegmentType.INTRO })
    }

    @Test
    fun `rejects outro chapter starting in first half of media`() {
        val totalDurationMs = 1_440_000L
        val chapters = listOf(
            PlayerChapter(startPosition = 100_000L, name = "Credits"),
            PlayerChapter(startPosition = 200_000L, name = "Act 1"),
        )

        val segments = ChapterSegmentDetector.detectSegments(chapters, totalDurationMs)
        assertTrue(segments.none { it.type == SpatialFinSegmentType.OUTRO })
    }

    @Test
    fun `rejects chapters exceeding max duration`() {
        val totalDurationMs = 1_440_000L
        val chapters = listOf(
            // 10 minutes intro is too long to be a TV theme
            PlayerChapter(startPosition = 0L, name = "Opening"),
            PlayerChapter(startPosition = 600_000L, name = "Episode"),
        )

        val segments = ChapterSegmentDetector.detectSegments(chapters, totalDurationMs)
        assertTrue(segments.isEmpty())
    }

    @Test
    fun `handles empty chapters gracefully`() {
        val segments = ChapterSegmentDetector.detectSegments(emptyList(), 1_000_000L)
        assertTrue(segments.isEmpty())
    }

    @Test
    fun `normalizes chapter names with variations`() {
        assertEquals("opening", ChapterSegmentDetector.normalizeChapterName("Chapter 01 - Opening"))
        assertEquals("intro", ChapterSegmentDetector.normalizeChapterName("02. Intro [NC]"))
        assertEquals("ed", ChapterSegmentDetector.normalizeChapterName("Chapter 5: ED (Creditless)"))
        assertEquals("main title", ChapterSegmentDetector.normalizeChapterName("Main Title"))
    }
}
