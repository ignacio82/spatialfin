package dev.jdtech.jellyfin.player.local.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the subtitle keep/drop rule used on the player-open path. The cap exists
 * to stop Media3's single-ByteBuffer subtitle load from OOMing on pathological
 * anime ASS packs; the key safety property is that an *unknown* size (probe
 * failed or timed out) must never silently drop a working track.
 */
class PlaylistSubtitleSizeTest {
    private val cap = 50L * 1024L * 1024L

    @Test
    fun `unknown size is kept (probe failed or timed out)`() {
        assertTrue(PlaylistManager.shouldKeepSubtitle(null))
    }

    @Test
    fun `sizes at or below the cap are kept`() {
        assertTrue(PlaylistManager.shouldKeepSubtitle(0L))
        assertTrue(PlaylistManager.shouldKeepSubtitle(1L))
        assertTrue(PlaylistManager.shouldKeepSubtitle(cap))
        assertTrue(PlaylistManager.shouldKeepSubtitle(cap - 1))
    }

    @Test
    fun `sizes above the cap are dropped`() {
        assertFalse(PlaylistManager.shouldKeepSubtitle(cap + 1))
        assertFalse(PlaylistManager.shouldKeepSubtitle(200L * 1024L * 1024L))
    }
}
