package dev.jdtech.jellyfin.downloads

import android.app.DownloadManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the P0 in [DownloadStorageManager.reconcileItemSources]:
 * a missing local file must NOT trigger deletion while a download is still in
 * flight for that source (which would wipe the item + userdata and orphan the
 * task).
 */
class DownloadReconciliationPolicyTest {
    private val running = DownloadManager.STATUS_RUNNING
    private val pending = DownloadManager.STATUS_PENDING
    private val paused = DownloadManager.STATUS_PAUSED
    private val failed = DownloadManager.STATUS_FAILED
    private val successful = DownloadManager.STATUS_SUCCESSFUL

    @Test
    fun `no tasks means safe to delete`() {
        assertTrue(DownloadReconciliationPolicy.shouldDeleteMissingSource(emptyList()))
    }

    @Test
    fun `all-successful tasks are safe to delete`() {
        assertTrue(DownloadReconciliationPolicy.shouldDeleteMissingSource(listOf(successful)))
        assertTrue(DownloadReconciliationPolicy.shouldDeleteMissingSource(listOf(successful, successful)))
    }

    @Test
    fun `an in-flight task blocks deletion`() {
        // The P0: each of these means the missing file is the not-yet-written
        // target of an active download, not a deleted asset.
        assertFalse(DownloadReconciliationPolicy.shouldDeleteMissingSource(listOf(running)))
        assertFalse(DownloadReconciliationPolicy.shouldDeleteMissingSource(listOf(pending)))
        assertFalse(DownloadReconciliationPolicy.shouldDeleteMissingSource(listOf(paused)))
        assertFalse(DownloadReconciliationPolicy.shouldDeleteMissingSource(listOf(failed)))
    }

    @Test
    fun `a single active task among successful ones still blocks deletion`() {
        assertFalse(DownloadReconciliationPolicy.shouldDeleteMissingSource(listOf(successful, running)))
        assertFalse(DownloadReconciliationPolicy.shouldDeleteMissingSource(listOf(pending, successful, successful)))
    }
}
