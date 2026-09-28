package dev.spatialfin.companion.wear.fcast

import org.junit.Assert.assertEquals
import org.junit.Test

class WearAudioTimingTest {
    @Test
    fun `future start waits for receiver clock rendezvous`() {
        assertEquals(250L, resumeDelayMs(1_000L, 1_250L))
    }

    @Test
    fun `late and excessive lead times cannot stall audio indefinitely`() {
        assertEquals(0L, resumeDelayMs(1_000L, 900L))
        assertEquals(0L, resumeDelayMs(1_000L, 1_000L))
        assertEquals(10_000L, resumeDelayMs(1_000L, Long.MAX_VALUE))
    }
}
