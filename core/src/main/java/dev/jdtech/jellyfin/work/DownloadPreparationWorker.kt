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
import dev.jdtech.jellyfin.repository.JellyfinRepositoryImpl
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionMode
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.videoApi
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import timber.log.Timber

/**
 * Performs the network + database prep work for a download using an isolated worker-scoped
 * [JellyfinRepositoryImpl.createScopedForWorker] instance, gated on App Lock unlock and
 * [SessionActivationCoordinator] epoch/server/user identity.
 */
@HiltWorker
class DownloadPreparationWorker
@AssistedInject
constructor(
    @Assisted private val appContext: Context,
    @Assisted private val params: WorkerParameters,
    private val database: ServerDatabaseDao,
    private val jellyfinApi: JellyfinApi,
    private val jellyfinRepositoryImpl: JellyfinRepositoryImpl,
    private val appPreferences: AppPreferences,
    private val downloadStorageManager: DownloadStorageManager,
    private val workManager: WorkManager,
    private val sessionActivationCoordinator: SessionActivationCoordinator,
    private val appLockObserver: AppLockObserver,
) : CoroutineWorker(appContext, params) {

    private data class PrepSnapshot(
        val serverId: String,
        val userId: UUID,
        val serverAddress: String,
        val accessToken: String,
        val expectedSessionEpoch: Long,
        val scopedRepository: JellyfinRepository,
        val scopedApiClient: ApiClient,
    )

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

            if (isLocked()) {
                pauseTask(taskId, ResumableDownloadWorker.DEK_LOCKED_PAUSE_MESSAGE)
                return@withContext Result.failure()
            }

            val state = sessionActivationCoordinator.ensureInitialized()
            if (isLocked()) {
                pauseTask(taskId, ResumableDownloadWorker.DEK_LOCKED_PAUSE_MESSAGE)
                return@withContext Result.failure()
            }
            val serverId = state.activeServerId
            val userId = state.activeUserId
            val serverAddress = state.activeServerAddress
            val accessToken = userId?.let { database.getUser(it)?.accessToken }?.takeIf { it.isNotBlank() }

            if (state.sessionMode != SessionMode.SERVER_AUTHENTICATED ||
                sessionActivationCoordinator.isActivating.get() ||
                serverId.isNullOrBlank() ||
                userId == null ||
                serverAddress.isNullOrBlank() ||
                accessToken == null
            ) {
                pauseTask(taskId, "Sign in required to prepare download")
                return@withContext Result.failure()
            }

            val scopedRepo = jellyfinRepositoryImpl.createScopedForWorker(
                serverAddress = serverAddress,
                accessToken = accessToken,
                userId = userId,
            )
            val scopedApi = jellyfinApi.createApi(baseUrl = serverAddress, accessToken = accessToken)
            val snap = PrepSnapshot(
                serverId = serverId,
                userId = userId,
                serverAddress = serverAddress,
                accessToken = accessToken,
                expectedSessionEpoch = state.sessionEpoch,
                scopedRepository = scopedRepo,
                scopedApiClient = scopedApi,
            )

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

            if (!isStillValid(snap)) {
                pauseTask(taskId, pauseReason())
                return@withContext Result.failure()
            }

            val item =
                try {
                    snap.scopedRepository.getItem(itemId)
                        ?: return@withContext failTask(taskId, "Item not found")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "DownloadPreparationWorker: getItem failed itemId=%s", itemId)
                    if (!isStillValid(snap)) {
                        pauseTask(taskId, pauseReason())
                        return@withContext Result.failure()
                    }
                    return@withContext failTask(taskId, "Item not found")
                }

            try {
                val waitingReason = prepareDownload(taskId, item, request, snap)
                when {
                    !isStillValid(snap) -> {
                        pauseTask(taskId, pauseReason())
                        Result.failure()
                    }
                    waitingReason != null -> Result.retry()
                    else -> Result.success()
                }
            } catch (e: CancellationException) {
                if (isLocked()) {
                    pauseTask(taskId, ResumableDownloadWorker.DEK_LOCKED_PAUSE_MESSAGE)
                } else {
                    pauseTask(taskId, "Download paused")
                }
                throw e
            } catch (e: Exception) {
                if (!isStillValid(snap)) {
                    pauseTask(taskId, pauseReason())
                    return@withContext Result.failure()
                }
                Timber.e(e, "DownloadPreparationWorker: prep failed taskId=%s", taskId)
                failTask(taskId, e.message ?: "Download preparation failed")
                if (runAttemptCount < MAX_AUTO_RETRIES) Result.retry() else Result.failure()
            }
        }

    private suspend fun prepareDownload(
        taskId: String,
        item: SpatialFinItem,
        request: DownloadRequest,
        snap: PrepSnapshot,
    ): String? {
        if (!isStillValid(snap)) return null
        val source = snap.scopedRepository.getMediaSources(item.id, true)
            .firstOrNull { it.id == request.sourceId }
            ?: error("Media source ${request.sourceId} not found")

        if (!isStillValid(snap)) return null
        val segments = snap.scopedRepository.getSegments(item.id)
        val trickplayInfo = (item as? SpatialFinSources)?.trickplayInfo?.get(request.sourceId)

        val downloadsRoot = downloadStorageManager.ensureDownloadsRoot()
        if (!downloadsRoot.exists() && !downloadsRoot.mkdirs()) {
            error("Storage unavailable")
        }
        val targetFile = buildTargetFile(item, source, request)
        val finalPath = downloadStorageManager.completedPathFor(targetFile.path)
        val path = Uri.fromFile(targetFile)
        val stats = android.os.StatFs(downloadsRoot.path)

        val estimatedTranscodeBytes = if (request.mode == DownloadMode.TRANSCODED) {
            val durationSec = if (item.runtimeTicks > 0L) item.runtimeTicks / 10_000_000.0 else 5400.0
            val bps = (request.videoBitrate ?: DEFAULT_TRANSCODE_VIDEO_BITRATE) + 192_000
            val rawEstimate = (durationSec * (bps / 8.0)).toLong()
            if (source.size > 0L) rawEstimate.coerceAtMost(source.size) else rawEstimate
        } else null

        val requiredBytes = estimatedTranscodeBytes ?: source.size
        if (stats.availableBytes < requiredBytes) {
            error("Not enough storage for $requiredBytes bytes (available ${stats.availableBytes})")
        }
        val requestUrl = buildDownloadUrl(item, source, request, snap.scopedApiClient)
        val existingTask = database.getDownloadTaskById(taskId)
        val waitingReason = currentNetworkRestrictionMessage()

        if (!isStillValid(snap)) return null

        Timber.i(
            "DownloadPreparationWorker: enqueue itemId=%s sourceId=%s mode=%s waitingReason=%s",
            item.id,
            source.id,
            request.mode,
            waitingReason,
        )

        upsertDownloadTask(
            taskId = taskId,
            itemId = item.id,
            sourceId = source.id,
            kind = DownloadTaskKind.PRIMARY,
            mediaStreamId = null,
            downloadId = null,
            requestUrl = requestUrl,
            accessToken = snap.accessToken,
            tempPath = targetFile.path,
            finalPath = finalPath,
            bytesDownloaded = targetFile.takeIf(File::exists)?.length() ?: 0L,
            totalBytes = if (request.mode == DownloadMode.ORIGINAL) {
                source.size.takeIf { it > 0L }
            } else {
                estimatedTranscodeBytes
            },
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
                if (!isStillValid(snap)) return null
                database.upsertMovie(item.toSpatialFinMovieDto(snap.serverId))
            }
            is SpatialFinEpisode -> {
                if (!isStillValid(snap)) return null
                val show = snap.scopedRepository.getShow(item.seriesId)
                if (!isStillValid(snap)) return null
                database.upsertShow(show.toSpatialFinShowDto(snap.serverId))

                val season = snap.scopedRepository.getSeason(item.seasonId)
                if (!isStillValid(snap)) return null
                database.upsertSeason(season.toSpatialFinSeasonDto())
                database.upsertEpisode(item.toSpatialFinEpisodeDto(snap.serverId))

                startImagesDownloader(show, snap)
                startImagesDownloader(season, snap)
            }
        }

        if (!isStillValid(snap)) return null
        val sourceDto = source.toSpatialFinSourceDto(item.id, path.path.orEmpty())
        database.insertSource(sourceDto.copy(downloadId = null))
        database.insertUserData(item.toSpatialFinUserDataDto(snap.userId))

        if (waitingReason != null) {
            Timber.i("DownloadPreparationWorker: paused taskId=%s reason=%s", taskId, waitingReason)
            return waitingReason
        }

        if (request.mode == DownloadMode.ORIGINAL || request.subtitleStreamIndex == null) {
            downloadExternalMediaStreams(item, source, snap)
        }

        if (!isStillValid(snap)) return null
        segments.forEach { database.insertSegment(it.toSpatialFinSegmentsDto(item.id)) }

        if (trickplayInfo != null && isStillValid(snap)) {
            downloadTrickplayData(item.id, request.sourceId, trickplayInfo, snap)
        }

        if (!isStillValid(snap)) return null
        startImagesDownloader(item, snap)
        enqueueResumableDownload(
            taskId = taskId,
            itemTitle = item.name,
            snap = snap,
            isPrimary = true,
        )
        return null
    }

    private fun isLocked(): Boolean =
        appLockObserver.lockState.value == AppLockManager.LockState.LOCKED

    private fun isStillValid(snap: PrepSnapshot): Boolean {
        if (isStopped || isLocked()) return false
        val current = sessionActivationCoordinator.sessionState.value
        return current.sessionMode == SessionMode.SERVER_AUTHENTICATED &&
            !sessionActivationCoordinator.isActivating.get() &&
            current.sessionEpoch == snap.expectedSessionEpoch &&
            current.activeServerId == snap.serverId &&
            current.activeUserId == snap.userId
    }

    private fun pauseReason(): String =
        if (isLocked()) ResumableDownloadWorker.DEK_LOCKED_PAUSE_MESSAGE else "Download paused"

    private fun pauseTask(taskId: String, message: String) {
        val task = database.getDownloadTaskById(taskId) ?: return
        database.updateDownloadTask(
            id = taskId,
            downloadId = task.downloadId,
            bytesDownloaded = task.bytesDownloaded,
            totalBytes = task.totalBytes,
            eTag = task.eTag,
            lastModified = task.lastModified,
            status = DownloadManager.STATUS_PAUSED,
            progress = task.progress,
            errorMessage = message,
            updatedAt = System.currentTimeMillis(),
        )
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
        apiClient: ApiClient,
    ): String {
        if (request.mode == DownloadMode.ORIGINAL) {
            return source.path
        }
        val targetBitrate = request.videoBitrate ?: DEFAULT_TRANSCODE_VIDEO_BITRATE
        val (maxWidth, maxHeight) = when {
            targetBitrate <= 2_500_000 -> 1280 to 720
            targetBitrate <= 8_000_000 -> 1920 to 1080
            else -> null to null
        }
        val subtitleMethod = if (request.subtitleStreamIndex != null) {
            SubtitleDeliveryMethod.ENCODE
        } else {
            SubtitleDeliveryMethod.DROP
        }
        return apiClient.videoApi.getVideoStreamUrl(
            itemId = item.id,
            container = "mp4",
            static = false,
            mediaSourceId = source.id,
            videoCodec = "h264",
            audioCodec = "aac",
            audioBitRate = 192_000,
            maxAudioChannels = 2,
            transcodingMaxAudioChannels = 2,
            allowVideoStreamCopy = false,
            allowAudioStreamCopy = false,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            videoBitRate = targetBitrate,
            subtitleStreamIndex = request.subtitleStreamIndex,
            subtitleMethod = subtitleMethod,
            audioStreamIndex = request.audioStreamIndex,
            context = EncodingContext.STREAMING,
            enableAudioVbrEncoding = true,
        )
    }

    private fun downloadExternalMediaStreams(
        item: SpatialFinItem,
        source: SpatialFinSource,
        snap: PrepSnapshot,
    ) {
        for (mediaStream in source.mediaStreams.filter {
            it.type == MediaStreamType.SUBTITLE && !it.path.isNullOrBlank()
        }) {
            if (!isStillValid(snap)) return
            val id = UUID.nameUUIDFromBytes("${item.id}_${source.id}_${mediaStream.index}".toByteArray())
            val taskId = subtitleDownloadTaskId(item.id, id)
            val existingTask = database.getDownloadTaskById(taskId)
            if (existingTask != null) {
                if (existingTask.status != DownloadManager.STATUS_SUCCESSFUL &&
                    existingTask.status != DownloadManager.STATUS_RUNNING
                ) {
                    enqueueResumableDownload(taskId, itemTitle = item.name, snap = snap, isPrimary = false)
                }
                continue
            }
            val streamFile = downloadStorageManager.buildTargetFile(
                item = item,
                source = source,
                modeSuffix = "subtitle_$id",
                extension = inferMediaStreamExtension(mediaStream),
                inProgress = true,
            )
            val streamPath = Uri.fromFile(streamFile)
            database.insertMediaStream(
                mediaStream.toSpatialFinMediaStreamDto(id, source.id, streamPath.path.orEmpty()),
            )
            upsertDownloadTask(
                taskId = taskId,
                itemId = item.id,
                sourceId = source.id,
                kind = DownloadTaskKind.SUBTITLE,
                mediaStreamId = id,
                downloadId = null,
                requestUrl = mediaStream.path!!,
                accessToken = snap.accessToken,
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
            enqueueResumableDownload(taskId, itemTitle = item.name, snap = snap, isPrimary = false)
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
        snap: PrepSnapshot,
    ) {
        val maxIndex = ceil(
            trickplayInfo.thumbnailCount
                .toDouble()
                .div(trickplayInfo.tileWidth * trickplayInfo.tileHeight),
        ).toInt()
        val byteArrays = mutableListOf<ByteArray>()
        for (i in 0..maxIndex) {
            if (!isStillValid(snap)) return
            snap.scopedRepository.getTrickplayData(itemId, trickplayInfo.width, i)?.let { byteArray ->
                byteArrays.add(byteArray)
            }
        }
        if (!isStillValid(snap)) return
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

    private fun startImagesDownloader(item: SpatialFinItem, snap: PrepSnapshot) {
        if (!isStillValid(snap)) return
        val images = item.images
        val urlEntries = listOfNotNull(
            images.primary?.toString()?.let { ImagesDownloaderWorker.KEY_URL_PRIMARY to it },
            images.backdrop?.toString()?.let { ImagesDownloaderWorker.KEY_URL_BACKDROP to it },
            images.logo?.toString()?.let { ImagesDownloaderWorker.KEY_URL_LOGO to it },
        )
        val networkType =
            if (appPreferences.getValue(appPreferences.downloadOverMobileData)) {
                NetworkType.CONNECTED
            } else {
                NetworkType.UNMETERED
            }
        val downloadImagesRequest =
            OneTimeWorkRequestBuilder<ImagesDownloaderWorker>()
                .setConstraints(Constraints(requiredNetworkType = networkType))
                .setInputData(
                    workDataOf(
                        ImagesDownloaderWorker.KEY_ITEM_ID to item.id.toString(),
                        ImagesDownloaderWorker.KEY_SERVER_ID to snap.serverId,
                        *urlEntries.toTypedArray(),
                    ),
                )
                .addTag("download-server:${snap.serverId}")
                .addTag("download-user:${snap.userId}")
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

    private fun enqueueResumableDownload(
        taskId: String,
        itemTitle: String? = null,
        snap: PrepSnapshot,
        isPrimary: Boolean,
    ) {
        if (!isStillValid(snap)) return
        val networkType =
            if (appPreferences.getValue(appPreferences.downloadOverMobileData)) {
                NetworkType.CONNECTED
            } else {
                NetworkType.UNMETERED
            }
        val inputData = workDataOf(
            ResumableDownloadWorker.KEY_TASK_ID to taskId,
            ResumableDownloadWorker.KEY_ITEM_TITLE to (itemTitle ?: ""),
        )
        val requestBuilder =
            OneTimeWorkRequestBuilder<ResumableDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .setInputData(inputData)
                .addTag("download-server:${snap.serverId}")
                .addTag("download-user:${snap.userId}")
        if (isPrimary) {
            requestBuilder.addTag("download-primary")
        }
        workManager.enqueueUniqueWork(
            ResumableDownloadWorker.uniqueWorkName(taskId),
            ExistingWorkPolicy.REPLACE,
            requestBuilder.build(),
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

        const val DEFAULT_TRANSCODE_VIDEO_BITRATE = 5_000_000

        fun uniqueWorkName(taskId: String): String = "download-prep:$taskId"
    }
}
