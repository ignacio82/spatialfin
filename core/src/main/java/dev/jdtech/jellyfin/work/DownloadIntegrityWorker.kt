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
import dev.jdtech.jellyfin.downloads.ActiveDownloadRegistry
import dev.jdtech.jellyfin.downloads.DownloadStagingPaths
import dev.jdtech.jellyfin.models.DownloadTaskDto
import dev.jdtech.jellyfin.models.DownloadTaskKind
import dev.jdtech.jellyfin.security.ContentKeyManager
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionMode
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Sweeps every completed download task belonging to the active authenticated server and verifies
 * the file actually exists on disk with the expected length. Broken tasks are flipped back to
 * in-progress, cleared of stale encryption/temp/staging files, and re-enqueued. Also sweeps
 * orphaned or completed staging files from `filesDir/downloads_tmp` under [ActiveDownloadRegistry.commitMutex].
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
    private val sessionActivationCoordinator: SessionActivationCoordinator,
    private val appLockObserver: AppLockObserver,
    private val activeDownloadRegistry: ActiveDownloadRegistry,
    private val contentKeyManager: ContentKeyManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            if (isLocked()) {
                Timber.d("DownloadIntegrityWorker: skipped while App Lock is LOCKED")
                return@withContext Result.success()
            }

            val state = sessionActivationCoordinator.ensureInitialized()
            if (isLocked() ||
                state.sessionMode != SessionMode.SERVER_AUTHENTICATED ||
                sessionActivationCoordinator.isActivating.get() ||
                state.activeServerId.isNullOrBlank() ||
                state.activeUserId == null
            ) {
                return@withContext Result.success()
            }

            val activeServerId = state.activeServerId
            val activeUserId = state.activeUserId
            val expectedSessionEpoch = state.sessionEpoch

            // Sweep orphaned / completed files from private downloads_tmp under commitMutex
            sweepPrivateStagingDir(expectedSessionEpoch, activeServerId)

            val tasks =
                database.getCompletedDownloadTasks(DownloadTaskKind.PRIMARY) +
                    database.getCompletedDownloadTasks(DownloadTaskKind.SUBTITLE)
            var checked = 0
            var requeued = 0
            for (task in tasks) {
                if (!isStillValid(expectedSessionEpoch, activeServerId)) break
                if (database.resolveDownloadTaskServerId(task) != activeServerId) continue
                if (task.isEncrypted && contentKeyManager.getDekOrNull()?.also { it.fill(0) } == null) {
                    continue
                }

                checked++
                if (!isBroken(task, expectedSessionEpoch, activeServerId)) continue

                Timber.w(
                    "DownloadIntegrityWorker: broken finalize taskId=%s kind=%s exists=%s actualLen=%s expectedLen=%s",
                    task.id,
                    task.kind,
                    File(task.finalPath).exists(),
                    File(task.finalPath).takeIf(File::exists)?.length(),
                    task.totalBytes,
                )
                runCatching {
                    requeueBrokenTask(
                        task = task,
                        expectedSessionEpoch = expectedSessionEpoch,
                        activeServerId = activeServerId,
                        activeUserId = activeUserId,
                    )
                }.onFailure {
                    Timber.e(it, "DownloadIntegrityWorker: requeue failed taskId=%s", task.id)
                }
                requeued++
            }
            Timber.i("DownloadIntegrityWorker: checked=%d requeued=%d", checked, requeued)
            Result.success()
        }

    private suspend fun sweepPrivateStagingDir(
        expectedSessionEpoch: Long,
        activeServerId: String,
    ) {
        activeDownloadRegistry.commitMutex.withLock {
            if (!isStillValid(expectedSessionEpoch, activeServerId)) return
            val stagingDir = File(appContext.filesDir, DownloadStagingPaths.STAGING_DIR_NAME)
            if (!stagingDir.exists() || !stagingDir.isDirectory) return

            val allTasks = database.getAllDownloadTasks()
            val activeStagingNames =
                allTasks
                    .filter { it.status != DownloadManager.STATUS_SUCCESSFUL }
                    .map { DownloadStagingPaths.privateStagingFile(appContext, it.id).name }
                    .toSet()

            stagingDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name !in activeStagingNames) {
                    runCatching { file.delete() }
                }
            }
        }
    }

    private fun isBroken(
        task: DownloadTaskDto,
        expectedSessionEpoch: Long,
        activeServerId: String,
    ): Boolean {
        if (task.finalPath.isBlank()) return false
        val finalFile = File(task.finalPath)
        if (!finalFile.exists()) return true
        val actualLength = finalFile.length()
        if (actualLength <= 0L) return true
        val expected = task.totalBytes ?: return false
        if (expected <= 0L) return false
        if (actualLength != expected) {
            val isTranscoded =
                task.finalPath.endsWith("_transcoded.mp4", ignoreCase = true) ||
                    task.requestUrl.contains("Static=false", ignoreCase = true)
            val isCompletedOnDisk =
                actualLength == task.bytesDownloaded ||
                    (task.status == DownloadManager.STATUS_SUCCESSFUL &&
                        (task.tempPath.isBlank() || !File(task.tempPath).exists()))
            if (isTranscoded && isCompletedOnDisk) {
                if (!isStillValid(expectedSessionEpoch, activeServerId)) return false
                database.updateDownloadTask(
                    id = task.id,
                    downloadId = task.downloadId,
                    bytesDownloaded = actualLength,
                    totalBytes = actualLength,
                    eTag = task.eTag,
                    lastModified = task.lastModified,
                    status = task.status,
                    progress = 100,
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis(),
                )
                return false
            }
            return true
        }
        return false
    }

    private suspend fun requeueBrokenTask(
        task: DownloadTaskDto,
        expectedSessionEpoch: Long,
        activeServerId: String,
        activeUserId: UUID,
    ) {
        var shouldEnqueue = false
        activeDownloadRegistry.commitMutex.withLock {
            if (!isStillValid(expectedSessionEpoch, activeServerId)) return

            if (task.tempPath.isNotBlank()) {
                when (task.kind) {
                    DownloadTaskKind.PRIMARY ->
                        database.setSourcePath(task.sourceId, task.tempPath)
                    DownloadTaskKind.SUBTITLE ->
                        task.mediaStreamId?.let { database.setMediaStreamPath(it, task.tempPath) }
                }
            }
            val now = System.currentTimeMillis()
            database.setDownloadTaskEncryption(
                id = task.id,
                isEncrypted = false,
                encryptionIv = null,
                updatedAt = now,
            )
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
                updatedAt = now,
            )

            File(task.finalPath).takeIf(File::exists)?.delete()
            File("${task.finalPath}.enc.tmp").takeIf(File::exists)?.delete()
            if (task.tempPath.isNotBlank()) {
                File(task.tempPath).takeIf(File::exists)?.delete()
            }
            DownloadStagingPaths.privateStagingFile(appContext, task.id).takeIf(File::exists)?.delete()
            shouldEnqueue = true
        }

        if (shouldEnqueue && isStillValid(expectedSessionEpoch, activeServerId)) {
            enqueueResumableDownload(
                taskId = task.id,
                itemTitle = itemTitleForItemId(task.itemId),
                serverId = activeServerId,
                userId = activeUserId,
                isPrimary = task.kind == DownloadTaskKind.PRIMARY,
            )
        }
    }

    private fun isLocked(): Boolean =
        appLockObserver.lockState.value == AppLockManager.LockState.LOCKED

    private fun isStillValid(expectedSessionEpoch: Long, activeServerId: String): Boolean {
        if (isStopped || isLocked()) return false
        val current = sessionActivationCoordinator.sessionState.value
        return current.sessionMode == SessionMode.SERVER_AUTHENTICATED &&
            !sessionActivationCoordinator.isActivating.get() &&
            current.sessionEpoch == expectedSessionEpoch &&
            current.activeServerId == activeServerId
    }

    private fun itemTitleForItemId(itemId: UUID): String? =
        database.getMovieOrNull(itemId)?.name
            ?: database.getEpisodeOrNull(itemId)?.name

    private fun enqueueResumableDownload(
        taskId: String,
        itemTitle: String?,
        serverId: String,
        userId: UUID,
        isPrimary: Boolean,
    ) {
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
        val requestBuilder =
            OneTimeWorkRequestBuilder<ResumableDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .setInputData(inputData)
                .addTag("download-server:$serverId")
                .addTag("download-user:$userId")
        if (isPrimary) {
            requestBuilder.addTag("download-primary")
        }
        workManager.enqueueUniqueWork(
            ResumableDownloadWorker.uniqueWorkName(taskId),
            ExistingWorkPolicy.KEEP,
            requestBuilder.build(),
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "download-integrity-check"

        fun enqueue(context: Context) {
            enqueue(WorkManager.getInstance(context))
        }

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
