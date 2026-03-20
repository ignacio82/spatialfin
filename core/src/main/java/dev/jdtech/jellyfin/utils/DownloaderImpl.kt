package dev.jdtech.jellyfin.utils

import android.app.DownloadManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderState
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.downloads.DownloadStorageManager
import dev.jdtech.jellyfin.models.DownloadMode
import dev.jdtech.jellyfin.models.DownloadRequest
import dev.jdtech.jellyfin.models.DownloadTaskKind
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinMediaStream
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.SpatialFinSources
import dev.jdtech.jellyfin.models.SpatialFinTrickplayInfo
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.downloadTaskId
import dev.jdtech.jellyfin.models.subtitleDownloadTaskId
import dev.jdtech.jellyfin.models.toSpatialFinEpisodeDto
import dev.jdtech.jellyfin.models.toSpatialFinMediaStreamDto
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
import java.io.File
import java.util.UUID
import kotlin.Exception
import kotlin.math.ceil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.jellyfin.sdk.model.api.EncodingContext
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

    // TODO: We should probably move most (if not all) code to a worker.
    //  At this moment it is possible that some things are not downloaded due to the user leaving
    //  the current screen
    override suspend fun downloadItem(
        item: SpatialFinItem,
        request: DownloadRequest,
    ): UiText? {
        try {
            val source =
                jellyfinRepository.getMediaSources(item.id, true).first { it.id == request.sourceId }
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
            enqueueResumableDownload(taskId)
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

    private fun downloadExternalMediaStreams(
        item: SpatialFinItem,
        source: SpatialFinSource,
    ) {
        for (mediaStream in source.mediaStreams.filter { it.isExternal && it.path != null }) {
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
            enqueueResumableDownload(taskId)
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
        val downloadImagesRequest =
            OneTimeWorkRequestBuilder<ImagesDownloaderWorker>()
                .setInputData(workDataOf(ImagesDownloaderWorker.KEY_ITEM_ID to item.id.toString()))
                .build()

        workManager.enqueue(downloadImagesRequest)
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

    private fun enqueueResumableDownload(taskId: String) {
        val networkType =
            if (appPreferences.getValue(appPreferences.downloadOverMobileData)) {
                NetworkType.CONNECTED
            } else {
                NetworkType.UNMETERED
            }
        Timber.i(
            "Enqueue resumable download taskId=%s networkType=%s",
            taskId,
            networkType,
        )
        val request =
            OneTimeWorkRequestBuilder<ResumableDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
                .setInputData(workDataOf(ResumableDownloadWorker.KEY_TASK_ID to taskId))
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
