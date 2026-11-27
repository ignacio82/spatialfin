package dev.spatialfin.fcast.session.calibration

import android.media.AudioTimestamp
import org.junit.Assert.assertEquals
import org.junit.Test

class CalibrationCaptureBootTimeTest {

    @Test
    fun `CalibrationCapture uses TIMEBASE_BOOTTIME matching elapsedRealtime`() {
        assertEquals(AudioTimestamp.TIMEBASE_BOOTTIME, CalibrationCapture.TIMESTAMP_TIMEBASE)
    }

    @Test
    fun `firstSampleMonotonicMs back-projects frame offset cleanly`() {
        // 48,000 samples at 48 kHz = 1,000 ms elapsed since sample 0.
        val sample0Ms = CalibrationCapture.firstSampleMonotonicMs(
            timestampFramePosition = 48_000L,
            timestampNanoTime = 10_000_000_000L, // 10,000 ms
            sampleRateHz = 48_000,
        )
        assertEquals(9_000L, sample0Ms)
    }
}
