package dev.jdtech.jellyfin.player.beam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeamResponsiveTest {

    @Test
    fun `beamWidthForDp maps Compact, Medium, and Expanded breakpoints`() {
        assertEquals(BeamWidth.Compact, beamWidthForDp(360))
        assertEquals(BeamWidth.Compact, beamWidthForDp(599))
        assertEquals(BeamWidth.Medium, beamWidthForDp(600))
        assertEquals(BeamWidth.Medium, beamWidthForDp(839))
        assertEquals(BeamWidth.Expanded, beamWidthForDp(840))
        assertEquals(BeamWidth.Expanded, beamWidthForDp(1280))
    }

    @Test
    fun `isCompact and isExpanded reflect BeamWidth enum values`() {
        assertTrue(BeamWidth.Compact.isCompact)
        assertFalse(BeamWidth.Compact.isExpanded)

        assertFalse(BeamWidth.Medium.isCompact)
        assertFalse(BeamWidth.Medium.isExpanded)

        assertFalse(BeamWidth.Expanded.isCompact)
        assertTrue(BeamWidth.Expanded.isExpanded)
    }
}
