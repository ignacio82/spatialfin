package dev.jdtech.jellyfin.work

import android.app.DownloadManager
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.DownloadTaskDto
import dev.jdtech.jellyfin.models.DownloadTaskKind
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Sweeps every download task — PRIMARY video and SUBTITLE sidecar — that the
 * DB believes is finished (status = STATUS_SUCCESSFUL) and verifies the file
 * actually exists on disk with the expected length. If the file is missing
 * or truncated — which can happen if the user cleared app storage, swapped
 * SD cards, or a previous encryption pass left things half-written — the
 * source / mediastream row is flipped back to its in-progress path so
 * isDownloaded() reflects reality, and the ResumableDownloadWorker is
 * re-enqueued so the file is re-downloaded automatically.
 *
 * Idempotent: enqueue with ExistingWorkPolicy.KEEP from app start and from
 * the Downloads screen.
 */
@HiltWorker
class DownloadIntegrityWorker
@AssistedInject
constructor(
    @Assisted private val appContext: Context,
    @Assisted private val params: WorkerParameters,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
    private val workManager: WorkManager,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val tasks =
                database.getCompletedDownloadTasks(DownloadTaskKind.PRIMARY) +
                    database.getCompletedDownloadTasks(DownloadTaskKind.SUBTITLE)
            var checked = 0
            var requeued = 0
            for (task in tasks) {
                checked++
                if (!isBroken(task)) continue

                Timber.w(
                    "DownloadIntegrityWorker: broken finalize taskId=%s kind=%s finalPath=%s exists=%s actualLen=%s expectedLen=%s",
                    task.id,
                    task.kind,
                    task.finalPath,
                    File(task.finalPath).exists(),
                    File(task.finalPath).takeIf(File::exists)?.length(),
                    task.totalBytes,
                )
                runCatching { requeueBrokenTask(task) }
                    .onFailure {
                        Timber.e(it, "DownloadIntegrityWorker: requeue failed taskId=%s", task.id)
                    }
                requeued++
            }
            Timber.i("DownloadIntegrityWorker: checked=%d requeued=%d", checked, requeued)
            Result.success()
        }

    private fun isBroken(task: DownloadTaskDto): Boolean {
        if (task.finalPath.isBlank()) return false
        val finalFile = File(task.finalPath)
        if (!finalFile.exists()) return true
        val expected = task.totalBytes ?: return false
        if (expected <= 0L) return false
        return finalFile.length() != expected
    }

    private fun requeueBrokenTask(task: DownloadTaskDto) {
        // Crash-safety order: DB updates first, file deletes after. If we
        // crash between the DB flip and the file delete, the DB already says
        // "not downloaded" so the next worker run re-downloads to tempPath
        // and renames over the orphan finalPath. The reverse order would
        // leave a STATUS_SUCCESSFUL row pointing at a deleted file until the
        // next integrity sweep — the user sees a "Downloaded" badge for a
        // file that won't play.
        if (task.tempPath.isNotBlank()) {
            when (task.kind) {
                // Flip the source / mediastream path back to the in-progress
                // path so isDownloaded() reflects reality until the worker
                // re-finalizes. Subtitles live on mediastreams.path, not
                // sources.path.
                DownloadTaskKind.PRIMARY ->
                    database.setSourcePath(task.sourceId, task.tempPath)
                DownloadTaskKind.SUBTITLE ->
                    task.mediaStreamId?.let { database.setMediaStreamPath(it, task.tempPath) }
            }
        }
        database.updateDownloadTask(
            id = task.id,
            downloadId = task.downloadId,
            bytesDownloaded = 0L,
            totalBytes = task.totalBytes,
            eTag = null,
            lastModified = null,
            status = DownloadManager.STATUS_PENDING,
            progress = 0,
            errorMessage = "File missing — re-downloading",
            updatedAt = System.currentTimeMillis(),
        )

        // Drop any half-written final + temp files so the next pass starts
        // from byte 0 instead of resuming against a corrupt range.
        File(task.finalPath).takeIf(File::exists)?.delete()
        File(task.tempPath).takeIf(File::exists)?.delete()

        enqueueResumableDownload(task.id, itemTitleForItemId(task.itemId))
    }

    private fun itemTitleForItemId(itemId: UUID): String? =
        database.getMovieOrNull(itemId)?.name
            ?: database.getEpisodeOrNull(itemId)?.name

    private fun enqueueResumableDownload(taskId: String, itemTitle: String?) {
        val networkType =
            if (appPreferences.getValue(appPreferences.downloadOverMobileData)) {
                NetworkType.CONNECTED
            } else {
                NetworkType.UNMETERED
            }
        val inputData =
            workDataOf(
                ResumableDownloadWorker.KEY_TASK_ID to taskId,
                ResumableDownloadWorker.KEY_ITEM_TITLE to (itemTitle ?: ""),
            )
        val request =
            OneTimeWorkRequestBuilder<ResumableDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .setInputData(inputData)
                .build()
        workManager.enqueueUniqueWork(
            ResumableDownloadWorker.uniqueWorkName(taskId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "download-integrity-check"

        fun enqueue(workManager: WorkManager) {
            val request = OneTimeWorkRequestBuilder<DownloadIntegrityWorker>().build()
            workManager.enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
