package dev.jdtech.jellyfin.utils

import android.app.DownloadManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import androidx.core.net.toUri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderState
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.downloads.DownloadStorageManager
import dev.jdtech.jellyfin.models.BulkDownloadSettings
import dev.jdtech.jellyfin.models.DownloadMode
import dev.jdtech.jellyfin.models.DownloadRequest
import dev.jdtech.jellyfin.models.DownloadTaskKind
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinSourceType
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinMediaStream
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.SpatialFinSources
import dev.jdtech.jellyfin.models.SpatialFinTrickplayInfo
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.downloadTaskId
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.models.subtitleDownloadTaskId
import dev.jdtech.jellyfin.models.toSpatialFinEpisodeDto
import dev.jdtech.jellyfin.models.toSpatialFinMediaStreamDto
import dev.jdtech.jellyfin.models.toSpatialFinEpisode
import dev.jdtech.jellyfin.models.toSpatialFinMovie
import dev.jdtech.jellyfin.models.toSpatialFinMovieDto
import dev.jdtech.jellyfin.models.toSpatialFinSeasonDto
import dev.jdtech.jellyfin.models.toSpatialFinSegmentsDto
import dev.jdtech.jellyfin.models.toSpatialFinShowDto
import dev.jdtech.jellyfin.models.toSpatialFinSource
import dev.jdtech.jellyfin.models.toSpatialFinSourceDto
import dev.jdtech.jellyfin.models.toSpatialFinTrickplayInfoDto
import dev.jdtech.jellyfin.models.toSpatialFinUserDataDto
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.work.ImagesDownloaderWorker
import dev.jdtech.jellyfin.work.ResumableDownloadWorker
import android.os.StatFs
import java.io.File
import java.util.UUID
import kotlin.Exception
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import timber.log.Timber

class DownloaderImpl(
    private val context: Context,
    private val database: ServerDatabaseDao,
    private val jellyfinApi: JellyfinApi,
    private val jellyfinRepository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    private val workManager: WorkManager,
    private val downloadStorageManager: DownloadStorageManager,
) : Downloader {
    private val downloadManager = context.getSystemService(DownloadManager::class.java)
    // taskId -> (bytesDownloaded, timestampMs) for speed calculation
    private val speedTracker = mutableMapOf<String, Pair<Long, Long>>()

    // TODO: We should probably move most (if not all) code to a worker.
    //  At this moment it is possible that some things are not downloaded due to the user leaving
    //  the current screen
    override suspend fun downloadItem(
        item: SpatialFinItem,
        request: DownloadRequest,
    ): UiText? {
        try {
            val source = jellyfinRepository.getMediaSources(item.id, true)
                .firstOrNull { it.id == request.sourceId }
                ?: return UiText.StringResource(CoreR.string.unknown_error)
            val segments = jellyfinRepository.getSegments(item.id)
            val trickplayInfo =
                if (item is SpatialFinSources) {
                    item.trickplayInfo?.get(request.sourceId)
                } else {
                    null
                }
            val downloadsRoot = downloadStorageManager.ensureDownloadsRoot()
            if (!downloadsRoot.exists() && !downloadsRoot.mkdirs()) {
                return UiText.StringResource(CoreR.string.storage_unavailable)
            }
            val targetFile = buildTargetFile(item, source, request)
            val finalPath = downloadStorageManager.completedPathFor(targetFile.path)
            val path = Uri.fromFile(targetFile)
            val stats = android.os.StatFs(downloadsRoot.path)
            if (stats.availableBytes < source.size) {
                return UiText.StringResource(
                    CoreR.string.not_enough_storage,
                    Formatter.formatFileSize(context, source.size),
                    Formatter.formatFileSize(context, stats.availableBytes),
                )
            }
            val requestUrl = buildDownloadUrl(item, source, request)
            val taskId = downloadTaskId(item.id, source.id)
            val currentServerId = appPreferences.getValue(appPreferences.currentServer)
            val accessToken = currentServerId?.let { database.getServerCurrentUser(it)?.accessToken }
            val existingTask = database.getDownloadTaskById(taskId)
            val waitingReason = currentNetworkRestrictionMessage()
            Timber.i(
                "Download enqueue requested itemId=%s sourceId=%s mode=%s allowMetered=%s allowRoaming=%s waitingReason=%s target=%s",
                item.id,
                source.id,
                request.mode,
                appPreferences.getValue(appPreferences.downloadOverMobileData),
                appPreferences.getValue(appPreferences.downloadWhenRoaming),
                waitingReason,
                targetFile.path,
            )
            upsertDownloadTask(
                taskId = taskId,
                itemId = item.id,
                sourceId = source.id,
                kind = DownloadTaskKind.PRIMARY,
                mediaStreamId = null,
                downloadId = null,
                requestUrl = requestUrl,
                accessToken = accessToken,
                tempPath = targetFile.path,
                finalPath = finalPath,
                bytesDownloaded = targetFile.takeIf(File::exists)?.length() ?: 0L,
                totalBytes = source.size.takeIf { it > 0L },
                eTag = existingTask?.eTag,
                lastModified = existingTask?.lastModified,
                status = if (waitingReason == null) DownloadManager.STATUS_PENDING else DownloadManager.STATUS_PAUSED,
                progress = existingTask?.progress ?: 0,
                errorMessage = waitingReason,
            )

            when (item) {
                is SpatialFinMovie -> {
                    database.insertMovie(
                        item.toSpatialFinMovieDto(
                            appPreferences.getValue(appPreferences.currentServer)
                        )
                    )
                }
                is SpatialFinEpisode -> {
                    val show = jellyfinRepository.getShow(item.seriesId)
                    database.insertShow(
                        show.toSpatialFinShowDto(
                            appPreferences.getValue(appPreferences.currentServer)
                        )
                    )
                    val season = jellyfinRepository.getSeason(item.seasonId)
                    database.insertSeason(season.toSpatialFinSeasonDto())
                    database.insertEpisode(
                        item.toSpatialFinEpisodeDto(
                            appPreferences.getValue(appPreferences.currentServer)
                        )
                    )

                    startImagesDownloader(show)
                    startImagesDownloader(season)
                }
            }

            val sourceDto = source.toSpatialFinSourceDto(item.id, path.path.orEmpty())

            database.insertSource(sourceDto.copy(downloadId = null))
            database.insertUserData(item.toSpatialFinUserDataDto(jellyfinRepository.getUserId()))

            if (request.mode == DownloadMode.ORIGINAL) {
                downloadExternalMediaStreams(item, source)
            }

            segments.forEach { database.insertSegment(it.toSpatialFinSegmentsDto(item.id)) }

            if (trickplayInfo != null) {
                downloadTrickplayData(item.id, request.sourceId, trickplayInfo)
            }

            startImagesDownloader(item)
            enqueueResumableDownload(taskId, itemTitle = item.name)
            return null
        } catch (e: Exception) {
            try {
                database.getSources(item.id)
                    .firstOrNull { it.id == request.sourceId }
                    ?.toSpatialFinSource(database)
                    ?.let { deleteItem(item, it) }
            } catch (_: Exception) {}
            Timber.e(e)
            return if (e.message != null) UiText.DynamicString(e.message!!)
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

        var queued = 0
        var skipped = 0
        var failed = 0
        for (episode in episodes) {
            if (episode.isDownloaded()) {
                skipped++
                continue
            }
            val sources = runCatching { jellyfinRepository.getMediaSources(episode.id, true) }
                .getOrNull()
            val source = sources?.firstOrNull { it.type == SpatialFinSourceType.REMOTE }
            if (source == null) {
                Timber.w("downloadItems: no remote source found for episode %s, skipping", episode.id)
                failed++
                continue
            }
            val request = DownloadRequest(
                sourceId = source.id,
                mode = settings.mode,
                videoBitrate = settings.videoBitrate,
            )
            val error = downloadItem(episode, request)
            if (error != null) {
                Timber.w("downloadItems: failed to queue episode %s: %s", episode.id, error)
                failed++
            } else {
                queued++
            }
        }
        return BulkDownloadResult(queued = queued, skipped = skipped, failed = failed, storageShortfallBytes = storageShortfall)
    }

    override suspend fun cancelDownload(item: SpatialFinItem) {
        database.getDownloadTasksByItemId(item.id).forEach { task ->
            workManager.cancelUniqueWork(ResumableDownloadWorker.uniqueWorkName(task.id))
        }
        val source =
            item.sources.firstOrNull { it.type == dev.jdtech.jellyfin.models.SpatialFinSourceType.LOCAL }
                ?: database.getSources(item.id)
                    .firstOrNull { it.type == dev.jdtech.jellyfin.models.SpatialFinSourceType.LOCAL }
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
        }
        source.downloadId?.let { downloadManager.remove(it) }
        database.getMediaStreamsBySourceId(source.id)
            .mapNotNull { it.downloadId }
            .forEach { downloadManager.remove(it) }
        database.deleteDownloadTask(item.id, source.id)
        downloadStorageManager.deleteItem(item, source, deletePhysicalFile = true)
    }

    private fun buildTargetFile(
        item: SpatialFinItem,
        source: SpatialFinSource,
        request: DownloadRequest,
    ): File {
        return when (request.mode) {
            DownloadMode.ORIGINAL -> {
                val extension = inferExtensionFromPath(source.path, fallback = "mkv")
                downloadStorageManager.buildTargetFile(item, source, "original", extension)
            }
            DownloadMode.TRANSCODED ->
                downloadStorageManager.buildTargetFile(item, source, "transcoded", "mp4")
        }
    }

    private fun buildDownloadUrl(
        item: SpatialFinItem,
        source: SpatialFinSource,
        request: DownloadRequest,
    ): String {
        if (request.mode == DownloadMode.ORIGINAL) {
            return source.path
        }
        return jellyfinApi.videosApi
            .getVideoStreamUrl(
                itemId = item.id,
                container = "mp4",
                static = true,
                mediaSourceId = source.id,
                audioCodec = "aac",
                allowVideoStreamCopy = false,
                allowAudioStreamCopy = false,
                videoBitRate = request.videoBitrate,
                subtitleStreamIndex = request.subtitleStreamIndex,
                subtitleMethod = request.subtitleDeliveryMethod ?: SubtitleDeliveryMethod.DROP,
                audioStreamIndex = request.audioStreamIndex,
                context = EncodingContext.STREAMING,
                enableAudioVbrEncoding = true,
            )
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

    private fun downloadExternalMediaStreams(
        item: SpatialFinItem,
        source: SpatialFinSource,
    ) {
        // Include both external and embedded subtitle tracks. Jellyfin exposes a deliveryUrl for
        // every text stream, so pre-downloading them lets offline playback sideload subtitles
        // without relying on MatroskaExtractor's buggy ContentEncoding decompression path.
        for (mediaStream in source.mediaStreams.filter {
            it.type == MediaStreamType.SUBTITLE && !it.path.isNullOrBlank()
        }) {
            val id = UUID.randomUUID()
            val streamFile =
                downloadStorageManager.buildTargetFile(
                    item = item,
                    source = source,
                    modeSuffix = "subtitle_${id}",
                    extension = inferMediaStreamExtension(mediaStream),
                    inProgress = true,
                )
            val streamPath = Uri.fromFile(streamFile)
            database.insertMediaStream(
                mediaStream.toSpatialFinMediaStreamDto(id, source.id, streamPath.path.orEmpty())
            )
            val taskId = subtitleDownloadTaskId(item.id, id)
            val existingTask = database.getDownloadTaskById(taskId)
            val currentServerId = appPreferences.getValue(appPreferences.currentServer)
            val accessToken = currentServerId?.let { database.getServerCurrentUser(it)?.accessToken }
            upsertDownloadTask(
                taskId = taskId,
                itemId = item.id,
                sourceId = source.id,
                kind = DownloadTaskKind.SUBTITLE,
                mediaStreamId = id,
                downloadId = null,
                requestUrl = mediaStream.path!!,
                accessToken = accessToken,
                tempPath = streamPath.path.orEmpty(),
                finalPath = downloadStorageManager.completedPathFor(streamPath.path.orEmpty()),
                bytesDownloaded = streamFile.takeIf(File::exists)?.length() ?: 0L,
                totalBytes = existingTask?.totalBytes,
                eTag = existingTask?.eTag,
                lastModified = existingTask?.lastModified,
                status = DownloadManager.STATUS_PENDING,
                progress = existingTask?.progress ?: 0,
                errorMessage = null,
            )
            enqueueResumableDownload(taskId, itemTitle = item.name)
        }
    }

    private fun inferMediaStreamExtension(mediaStream: SpatialFinMediaStream): String {
        val codec = mediaStream.codec.lowercase()
        return when {
            codec.isBlank() -> inferExtensionFromPath(mediaStream.path.orEmpty(), fallback = "srt")
            codec == "subrip" -> "srt"
            codec == "mov_text" -> "srt"
            else -> codec
        }
    }

    private fun inferExtensionFromPath(urlOrPath: String, fallback: String): String {
        val pathSegment =
            runCatching { Uri.parse(urlOrPath).lastPathSegment }
                .getOrNull()
                .orEmpty()
                .ifBlank { urlOrPath.substringBefore('?').substringAfterLast('/') }
        return pathSegment.substringAfterLast('.', "")
            .lowercase()
            .takeIf { it.isNotBlank() }
            ?: fallback.lowercase()
    }

    private suspend fun downloadTrickplayData(
        itemId: UUID,
        sourceId: String,
        trickplayInfo: SpatialFinTrickplayInfo,
    ) {
        val maxIndex =
            ceil(
                    trickplayInfo.thumbnailCount
                        .toDouble()
                        .div(trickplayInfo.tileWidth * trickplayInfo.tileHeight)
                )
                .toInt()
        val byteArrays = mutableListOf<ByteArray>()
        for (i in 0..maxIndex) {
            jellyfinRepository.getTrickplayData(itemId, trickplayInfo.width, i)?.let { byteArray ->
                byteArrays.add(byteArray)
            }
        }
        saveTrickplayData(itemId, sourceId, trickplayInfo, byteArrays)
    }

    private fun saveTrickplayData(
        itemId: UUID,
        sourceId: String,
        trickplayInfo: SpatialFinTrickplayInfo,
        byteArrays: List<ByteArray>,
    ) {
        val basePath = "trickplay/$itemId/$sourceId"
        database.insertTrickplayInfo(trickplayInfo.toSpatialFinTrickplayInfoDto(sourceId))
        File(context.filesDir, basePath).mkdirs()
        for ((i, byteArray) in byteArrays.withIndex()) {
            val file = File(context.filesDir, "$basePath/$i")
            file.writeBytes(byteArray)
        }
    }

    private fun startImagesDownloader(item: SpatialFinItem) {
        val images = item.images
        val urlEntries = listOfNotNull(
            images.primary?.toString()?.let { ImagesDownloaderWorker.KEY_URL_PRIMARY to it },
            images.backdrop?.toString()?.let { ImagesDownloaderWorker.KEY_URL_BACKDROP to it },
            images.logo?.toString()?.let { ImagesDownloaderWorker.KEY_URL_LOGO to it },
        )
        val downloadImagesRequest =
            OneTimeWorkRequestBuilder<ImagesDownloaderWorker>()
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setInputData(
                    workDataOf(
                        ImagesDownloaderWorker.KEY_ITEM_ID to item.id.toString(),
                        *urlEntries.toTypedArray(),
                    )
                )
                .build()

        workManager.enqueueUniqueWork(
            ImagesDownloaderWorker.uniqueWorkName(item.id),
            ExistingWorkPolicy.KEEP,
            downloadImagesRequest,
        )
    }

    private fun upsertDownloadTask(
        taskId: String,
        itemId: UUID,
        sourceId: String,
        kind: DownloadTaskKind,
        mediaStreamId: UUID?,
        downloadId: Long?,
        requestUrl: String,
        accessToken: String?,
        tempPath: String,
        finalPath: String,
        bytesDownloaded: Long,
        totalBytes: Long?,
        eTag: String?,
        lastModified: String?,
        status: Int,
        progress: Int,
        errorMessage: String?,
    ) {
        database.insertDownloadTask(
            dev.jdtech.jellyfin.models.DownloadTaskDto(
                id = taskId,
                itemId = itemId,
                sourceId = sourceId,
                kind = kind,
                mediaStreamId = mediaStreamId,
                downloadId = downloadId,
                requestUrl = requestUrl,
                accessToken = accessToken,
                tempPath = tempPath,
                finalPath = finalPath,
                bytesDownloaded = bytesDownloaded,
                totalBytes = totalBytes,
                eTag = eTag,
                lastModified = lastModified,
                status = status,
                progress = progress,
                errorMessage = errorMessage,
                updatedAt = System.currentTimeMillis(),
            )
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

    private fun currentNetworkRestrictionMessage(): String? {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivityManager.activeNetwork ?: return "Waiting for network"
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return "Waiting for network"

        if (!appPreferences.getValue(appPreferences.downloadOverMobileData) &&
            connectivityManager.isActiveNetworkMetered
        ) {
            return "Waiting for unmetered network"
        }

        if (!appPreferences.getValue(appPreferences.downloadWhenRoaming) &&
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        ) {
            return "Waiting for non-roaming network"
        }

        return null
    }
}
