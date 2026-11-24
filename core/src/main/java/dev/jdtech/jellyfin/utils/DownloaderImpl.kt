package dev.jdtech.jellyfin.utils

import android.app.DownloadManager
import android.content.Context
import android.os.StatFs
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderState
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.downloads.ActiveDownloadRegistry
import dev.jdtech.jellyfin.downloads.DownloadStagingPaths
import dev.jdtech.jellyfin.downloads.DownloadStorageManager
import dev.jdtech.jellyfin.downloads.OrphanedDownloadCleanupResult
import dev.jdtech.jellyfin.downloads.OrphanedDownloadsReport
import dev.jdtech.jellyfin.models.BulkDownloadSettings
import dev.jdtech.jellyfin.models.DownloadMode
import dev.jdtech.jellyfin.models.DownloadRequest
import dev.jdtech.jellyfin.models.DownloadTaskDto
import dev.jdtech.jellyfin.models.DownloadTaskKind
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.SpatialFinSourceType
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.downloadTaskId
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.models.toSpatialFinEpisode
import dev.jdtech.jellyfin.models.toSpatialFinMovie
import dev.jdtech.jellyfin.models.toSpatialFinSource
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.security.ContentKeyManager
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionMode
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.work.BulkDownloadResolutionWorker
import dev.jdtech.jellyfin.work.DownloadIntegrityWorker
import dev.jdtech.jellyfin.work.DownloadPreparationWorker
import dev.jdtech.jellyfin.work.ResumableDownloadWorker
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

class DownloaderImpl(
    private val context: Context,
    private val database: ServerDatabaseDao,
    private val jellyfinRepository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    private val workManager: WorkManager,
    private val downloadStorageManager: DownloadStorageManager,
    private val sessionActivationCoordinator: SessionActivationCoordinator,
    private val appLockObserver: AppLockObserver,
    private val activeDownloadRegistry: ActiveDownloadRegistry,
    private val contentKeyManager: ContentKeyManager,
) : Downloader {
    private val downloadManager = context.getSystemService(DownloadManager::class.java)
    private val speedTracker = mutableMapOf<String, Pair<Long, Long>>()

    private fun isLocked(): Boolean =
        appLockObserver.lockState.value == AppLockManager.LockState.LOCKED

    override suspend fun downloadItem(
        item: SpatialFinItem,
        request: DownloadRequest,
    ): UiText? {
        return try {
            if (isLocked()) {
                return UiText.DynamicString(ResumableDownloadWorker.DEK_LOCKED_PAUSE_MESSAGE)
            }
            val session = sessionActivationCoordinator.ensureInitialized()
            val activeServerId = session.activeServerId
            val activeUserId = session.activeUserId
            if (session.sessionMode != SessionMode.SERVER_AUTHENTICATED ||
                sessionActivationCoordinator.isActivating.get() ||
                activeServerId.isNullOrBlank() ||
                activeUserId == null ||
                isLocked()
            ) {
                return UiText.DynamicString("Sign in required to download")
            }

            sessionActivationCoordinator.awaitPendingServerDeleteCleanup(activeServerId)

            downloadStorageManager.clearDeletedTombstone(item.id)
            val taskId = downloadTaskId(item.id, request.sourceId)
            val existingTask = database.getDownloadTaskById(taskId)
            val placeholderUrl =
                existingTask?.requestUrl?.takeIf { it.isNotBlank() }
                    ?: session.activeServerAddress?.takeIf { it.isNotBlank() }?.let { base ->
                        "${base.trimEnd('/')}/Items/${item.id}/Download"
                    }.orEmpty()
            database.insertDownloadTask(
                DownloadTaskDto(
                    id = taskId,
                    itemId = item.id,
                    sourceId = request.sourceId,
                    kind = DownloadTaskKind.PRIMARY,
                    mediaStreamId = null,
                    downloadId = null,
                    requestUrl = placeholderUrl,
                    accessToken = existingTask?.accessToken,
                    tempPath = existingTask?.tempPath.orEmpty(),
                    finalPath = existingTask?.finalPath.orEmpty(),
                    bytesDownloaded = existingTask?.bytesDownloaded ?: 0L,
                    totalBytes = existingTask?.totalBytes,
                    eTag = existingTask?.eTag,
                    lastModified = existingTask?.lastModified,
                    status = DownloadManager.STATUS_PENDING,
                    progress = existingTask?.progress ?: 0,
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            enqueuePreparationWorker(
                taskId = taskId,
                itemId = item.id,
                request = request,
                serverId = activeServerId,
                userId = activeUserId,
            )
            null
        } catch (e: Exception) {
            Timber.e(e, "Failed to enqueue download for item %s", item.id)
            if (e.message != null) UiText.DynamicString(e.message!!)
            else UiText.StringResource(CoreR.string.unknown_error)
        }
    }

    override suspend fun downloadItems(
        episodes: List<SpatialFinEpisode>,
        settings: BulkDownloadSettings,
    ): BulkDownloadResult {
        if (isLocked()) {
            return BulkDownloadResult(queued = 0, skipped = 0, failed = episodes.size)
        }
        val session = sessionActivationCoordinator.ensureInitialized()
        val activeServerId = session.activeServerId
        val activeUserId = session.activeUserId
        if (session.sessionMode != SessionMode.SERVER_AUTHENTICATED ||
            sessionActivationCoordinator.isActivating.get() ||
            activeServerId.isNullOrBlank() ||
            activeUserId == null ||
            isLocked()
        ) {
            return BulkDownloadResult(queued = 0, skipped = 0, failed = episodes.size)
        }

        val storageShortfall = run {
            val pendingEpisodes = episodes.filter { !it.isDownloaded() }
            val estimatedBytes = pendingEpisodes.sumOf { ep ->
                if (settings.mode == DownloadMode.TRANSCODED) {
                    val durationSec = if (ep.runtimeTicks > 0L) ep.runtimeTicks / 10_000_000.0 else 2700.0
                    val bps = (settings.videoBitrate ?: DownloadPreparationWorker.DEFAULT_TRANSCODE_VIDEO_BITRATE) + 192_000
                    val rawEstimate = (durationSec * (bps / 8.0)).toLong()
                    val originalSize = ep.sources.firstOrNull { it.type == SpatialFinSourceType.REMOTE }?.size ?: 0L
                    if (originalSize > 0L) rawEstimate.coerceAtMost(originalSize) else rawEstimate
                } else {
                    ep.sources.firstOrNull { it.type == SpatialFinSourceType.REMOTE }?.size ?: 0L
                }
            }
            if (estimatedBytes > 0L) {
                val downloadDir = downloadStorageManager.downloadsRoot()
                downloadDir.mkdirs()
                val available = runCatching { StatFs(downloadDir.path).availableBytes }.getOrElse { Long.MAX_VALUE }
                if (estimatedBytes > available) estimatedBytes - available else null
            } else null
        }

        var skipped = 0
        val pendingEpisodeIds = mutableListOf<String>()
        for (episode in episodes) {
            if (episode.isDownloaded()) {
                skipped++
            } else {
                downloadStorageManager.clearDeletedTombstone(episode.id)
                pendingEpisodeIds.add(episode.id.toString())
            }
        }

        pendingEpisodeIds.chunked(BulkDownloadResolutionWorker.MAX_ITEM_IDS_PER_REQUEST).forEach { itemIds ->
            val inputBuilder = androidx.work.Data.Builder()
                .putStringArray(BulkDownloadResolutionWorker.KEY_ITEM_IDS, itemIds.toTypedArray())
                .putString(BulkDownloadResolutionWorker.KEY_MODE, settings.mode.name)
            settings.videoBitrate?.let {
                inputBuilder.putInt(BulkDownloadResolutionWorker.KEY_VIDEO_BITRATE, it)
            }

            val workRequest = OneTimeWorkRequestBuilder<BulkDownloadResolutionWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(inputBuilder.build())
                .addTag("download-server:$activeServerId")
                .addTag("download-user:$activeUserId")
                .addTag("download-primary")
                .build()

            workManager.enqueue(workRequest)
        }

        return BulkDownloadResult(
            queued = pendingEpisodeIds.size,
            skipped = skipped,
            failed = 0,
            storageShortfallBytes = storageShortfall,
        )
    }

    override suspend fun cancelDownload(item: SpatialFinItem) {
        val tasks = database.getDownloadTasksByItemId(item.id)
        tasks.forEach { task ->
            workManager.cancelUniqueWork(ResumableDownloadWorker.uniqueWorkName(task.id))
            workManager.cancelUniqueWork(DownloadPreparationWorker.uniqueWorkName(task.id))
            activeDownloadRegistry.cancelByTaskId(task.id)
        }
        activeDownloadRegistry.cancelByItemId(item.id)

        val source =
            item.sources.firstOrNull { it.type == SpatialFinSourceType.LOCAL }
                ?: database.getSources(item.id)
                    .firstOrNull { it.type == SpatialFinSourceType.LOCAL }
                    ?.toSpatialFinSource(database)
        if (source != null) {
            deleteItem(item, source)
        } else {
            activeDownloadRegistry.commitMutex.withLock {
                cleanupTaskStagingFiles(tasks)
                database.deleteDownloadTasksByItemId(item.id)
                downloadStorageManager.purgeItem(item, deletePhysicalFile = true)
            }
        }
    }

    override suspend fun deleteItem(item: SpatialFinItem, source: SpatialFinSource) {
        val tasks = database.getDownloadTasksByItemId(item.id)
        tasks.forEach { task ->
            workManager.cancelUniqueWork(ResumableDownloadWorker.uniqueWorkName(task.id))
            workManager.cancelUniqueWork(DownloadPreparationWorker.uniqueWorkName(task.id))
            activeDownloadRegistry.cancelByTaskId(task.id)
        }
        activeDownloadRegistry.cancelByItemId(item.id)

        activeDownloadRegistry.commitMutex.withLock {
            source.downloadId?.let { downloadManager.remove(it) }
            database.getMediaStreamsBySourceId(source.id)
                .mapNotNull { it.downloadId }
                .forEach { downloadManager.remove(it) }
            cleanupTaskStagingFiles(tasks)
            database.deleteDownloadTask(item.id, source.id)
            downloadStorageManager.deleteItem(item, source, deletePhysicalFile = true)
        }
    }

    override fun observeDownloadStatus(itemId: UUID): Flow<DownloadStatusSnapshot?> {
        return database.observeDownloadTask(itemId).map { task ->
            task?.let {
                DownloadStatusSnapshot(
                    downloadId = it.downloadId,
                    state =
                        DownloaderState(
                            status = it.status,
                            progress = it.progress.coerceAtLeast(0) / 100f,
                            errorText = it.errorMessage?.let(UiText::DynamicString),
                        ),
                )
            }
        }
    }

    override suspend fun cancelDownloadById(itemId: UUID) {
        val userId = runCatching { jellyfinRepository.getUserId() }.getOrNull()
        val item: SpatialFinItem? =
            database.getMovieOrNull(itemId)?.toSpatialFinMovie(database, userId)
                ?: database.getEpisodeOrNull(itemId)?.toSpatialFinEpisode(database, userId)
        if (item != null) {
            cancelDownload(item)
        } else {
            val tasks = database.getDownloadTasksByItemId(itemId)
            tasks.forEach { task ->
                workManager.cancelUniqueWork(ResumableDownloadWorker.uniqueWorkName(task.id))
                workManager.cancelUniqueWork(DownloadPreparationWorker.uniqueWorkName(task.id))
                activeDownloadRegistry.cancelByTaskId(task.id)
            }
            activeDownloadRegistry.cancelByItemId(itemId)
            activeDownloadRegistry.commitMutex.withLock {
                cleanupTaskStagingFiles(tasks)
                database.deleteDownloadTasksByItemId(itemId)
            }
        }
    }

    override suspend fun pauseDownload(item: SpatialFinItem) = pauseDownloadById(item.id)

    override suspend fun pauseDownloadById(itemId: UUID) {
        val tasks = database.getDownloadTasksByItemId(itemId)
            .filter { it.status != DownloadManager.STATUS_SUCCESSFUL }
        for (task in tasks) {
            workManager.cancelUniqueWork(ResumableDownloadWorker.uniqueWorkName(task.id))
            workManager.cancelUniqueWork(DownloadPreparationWorker.uniqueWorkName(task.id))
            activeDownloadRegistry.cancelByTaskId(task.id)
        }
        activeDownloadRegistry.cancelByItemId(itemId)
        activeDownloadRegistry.commitMutex.withLock {
            for (task in tasks) {
                val latest = database.getDownloadTaskById(task.id) ?: continue
                if (latest.status == DownloadManager.STATUS_SUCCESSFUL) continue
                database.updateDownloadTask(
                    id = latest.id,
                    downloadId = latest.downloadId,
                    bytesDownloaded = latest.bytesDownloaded,
                    totalBytes = latest.totalBytes,
                    eTag = latest.eTag,
                    lastModified = latest.lastModified,
                    status = DownloadManager.STATUS_PAUSED,
                    progress = latest.progress,
                    errorMessage = "Paused",
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }
    }

    override suspend fun resumeDownload(item: SpatialFinItem) = resumeDownloadById(item.id)

    override suspend fun resumeDownloadById(itemId: UUID) {
        if (isLocked()) return
        val session = sessionActivationCoordinator.ensureInitialized()
        val activeServerId = session.activeServerId
        val activeUserId = session.activeUserId
        if (session.sessionMode != SessionMode.SERVER_AUTHENTICATED ||
            sessionActivationCoordinator.isActivating.get() ||
            activeServerId.isNullOrBlank() ||
            activeUserId == null ||
            isLocked()
        ) {
            return
        }

        val tasks = database.getDownloadTasksByItemId(itemId)
            .filter {
                (it.status == DownloadManager.STATUS_PAUSED || it.status == DownloadManager.STATUS_FAILED) &&
                    database.resolveDownloadTaskServerId(it) == activeServerId
            }
        for (task in tasks) {
            if (task.kind == DownloadTaskKind.PRIMARY) {
                val itemName = database.getMovieOrNull(task.itemId)?.name
                    ?: database.getEpisodeOrNull(task.itemId)?.name
                val needsPreparation =
                    task.requestUrl.isBlank() || task.tempPath.isBlank() || task.finalPath.isBlank()
                if (needsPreparation) {
                    enqueuePreparationWorker(
                        taskId = task.id,
                        itemId = task.itemId,
                        request = DownloadRequest(
                            sourceId = task.sourceId,
                            mode = DownloadMode.ORIGINAL,
                        ),
                        serverId = activeServerId,
                        userId = activeUserId,
                    )
                } else {
                    enqueueResumableDownload(
                        taskId = task.id,
                        itemTitle = itemName,
                        serverId = activeServerId,
                        userId = activeUserId,
                        isPrimary = true,
                    )
                }
            }
        }
    }

    override fun observeActiveDownloads(): Flow<List<ActiveDownloadEntry>> {
        return combine(
            database.observeActiveDownloadTasks(),
            sessionActivationCoordinator.sessionState,
        ) { tasks, session ->
            val activeServerId = session.activeServerId
            if (activeServerId.isNullOrBlank()) {
                speedTracker.clear()
                return@combine emptyList()
            }
            val scopedTasks = tasks.filter { database.resolveDownloadTaskServerId(it) == activeServerId }
            val now = System.currentTimeMillis()
            val activeIds = scopedTasks.map { it.id }.toSet()
            speedTracker.keys.retainAll(activeIds)
            scopedTasks.map { task ->
                val name = database.getMovieOrNull(task.itemId)?.name
                    ?: database.getEpisodeOrNull(task.itemId)?.name
                    ?: "Unknown"
                val speed: Long? = speedTracker[task.id]?.let { (prevBytes, prevTime) ->
                    val elapsed = now - prevTime
                    if (elapsed > 0L && task.bytesDownloaded > prevBytes) {
                        ((task.bytesDownloaded - prevBytes) * 1000L) / elapsed
                    } else null
                }
                speedTracker[task.id] = task.bytesDownloaded to now
                ActiveDownloadEntry(
                    taskId = task.id,
                    itemId = task.itemId,
                    itemName = name,
                    progress = task.progress,
                    status = task.status,
                    errorMessage = task.errorMessage,
                    bytesDownloaded = task.bytesDownloaded,
                    totalBytes = task.totalBytes,
                    downloadSpeedBytesPerSec = speed,
                )
            }
        }.flowOn(Dispatchers.IO)
    }

    override suspend fun getStorageUsedBytes(): Long =
        downloadStorageManager.computeTotalDownloadsSize()

    override suspend fun scanOrphanedDownloads(): OrphanedDownloadsReport =
        downloadStorageManager.scanOrphanedFiles()

    override suspend fun cleanOrphanedDownloads(): OrphanedDownloadCleanupResult =
        downloadStorageManager.cleanOrphanedFiles()

    override fun verifyDownloads() {
        if (!isLocked()) {
            DownloadIntegrityWorker.enqueue(workManager)
        }
    }

    override suspend fun verifyDownloadIntegrity(): DownloadVerificationReport =
        withContext(Dispatchers.IO) {
            if (isLocked()) {
                return@withContext DownloadVerificationReport(0, 0, 0, 0)
            }
            val session = sessionActivationCoordinator.ensureInitialized()
            val activeServerId = session.activeServerId
            val activeUserId = session.activeUserId
            val expectedEpoch = session.sessionEpoch
            if (session.sessionMode != SessionMode.SERVER_AUTHENTICATED ||
                sessionActivationCoordinator.isActivating.get() ||
                activeServerId.isNullOrBlank() ||
                activeUserId == null ||
                isLocked()
            ) {
                return@withContext DownloadVerificationReport(0, 0, 0, 0)
            }

            val tasks =
                (database.getCompletedDownloadTasks(DownloadTaskKind.PRIMARY) +
                    database.getCompletedDownloadTasks(DownloadTaskKind.SUBTITLE))
                    .filter { database.resolveDownloadTaskServerId(it) == activeServerId }
            var checked = 0
            var intact = 0
            var broken = 0
            var requeued = 0
            val details = mutableListOf<String>()

            for (task in tasks) {
                if (!isVerificationSessionValid(expectedEpoch, activeServerId)) break
                if (task.finalPath.isBlank()) continue
                if (task.isEncrypted && contentKeyManager.getDekOrNull()?.also { it.fill(0) } == null) {
                    continue
                }
                checked++

                val finalFile = File(task.finalPath)
                val exists = finalFile.exists()
                val length = if (exists) finalFile.length() else 0L
                val expected = task.totalBytes

                val isMissing = !exists || length == 0L
                val isTranscodedCompleted =
                    !isMissing &&
                        (task.finalPath.endsWith("_transcoded.mp4", ignoreCase = true) ||
                            task.requestUrl.contains("Static=false", ignoreCase = true)) &&
                        (length == task.bytesDownloaded ||
                            (task.status == DownloadManager.STATUS_SUCCESSFUL &&
                                (task.tempPath.isBlank() || !File(task.tempPath).exists())))
                if (isTranscodedCompleted && expected != null && expected != length) {
                    if (!isVerificationSessionValid(expectedEpoch, activeServerId)) break
                    database.updateDownloadTask(
                        id = task.id,
                        downloadId = task.downloadId,
                        bytesDownloaded = length,
                        totalBytes = length,
                        eTag = task.eTag,
                        lastModified = task.lastModified,
                        status = task.status,
                        progress = 100,
                        errorMessage = null,
                        updatedAt = System.currentTimeMillis(),
                    )
                    if (task.kind == DownloadTaskKind.PRIMARY) {
                        database.setSourcePath(task.sourceId, task.finalPath)
                    }
                }
                val isSizeMismatch = !isTranscodedCompleted && expected != null && expected > 0L && length != expected
                val isCorrupt = if (!isMissing && !isSizeMismatch && task.kind == DownloadTaskKind.PRIMARY && !task.isEncrypted) {
                    checkMediaFileCorrupted(finalFile)
                } else {
                    false
                }

                if (isMissing || isSizeMismatch || isCorrupt) {
                    broken++
                    val reason = when {
                        isMissing -> "File missing or 0 bytes"
                        isSizeMismatch -> "Size mismatch ($length vs expected $expected)"
                        else -> "Corrupt media container (unreadable tracks)"
                    }
                    details.add("${finalFile.name}: $reason")
                    Timber.w("verifyDownloadIntegrity: broken task %s (%s)", task.id, reason)
                    runCatching {
                        if (requeueBrokenTask(task, expectedEpoch, activeServerId, activeUserId)) {
                            requeued++
                        }
                    }.onFailure {
                        Timber.e(it, "verifyDownloadIntegrity: failed to requeue task %s", task.id)
                    }
                } else {
                    intact++
                }
            }

            DownloadVerificationReport(
                totalChecked = checked,
                intactCount = intact,
                missingOrCorruptedCount = broken,
                requeuedForRepairCount = requeued,
                details = details,
            )
        }

    override suspend fun countUnencryptedDownloads(): Int =
        withContext(Dispatchers.IO) {
            if (isLocked()) return@withContext 0
            val activeServerId = sessionActivationCoordinator.ensureInitialized().activeServerId
            if (activeServerId.isNullOrBlank() || isLocked()) return@withContext 0
            database.getUnencryptedDownloads()
                .count { database.resolveDownloadTaskServerId(it) == activeServerId }
        }

    override suspend fun deleteUnencryptedDownloads(): Int =
        withContext(Dispatchers.IO) {
            if (isLocked()) return@withContext 0
            val session = sessionActivationCoordinator.ensureInitialized()
            val activeServerId = session.activeServerId
            if (activeServerId.isNullOrBlank() || isLocked()) return@withContext 0

            val tasks = database.getUnencryptedDownloads()
                .filter { database.resolveDownloadTaskServerId(it) == activeServerId }
            if (tasks.isEmpty()) return@withContext 0

            for (task in tasks) {
                workManager.cancelUniqueWork(ResumableDownloadWorker.uniqueWorkName(task.id))
                workManager.cancelUniqueWork(DownloadPreparationWorker.uniqueWorkName(task.id))
                activeDownloadRegistry.cancelByTaskId(task.id)
            }

            var deletedCount = 0
            activeDownloadRegistry.commitMutex.withLock {
                if (isLocked()) return@withLock
                for (task in tasks) {
                    downloadStorageManager.markDownloadDeleted(task.itemId)
                    if (task.finalPath.isNotBlank()) {
                        downloadStorageManager.deletePhysicalFileAndMediaStore(task.finalPath)
                    }
                    if (task.tempPath.isNotBlank()) {
                        downloadStorageManager.deletePhysicalFileAndMediaStore(task.tempPath)
                    }
                    DownloadStagingPaths.privateStagingFile(context, task.id).takeIf(File::exists)?.delete()

                    when (task.kind) {
                        DownloadTaskKind.PRIMARY -> {
                            database.getMediaStreamsBySourceId(task.sourceId).forEach { stream ->
                                if (stream.path.isNotBlank()) {
                                    downloadStorageManager.deletePhysicalFileAndMediaStore(stream.path)
                                }
                                database.deleteMediaStream(stream.id)
                            }
                            database.deleteSource(task.sourceId)
                        }
                        DownloadTaskKind.SUBTITLE -> {
                            task.mediaStreamId?.let { database.deleteMediaStream(it) }
                        }
                    }
                    database.deleteDownloadTasksByIds(listOf(task.id))
                    deletedCount++
                }
            }
            deletedCount
        }

    private fun isVerificationSessionValid(expectedEpoch: Long, activeServerId: String): Boolean {
        if (isLocked()) return false
        val snap = sessionActivationCoordinator.sessionState.value
        return snap.sessionMode == SessionMode.SERVER_AUTHENTICATED &&
            !sessionActivationCoordinator.isActivating.get() &&
            snap.sessionEpoch == expectedEpoch &&
            snap.activeServerId == activeServerId
    }

    private fun cleanupTaskStagingFiles(tasks: List<DownloadTaskDto>) {
        for (task in tasks) {
            if (task.tempPath.isNotBlank()) {
                runCatching { File(task.tempPath).delete() }
            }
            if (task.finalPath.isNotBlank()) {
                runCatching { File("${task.finalPath}.enc.tmp").delete() }
            }
            runCatching { DownloadStagingPaths.privateStagingFile(context, task.id).delete() }
        }
    }

    private fun checkMediaFileCorrupted(file: File): Boolean {
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            extractor.trackCount == 0
        } catch (_: Exception) {
            true
        } finally {
            runCatching { extractor.release() }
        }
    }

    private suspend fun requeueBrokenTask(
        task: DownloadTaskDto,
        expectedEpoch: Long,
        activeServerId: String,
        activeUserId: UUID,
    ): Boolean {
        var enqueued = false
        activeDownloadRegistry.commitMutex.withLock {
            if (!isVerificationSessionValid(expectedEpoch, activeServerId)) return false
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
                errorMessage = "Integrity check failed — re-downloading",
                updatedAt = now,
            )

            File(task.finalPath).takeIf(File::exists)?.delete()
            File("${task.finalPath}.enc.tmp").takeIf(File::exists)?.delete()
            if (task.tempPath.isNotBlank()) {
                File(task.tempPath).takeIf(File::exists)?.delete()
            }
            DownloadStagingPaths.privateStagingFile(context, task.id).takeIf(File::exists)?.delete()
            enqueued = true
        }

        if (enqueued && isVerificationSessionValid(expectedEpoch, activeServerId)) {
            val title = database.getMovieOrNull(task.itemId)?.name
                ?: database.getEpisodeOrNull(task.itemId)?.name
            enqueueResumableDownload(
                taskId = task.id,
                itemTitle = title,
                serverId = activeServerId,
                userId = activeUserId,
                isPrimary = task.kind == DownloadTaskKind.PRIMARY,
            )
            return true
        }
        return false
    }

    private fun enqueuePreparationWorker(
        taskId: String,
        itemId: UUID,
        request: DownloadRequest,
        serverId: String,
        userId: UUID,
    ) {
        val networkType =
            if (appPreferences.getValue(appPreferences.downloadOverMobileData)) {
                NetworkType.CONNECTED
            } else {
                NetworkType.UNMETERED
            }
        val inputBuilder = androidx.work.Data.Builder()
            .putString(DownloadPreparationWorker.KEY_TASK_ID, taskId)
            .putString(DownloadPreparationWorker.KEY_ITEM_ID, itemId.toString())
            .putString(DownloadPreparationWorker.KEY_SOURCE_ID, request.sourceId)
            .putString(DownloadPreparationWorker.KEY_MODE, request.mode.name)
        request.videoBitrate?.let {
            inputBuilder.putInt(DownloadPreparationWorker.KEY_VIDEO_BITRATE, it)
        }
        request.audioStreamIndex?.let {
            inputBuilder.putInt(DownloadPreparationWorker.KEY_AUDIO_STREAM_INDEX, it)
        }
        request.subtitleStreamIndex?.let {
            inputBuilder.putInt(DownloadPreparationWorker.KEY_SUBTITLE_STREAM_INDEX, it)
        }
        request.subtitleDeliveryMethod?.let {
            inputBuilder.putString(DownloadPreparationWorker.KEY_SUBTITLE_DELIVERY_METHOD, it.name)
        }
        val workRequest =
            OneTimeWorkRequestBuilder<DownloadPreparationWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(inputBuilder.build())
                .addTag("download-server:$serverId")
                .addTag("download-user:$userId")
                .addTag("download-primary")
                .build()
        workManager.enqueueUniqueWork(
            DownloadPreparationWorker.uniqueWorkName(taskId),
            ExistingWorkPolicy.REPLACE,
            workRequest,
        )
    }

    private fun enqueueResumableDownload(
        taskId: String,
        itemTitle: String? = null,
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
        Timber.i(
            "Enqueue resumable download taskId=%s networkType=%s title=%s",
            taskId,
            networkType,
            itemTitle,
        )
        val inputData = workDataOf(
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
            ExistingWorkPolicy.REPLACE,
            requestBuilder.build(),
        )
    }
}
