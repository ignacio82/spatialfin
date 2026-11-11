package dev.jdtech.jellyfin.player.xr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XrPassthroughControllerTest {

    @Test
    fun `clampOpacity clamps out-of-range and non-finite inputs`() {
        assertEquals(0.0f, XrPassthroughController.clampOpacity(-0.5f), 1e-4f)
        assertEquals(1.0f, XrPassthroughController.clampOpacity(1.5f), 1e-4f)
        assertEquals(0.0f, XrPassthroughController.clampOpacity(Float.NaN), 1e-4f)
        assertEquals(0.35f, XrPassthroughController.clampOpacity(0.35f), 1e-4f)
    }

    @Test
    fun `nextSteppedPreset cycles 0 to 35 to 70 to 100 to 0`() {
        assertEquals(0.35f, XrPassthroughController.nextSteppedPreset(0.0f), 1e-4f)
        assertEquals(0.70f, XrPassthroughController.nextSteppedPreset(0.35f), 1e-4f)
        assertEquals(1.0f, XrPassthroughController.nextSteppedPreset(0.70f), 1e-4f)
        assertEquals(0.0f, XrPassthroughController.nextSteppedPreset(1.0f), 1e-4f)
    }

    @Test
    fun `isPassthroughActive returns true above epsilon`() {
        assertFalse(XrPassthroughController.isPassthroughActive(0.0f))
        assertTrue(XrPassthroughController.isPassthroughActive(0.25f))
        assertTrue(XrPassthroughController.isPassthroughActive(1.0f))
    }

    @Test
    fun `formatPercentLabel and formatShortBadge round opacity to integer percent`() {
        assertEquals("0% (Blackout)", XrPassthroughController.formatPercentLabel(0.0f))
        assertEquals("35%", XrPassthroughController.formatPercentLabel(0.35f))
        assertEquals("Off", XrPassthroughController.formatShortBadge(0.0f))
        assertEquals("70%", XrPassthroughController.formatShortBadge(0.70f))
        assertEquals("100%", XrPassthroughController.formatShortBadge(1.0f))
    }
}
