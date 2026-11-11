package dev.jdtech.jellyfin.player.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSubtitleSyncControllerTest {

    @Test
    fun `clamps subtitle and audio offsets within defined bounds`() {
        val controller = AudioSubtitleSyncController()

        assertEquals(0L, controller.currentSubtitleOffsetMs())
        assertEquals(0L, controller.currentAudioOffsetMs())

        assertEquals(10_000L, controller.setSubtitleOffsetMs(25_000L))
        assertEquals(-10_000L, controller.setSubtitleOffsetMs(-25_000L))

        assertEquals(5_000L, controller.setAudioOffsetMs(9_000L))
        assertEquals(-5_000L, controller.setAudioOffsetMs(-9_000L))
    }

    @Test
    fun `adjusts subtitle and audio offsets incrementally and resets to zero`() {
        val controller = AudioSubtitleSyncController()

        assertEquals(250L, controller.adjustSubtitleOffsetMs(250L))
        assertEquals(200L, controller.adjustSubtitleOffsetMs(-50L))
        assertEquals(-200L, controller.adjustAudioOffsetMs(-200L))
        assertEquals(0L, controller.currentPositivePcmDelayMs())

        controller.setAudioOffsetMs(350L)
        assertEquals(350L, controller.currentPositivePcmDelayMs())

        controller.reset()
        assertEquals(0L, controller.currentSubtitleOffsetMs())
        assertEquals(0L, controller.currentAudioOffsetMs())
        assertEquals(0L, controller.subtitleOffsetMs.value)
        assertEquals(0L, controller.audioOffsetMs.value)
    }

    @Test
    fun `effectiveLibassPositionMs delays for positive offset and advances for negative offset`() {
        // +500 ms subtitle delay: at raw 5500 ms, render subtitle frame for 5000 ms
        assertEquals(
            5_000L,
            AudioSubtitleSyncController.effectiveLibassPositionMs(5_500L, 500L),
        )
        // -500 ms subtitle advance: at raw 4500 ms, render subtitle frame for 5000 ms
        assertEquals(
            5_000L,
            AudioSubtitleSyncController.effectiveLibassPositionMs(4_500L, -500L),
        )
        // Clamped at 0 ms when positive delay exceeds raw position
        assertEquals(
            0L,
            AudioSubtitleSyncController.effectiveLibassPositionMs(200L, 500L),
        )
    }

    @Test
    fun `formatSignedOffsetMs includes explicit sign for positive offsets`() {
        assertEquals("+250 ms", AudioSubtitleSyncController.formatSignedOffsetMs(250L))
        assertEquals("-150 ms", AudioSubtitleSyncController.formatSignedOffsetMs(-150L))
        assertEquals("0 ms", AudioSubtitleSyncController.formatSignedOffsetMs(0L))
    }
}
