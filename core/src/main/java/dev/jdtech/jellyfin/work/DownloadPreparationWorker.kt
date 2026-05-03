package dev.jdtech.jellyfin.work

import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
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
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.downloads.DownloadStorageManager
import dev.jdtech.jellyfin.models.DownloadMode
import dev.jdtech.jellyfin.models.DownloadRequest
import dev.jdtech.jellyfin.models.DownloadTaskDto
import dev.jdtech.jellyfin.models.DownloadTaskKind
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMediaStream
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.SpatialFinSources
import dev.jdtech.jellyfin.models.SpatialFinTrickplayInfo
import dev.jdtech.jellyfin.models.downloadTaskId
import dev.jdtech.jellyfin.models.subtitleDownloadTaskId
import dev.jdtech.jellyfin.models.toSpatialFinEpisodeDto
import dev.jdtech.jellyfin.models.toSpatialFinMediaStreamDto
import dev.jdtech.jellyfin.models.toSpatialFinMovieDto
import dev.jdtech.jellyfin.models.toSpatialFinSeasonDto
import dev.jdtech.jellyfin.models.toSpatialFinSegmentsDto
import dev.jdtech.jellyfin.models.toSpatialFinShowDto
import dev.jdtech.jellyfin.models.toSpatialFinSourceDto
import dev.jdtech.jellyfin.models.toSpatialFinTrickplayInfoDto
import dev.jdtech.jellyfin.models.toSpatialFinUserDataDto
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import timber.log.Timber

/**
 * Performs the network + database prep work for a download (fetching media
 * sources, segments, trickplay metadata, inserting item/source/user-data rows,
 * spawning subtitle and image workers) and finally enqueues the
 * [ResumableDownloadWorker] that streams the primary media file. Running this
 * inside WorkManager — instead of the Downloader's caller scope — means the
 * preparation survives the user navigating away from the screen that
 * initiated the download.
 */
@HiltWorker
class DownloadPreparationWorker
@AssistedInject
constructor(
    @Assisted private val appContext: Context,
    @Assisted private val params: WorkerParameters,
    private val database: ServerDatabaseDao,
    private val jellyfinApi: JellyfinApi,
    private val jellyfinRepository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    private val downloadStorageManager: DownloadStorageManager,
    private val workManager: WorkManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val taskId = params.inputData.getString(KEY_TASK_ID)
                ?: return@withContext Result.failure()
            val itemIdString = params.inputData.getString(KEY_ITEM_ID)
                ?: return@withContext failTask(taskId, "Missing item id")
            val sourceId = params.inputData.getString(KEY_SOURCE_ID)
                ?: return@withContext failTask(taskId, "Missing source id")
            val itemId = runCatching { UUID.fromString(itemIdString) }.getOrNull()
                ?: return@withContext failTask(taskId, "Invalid item id")

            val mode = runCatching {
                DownloadMode.valueOf(
                    params.inputData.getString(KEY_MODE) ?: DownloadMode.ORIGINAL.name,
                )
            }.getOrDefault(DownloadMode.ORIGINAL)
            val request = DownloadRequest(
                sourceId = sourceId,
                mode = mode,
                videoBitrate =
                    params.inputData.getInt(KEY_VIDEO_BITRATE, INT_UNSET).takeIf { it != INT_UNSET },
                audioStreamIndex =
                    params.inputData
                        .getInt(KEY_AUDIO_STREAM_INDEX, INT_UNSET)
                        .takeIf { it != INT_UNSET },
                subtitleStreamIndex =
                    params.inputData
                        .getInt(KEY_SUBTITLE_STREAM_INDEX, INT_UNSET)
                        .takeIf { it != INT_UNSET },
                subtitleDeliveryMethod =
                    params.inputData.getString(KEY_SUBTITLE_DELIVERY_METHOD)?.let { name ->
                        runCatching { SubtitleDeliveryMethod.valueOf(name) }.getOrNull()
                    },
            )

            val item = runCatching { jellyfinRepository.getItem(itemId) }
                .onFailure { Timber.e(it, "DownloadPreparationWorker: getItem failed itemId=%s", itemId) }
                .getOrNull()
                ?: return@withContext failTask(taskId, "Item not found")

            try {
                prepareDownload(taskId, item, request)
                Result.success()
            } catch (e: Exception) {
                Timber.e(e, "DownloadPreparationWorker: prep failed taskId=%s", taskId)
                failTask(taskId, e.message ?: "Download preparation failed")
                if (runAttemptCount < MAX_AUTO_RETRIES) Result.retry() else Result.failure()
            }
        }

    private suspend fun prepareDownload(
        taskId: String,
        item: SpatialFinItem,
        request: DownloadRequest,
    ) {
        val source = jellyfinRepository.getMediaSources(item.id, true)
            .firstOrNull { it.id == request.sourceId }
            ?: error("Media source ${request.sourceId} not found")
        val segments = jellyfinRepository.getSegments(item.id)
        val trickplayInfo = (item as? SpatialFinSources)?.trickplayInfo?.get(request.sourceId)

        val downloadsRoot = downloadStorageManager.ensureDownloadsRoot()
        if (!downloadsRoot.exists() && !downloadsRoot.mkdirs()) {
            error("Storage unavailable")
        }
        val targetFile = buildTargetFile(item, source, request)
        val finalPath = downloadStorageManager.completedPathFor(targetFile.path)
        val path = Uri.fromFile(targetFile)
        val stats = android.os.StatFs(downloadsRoot.path)
        if (stats.availableBytes < source.size) {
            error("Not enough storage for ${source.size} bytes (available ${stats.availableBytes})")
        }
        val requestUrl = buildDownloadUrl(item, source, request)
        val currentServerId = appPreferences.getValue(appPreferences.currentServer)
        val accessToken = currentServerId?.let { database.getServerCurrentUser(it)?.accessToken }
        val existingTask = database.getDownloadTaskById(taskId)
        val waitingReason = currentNetworkRestrictionMessage()
        Timber.i(
            "DownloadPreparationWorker: enqueue itemId=%s sourceId=%s mode=%s allowMetered=%s allowRoaming=%s waitingReason=%s target=%s",
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
            status =
                if (waitingReason == null) DownloadManager.STATUS_PENDING
                else DownloadManager.STATUS_PAUSED,
            progress = existingTask?.progress ?: 0,
            errorMessage = waitingReason,
        )

        when (item) {
            is SpatialFinMovie -> {
                database.insertMovie(
                    item.toSpatialFinMovieDto(
                        appPreferences.getValue(appPreferences.currentServer),
                    ),
                )
            }
            is SpatialFinEpisode -> {
                val show = jellyfinRepository.getShow(item.seriesId)
                database.insertShow(
                    show.toSpatialFinShowDto(
                        appPreferences.getValue(appPreferences.currentServer),
                    ),
                )
                val season = jellyfinRepository.getSeason(item.seasonId)
                database.insertSeason(season.toSpatialFinSeasonDto())
                database.insertEpisode(
                    item.toSpatialFinEpisodeDto(
                        appPreferences.getValue(appPreferences.currentServer),
                    ),
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
    }

    private fun failTask(taskId: String, message: String): Result {
        val task = database.getDownloadTaskById(taskId)
        if (task != null) {
            database.updateDownloadTask(
                id = taskId,
                downloadId = task.downloadId,
                bytesDownloaded = task.bytesDownloaded,
                totalBytes = task.totalBytes,
                eTag = task.eTag,
                lastModified = task.lastModified,
                status = DownloadManager.STATUS_FAILED,
                progress = task.progress,
                errorMessage = message,
                updatedAt = System.currentTimeMillis(),
            )
        }
        return Result.failure()
    }

    private fun buildTargetFile(
        item: SpatialFinItem,
        source: SpatialFinSource,
        request: DownloadRequest,
    ): File =
        when (request.mode) {
            DownloadMode.ORIGINAL -> {
                val extension = inferExtensionFromPath(source.path, fallback = "mkv")
                downloadStorageManager.buildTargetFile(item, source, "original", extension)
            }
            DownloadMode.TRANSCODED ->
                downloadStorageManager.buildTargetFile(item, source, "transcoded", "mp4")
        }

    private fun buildDownloadUrl(
        item: SpatialFinItem,
        source: SpatialFinSource,
        request: DownloadRequest,
    ): String {
        if (request.mode == DownloadMode.ORIGINAL) {
            return source.path
        }
        return jellyfinApi.videosApi.getVideoStreamUrl(
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

    private fun downloadExternalMediaStreams(
        item: SpatialFinItem,
        source: SpatialFinSource,
    ) {
        for (mediaStream in source.mediaStreams.filter {
            it.type == MediaStreamType.SUBTITLE && !it.path.isNullOrBlank()
        }) {
            val id = UUID.randomUUID()
            val streamFile = downloadStorageManager.buildTargetFile(
                item = item,
                source = source,
                modeSuffix = "subtitle_${id}",
                extension = inferMediaStreamExtension(mediaStream),
                inProgress = true,
            )
            val streamPath = Uri.fromFile(streamFile)
            database.insertMediaStream(
                mediaStream.toSpatialFinMediaStreamDto(id, source.id, streamPath.path.orEmpty()),
            )
            val taskId = subtitleDownloadTaskId(item.id, id)
            val existingTask = database.getDownloadTaskById(taskId)
            val currentServerId = appPreferences.getValue(appPreferences.currentServer)
            val accessToken =
                currentServerId?.let { database.getServerCurrentUser(it)?.accessToken }
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
        val pathSegment = runCatching { Uri.parse(urlOrPath).lastPathSegment }
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
        val maxIndex = ceil(
            trickplayInfo.thumbnailCount
                .toDouble()
                .div(trickplayInfo.tileWidth * trickplayInfo.tileHeight),
        ).toInt()
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
        File(appContext.filesDir, basePath).mkdirs()
        for ((i, byteArray) in byteArrays.withIndex()) {
            File(appContext.filesDir, "$basePath/$i").writeBytes(byteArray)
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
                    ),
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
            DownloadTaskDto(
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
            ),
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
            "DownloadPreparationWorker: enqueue resumable taskId=%s networkType=%s title=%s",
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
        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivityManager.activeNetwork ?: return "Waiting for network"
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            ?: return "Waiting for network"

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

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val KEY_ITEM_ID = "item_id"
        const val KEY_SOURCE_ID = "source_id"
        const val KEY_MODE = "mode"
        const val KEY_VIDEO_BITRATE = "video_bitrate"
        const val KEY_AUDIO_STREAM_INDEX = "audio_stream_index"
        const val KEY_SUBTITLE_STREAM_INDEX = "subtitle_stream_index"
        const val KEY_SUBTITLE_DELIVERY_METHOD = "subtitle_delivery_method"

        private const val INT_UNSET = Int.MIN_VALUE
        private const val MAX_AUTO_RETRIES = 3

        fun uniqueWorkName(taskId: String): String = "download-prep:$taskId"
    }
}
