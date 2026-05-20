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
import dev.jdtech.jellyfin.downloads.DownloadStorageManager
import dev.jdtech.jellyfin.models.BulkDownloadSettings
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
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.work.DownloadIntegrityWorker
import dev.jdtech.jellyfin.work.DownloadPreparationWorker
import dev.jdtech.jellyfin.work.ResumableDownloadWorker
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import timber.log.Timber

class DownloaderImpl(
    private val context: Context,
    private val database: ServerDatabaseDao,
    private val jellyfinRepository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    private val workManager: WorkManager,
    private val downloadStorageManager: DownloadStorageManager,
) : Downloader {
    private val downloadManager = context.getSystemService(DownloadManager::class.java)
    // taskId -> (bytesDownloaded, timestampMs) for speed calculation
    private val speedTracker = mutableMapOf<String, Pair<Long, Long>>()

    override suspend fun downloadItem(
        item: SpatialFinItem,
        request: DownloadRequest,
    ): UiText? {
        return try {
            val taskId = downloadTaskId(item.id, request.sourceId)
            val existingTask = database.getDownloadTaskById(taskId)
            // Insert (or refresh) a placeholder row immediately so the screen's
            // observeDownloadStatus flow sees the queued state. The
            // DownloadPreparationWorker will fill in the real fields (paths,
            // request URL, totalBytes) and then enqueue ResumableDownloadWorker.
            database.insertDownloadTask(
                DownloadTaskDto(
                    id = taskId,
                    itemId = item.id,
                    sourceId = request.sourceId,
                    kind = DownloadTaskKind.PRIMARY,
                    mediaStreamId = null,
                    downloadId = null,
                    requestUrl = existingTask?.requestUrl.orEmpty(),
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
            enqueuePreparationWorker(taskId, item.id, request)
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
        // Pre-flight storage check
        val storageShortfall = run {
            val pendingEpisodes = episodes.filter { !it.isDownloaded() }
            val estimatedBytes = pendingEpisodes.sumOf { ep ->
                ep.sources.firstOrNull { it.type == SpatialFinSourceType.REMOTE }?.size ?: 0L
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
                pendingEpisodeIds.add(episode.id.toString())
            }
        }
        
        if (pendingEpisodeIds.isNotEmpty()) {
            val inputBuilder = androidx.work.Data.Builder()
                .putStringArray(dev.jdtech.jellyfin.work.BulkDownloadResolutionWorker.KEY_ITEM_IDS, pendingEpisodeIds.toTypedArray())
                .putString(dev.jdtech.jellyfin.work.BulkDownloadResolutionWorker.KEY_MODE, settings.mode.name)
            settings.videoBitrate?.let {
                inputBuilder.putInt(dev.jdtech.jellyfin.work.BulkDownloadResolutionWorker.KEY_VIDEO_BITRATE, it)
            }
            
            val workRequest = androidx.work.OneTimeWorkRequestBuilder<dev.jdtech.jellyfin.work.BulkDownloadResolutionWorker>()
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .setInputData(inputBuilder.build())
                .build()
                
            workManager.enqueue(workRequest)
        }
        
        return BulkDownloadResult(queued = pendingEpisodeIds.size, skipped = skipped, failed = 0, storageShortfallBytes = storageShortfall)
    }

    override suspend fun cancelDownload(item: SpatialFinItem) {
        database.getDownloadTasksByItemId(item.id).forEach { task ->
            workManager.cancelUniqueWork(ResumableDownloadWorker.uniqueWorkName(task.id))
            workManager.cancelUniqueWork(DownloadPreparationWorker.uniqueWorkName(task.id))
        }
        val source =
            item.sources.firstOrNull { it.type == SpatialFinSourceType.LOCAL }
                ?: database.getSources(item.id)
                    .firstOrNull { it.type == SpatialFinSourceType.LOCAL }
                    ?.toSpatialFinSource(database)
        if (source != null) {
            deleteItem(item, source)
        } else {
            database.deleteDownloadTasksByItemId(item.id)
        }
    }

    override suspend fun deleteItem(item: SpatialFinItem, source: SpatialFinSource) {
        database.getDownloadTasksByItemId(item.id).forEach { task ->
            workManager.cancelUniqueWork(ResumableDownloadWorker.uniqueWorkName(task.id))
            workManager.cancelUniqueWork(DownloadPreparationWorker.uniqueWorkName(task.id))
        }
        source.downloadId?.let { downloadManager.remove(it) }
        database.getMediaStreamsBySourceId(source.id)
            .mapNotNull { it.downloadId }
            .forEach { downloadManager.remove(it) }
        database.deleteDownloadTask(item.id, source.id)
        downloadStorageManager.deleteItem(item, source, deletePhysicalFile = true)
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
        val item: SpatialFinItem? = userId?.let {
            database.getMovieOrNull(itemId)?.toSpatialFinMovie(database, it)
                ?: database.getEpisodeOrNull(itemId)?.toSpatialFinEpisode(database, it)
        }
        if (item != null) {
            cancelDownload(item)
        } else {
            database.getDownloadTasksByItemId(itemId).forEach { task ->
                workManager.cancelUniqueWork(ResumableDownloadWorker.uniqueWorkName(task.id))
                workManager.cancelUniqueWork(DownloadPreparationWorker.uniqueWorkName(task.id))
            }
            database.deleteDownloadTasksByItemId(itemId)
        }
    }

    override suspend fun pauseDownload(item: SpatialFinItem) = pauseDownloadById(item.id)

    override suspend fun pauseDownloadById(itemId: UUID) {
        val tasks = database.getDownloadTasksByItemId(itemId)
            .filter { it.status != DownloadManager.STATUS_SUCCESSFUL }
        for (task in tasks) {
            workManager.cancelUniqueWork(ResumableDownloadWorker.uniqueWorkName(task.id))
            workManager.cancelUniqueWork(DownloadPreparationWorker.uniqueWorkName(task.id))
            database.updateDownloadTask(
                id = task.id,
                downloadId = task.downloadId,
                bytesDownloaded = task.bytesDownloaded,
                totalBytes = task.totalBytes,
                eTag = task.eTag,
                lastModified = task.lastModified,
                status = DownloadManager.STATUS_PAUSED,
                progress = task.progress,
                errorMessage = "Paused",
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    override suspend fun resumeDownload(item: SpatialFinItem) = resumeDownloadById(item.id)

    override suspend fun resumeDownloadById(itemId: UUID) {
        val tasks = database.getDownloadTasksByItemId(itemId)
            .filter { it.status == DownloadManager.STATUS_PAUSED || it.status == DownloadManager.STATUS_FAILED }
        for (task in tasks) {
            if (task.kind == DownloadTaskKind.PRIMARY) {
                val itemName = database.getMovieOrNull(task.itemId)?.name
                    ?: database.getEpisodeOrNull(task.itemId)?.name
                enqueueResumableDownload(task.id, itemTitle = itemName)
            }
        }
    }

    override fun observeActiveDownloads(): Flow<List<ActiveDownloadEntry>> {
        return database.observeActiveDownloadTasks().map { tasks ->
            val now = System.currentTimeMillis()
            // Remove stale entries for completed tasks
            val activeIds = tasks.map { it.id }.toSet()
            speedTracker.keys.retainAll(activeIds)
            tasks.map { task ->
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

    override fun verifyDownloads() {
        DownloadIntegrityWorker.enqueue(workManager)
    }

    private fun enqueuePreparationWorker(
        taskId: String,
        itemId: UUID,
        request: DownloadRequest,
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
                .build()
        workManager.enqueueUniqueWork(
            DownloadPreparationWorker.uniqueWorkName(taskId),
            ExistingWorkPolicy.REPLACE,
            workRequest,
        )
    }

    private fun enqueueResumableDownload(taskId: String, itemTitle: String? = null) {
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
        val request =
            OneTimeWorkRequestBuilder<ResumableDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .setInputData(inputData)
                .build()
        workManager.enqueueUniqueWork(
            ResumableDownloadWorker.uniqueWorkName(taskId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
