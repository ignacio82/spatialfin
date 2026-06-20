package dev.jdtech.jellyfin.downloads

import android.app.DownloadManager

/**
 * Pure decision logic for download reconciliation, kept device-free so it can be
 * unit-tested (mirrors `ResumeFilter` / `PlayerTrackSignatures`).
 *
 * Guards the P0 in [DownloadStorageManager.reconcileItemSources]: when a LOCAL
 * source's file is missing on disk, the item + userdata must only be deleted if
 * there is **no** download still in flight for that source. Any `downloadtasks`
 * row whose status is not [DownloadManager.STATUS_SUCCESSFUL] means a download is
 * queued / running / paused / resumable, and the missing file is just the
 * not-yet-written target — deleting then would wipe the movie/episode/season/
 * show + userdata and orphan the still-queued task.
 */
object DownloadReconciliationPolicy {
    /**
     * @param downloadTaskStatuses the `status` of every `downloadtasks` row for
     *   the source (see [android.app.DownloadManager] status ints).
     * @return true only when it is safe to delete the missing source — i.e. no
     *   task is still active (every task, if any, is already SUCCESSFUL).
     */
    fun shouldDeleteMissingSource(downloadTaskStatuses: List<Int>): Boolean =
        downloadTaskStatuses.none { it != DownloadManager.STATUS_SUCCESSFUL }
}
