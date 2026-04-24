package dev.jdtech.jellyfin.player.local.presentation

import androidx.media3.common.C
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerTrackHeuristicsTest {

    // -----------------------------------------------------------------
    // isForcedOrSignsOnly(label, selectionFlags)
    // -----------------------------------------------------------------

    @Test
    fun `SELECTION_FLAG_FORCED alone is enough`() {
        assertTrue(
            PlayerTrackHeuristics.isForcedOrSignsOnly(
                label = null,
                selectionFlags = C.SELECTION_FLAG_FORCED,
            ),
        )
    }

    @Test
    fun `SELECTION_FLAG_FORCED combined with other flags still triggers`() {
        assertTrue(
            PlayerTrackHeuristics.isForcedOrSignsOnly(
                label = "English",
                selectionFlags = C.SELECTION_FLAG_FORCED or C.SELECTION_FLAG_DEFAULT,
            ),
        )
    }

    @Test
    fun `forced label is detected case-insensitively`() {
        assertTrue(PlayerTrackHeuristics.isForcedOrSignsOnly("Forced", 0))
        assertTrue(PlayerTrackHeuristics.isForcedOrSignsOnly("FORCED", 0))
        assertTrue(PlayerTrackHeuristics.isForcedOrSignsOnly("forced", 0))
        assertTrue(PlayerTrackHeuristics.isForcedOrSignsOnly("English (Forced)", 0))
    }

    @Test
    fun `signs and songs labels are detected`() {
        assertTrue(PlayerTrackHeuristics.isForcedOrSignsOnly("Signs", 0))
        assertTrue(PlayerTrackHeuristics.isForcedOrSignsOnly("Sign", 0))
        assertTrue(PlayerTrackHeuristics.isForcedOrSignsOnly("Song", 0))
        assertTrue(PlayerTrackHeuristics.isForcedOrSignsOnly("Songs", 0))
        assertTrue(PlayerTrackHeuristics.isForcedOrSignsOnly("Signs & Songs", 0))
        assertTrue(PlayerTrackHeuristics.isForcedOrSignsOnly("Signs/Songs", 0))
    }

    @Test
    fun `S and S short label is NOT flagged because it lacks sign-song keywords`() {
        // Short "S&S" labels are intentionally NOT matched — the predicate is
        // keyword-based (whole-word `sign`, `song`, `forced`), not acronym-based. If a
        // file ships that label, users can still switch to it manually. We prefer
        // false negatives (allow) over false positives (block a real dialogue track).
        assertFalse(PlayerTrackHeuristics.isForcedOrSignsOnly("S&S", 0))
    }

    @Test
    fun `full dialogue label is not flagged`() {
        assertFalse(PlayerTrackHeuristics.isForcedOrSignsOnly("Full Dialogue", 0))
        assertFalse(PlayerTrackHeuristics.isForcedOrSignsOnly("Dialogue", 0))
        assertFalse(PlayerTrackHeuristics.isForcedOrSignsOnly("English", 0))
        assertFalse(PlayerTrackHeuristics.isForcedOrSignsOnly("Spain", 0))
    }

    @Test
    fun `SDH and CC labels are not flagged as forced`() {
        assertFalse(PlayerTrackHeuristics.isForcedOrSignsOnly("SDH", 0))
        assertFalse(PlayerTrackHeuristics.isForcedOrSignsOnly("English [CC]", 0))
    }

    @Test
    fun `null or empty label with no flags returns false`() {
        assertFalse(PlayerTrackHeuristics.isForcedOrSignsOnly(null, 0))
        assertFalse(PlayerTrackHeuristics.isForcedOrSignsOnly("", 0))
        assertFalse(PlayerTrackHeuristics.isForcedOrSignsOnly("   ", 0))
    }

    @Test
    fun `unrelated flags do not trigger`() {
        assertFalse(
            PlayerTrackHeuristics.isForcedOrSignsOnly(
                label = "English",
                selectionFlags = C.SELECTION_FLAG_DEFAULT,
            ),
        )
        assertFalse(
            PlayerTrackHeuristics.isForcedOrSignsOnly(
                label = "English",
                selectionFlags = C.SELECTION_FLAG_AUTOSELECT,
            ),
        )
    }

    @Test
    fun `word-boundary keeps unrelated labels clean`() {
        // Regression guard: substrings like "sign" inside "Designer" or "Assigned" must
        // NOT flag the track. Whole-word match only.
        assertFalse(PlayerTrackHeuristics.isForcedOrSignsOnly("Designer Cut", 0))
        assertFalse(PlayerTrackHeuristics.isForcedOrSignsOnly("Assigned seat", 0))
        assertFalse(PlayerTrackHeuristics.isForcedOrSignsOnly("Strongly worded", 0))
        assertFalse(PlayerTrackHeuristics.isForcedOrSignsOnly("Enforceable", 0))
    }
}
