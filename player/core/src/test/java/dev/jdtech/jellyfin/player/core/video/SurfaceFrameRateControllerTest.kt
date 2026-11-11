package dev.jdtech.jellyfin.player.core.video

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SurfaceFrameRateControllerTest {

    @Test
    fun `normalizeVideoFrameRate accepts valid cinema and broadcast rates`() {
        assertEquals(23.976f, SurfaceFrameRateController.normalizeVideoFrameRate(23.976f)!!, 1e-4f)
        assertEquals(24f, SurfaceFrameRateController.normalizeVideoFrameRate(24f)!!, 1e-4f)
        assertEquals(25f, SurfaceFrameRateController.normalizeVideoFrameRate(25f)!!, 1e-4f)
        assertEquals(29.97f, SurfaceFrameRateController.normalizeVideoFrameRate(29.97f)!!, 1e-4f)
        assertEquals(59.94f, SurfaceFrameRateController.normalizeVideoFrameRate(59.94f)!!, 1e-4f)
        assertEquals(120f, SurfaceFrameRateController.normalizeVideoFrameRate(120f)!!, 1e-4f)
    }

    @Test
    fun `normalizeVideoFrameRate rejects zero, negative, NaN, infinite, and out-of-range rates`() {
        assertNull(SurfaceFrameRateController.normalizeVideoFrameRate(0f))
        assertNull(SurfaceFrameRateController.normalizeVideoFrameRate(-24f))
        assertNull(SurfaceFrameRateController.normalizeVideoFrameRate(5f))
        assertNull(SurfaceFrameRateController.normalizeVideoFrameRate(1000f))
        assertNull(SurfaceFrameRateController.normalizeVideoFrameRate(Float.NaN))
        assertNull(SurfaceFrameRateController.normalizeVideoFrameRate(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `resolveChangeStrategy maps seamless default and non-seamless opt-in`() {
        assertEquals(
            Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
            SurfaceFrameRateController.resolveChangeStrategy(allowNonSeamlessSwitch = false),
        )
        assertEquals(
            Surface.CHANGE_FRAME_RATE_ALWAYS,
            SurfaceFrameRateController.resolveChangeStrategy(allowNonSeamlessSwitch = true),
        )
    }
}
