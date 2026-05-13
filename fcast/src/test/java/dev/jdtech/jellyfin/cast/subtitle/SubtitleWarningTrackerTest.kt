package dev.jdtech.jellyfin.cast.subtitle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleWarningTrackerTest {

    @Test
    fun `first call returns true`() {
        val tracker = SubtitleWarningTracker()
        assertTrue(tracker.shouldWarn("10.0.0.5", 46899))
    }

    @Test
    fun `same receiver returns false on subsequent calls`() {
        val tracker = SubtitleWarningTracker()
        tracker.shouldWarn("10.0.0.5", 46899)
        assertFalse(
            "Second call for same receiver must dedup",
            tracker.shouldWarn("10.0.0.5", 46899),
        )
        assertFalse(
            "Third call still dedups",
            tracker.shouldWarn("10.0.0.5", 46899),
        )
    }

    @Test
    fun `different receivers each warn once`() {
        val tracker = SubtitleWarningTracker()
        assertTrue(tracker.shouldWarn("10.0.0.5", 46899))
        assertTrue(tracker.shouldWarn("10.0.0.6", 46899))
        assertTrue(tracker.shouldWarn("10.0.0.5", 8009))
        // Re-asking each: all dedup.
        assertFalse(tracker.shouldWarn("10.0.0.5", 46899))
        assertFalse(tracker.shouldWarn("10.0.0.6", 46899))
        assertFalse(tracker.shouldWarn("10.0.0.5", 8009))
    }

    @Test
    fun `reset re-arms warnings`() {
        val tracker = SubtitleWarningTracker()
        tracker.shouldWarn("10.0.0.5", 46899)
        tracker.reset()
        assertTrue(
            "After reset, the receiver should warn again",
            tracker.shouldWarn("10.0.0.5", 46899),
        )
    }

    @Test
    fun `port disambiguates same host`() {
        // Two FCast receivers on the same machine (e.g. dev workstation running two builds) get
        // independent warnings. Cheap insurance against accidental conflation.
        val tracker = SubtitleWarningTracker()
        assertTrue(tracker.shouldWarn("10.0.0.5", 46899))
        assertTrue(tracker.shouldWarn("10.0.0.5", 46900))
    }
}
