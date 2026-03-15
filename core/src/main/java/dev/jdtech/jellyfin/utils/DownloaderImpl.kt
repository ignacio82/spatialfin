package dev.jdtech.jellyfin.utils

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import androidx.core.net.toUri
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.downloads.DownloadStorageManager
import dev.jdtech.jellyfin.models.DownloadMode
import dev.jdtech.jellyfin.models.DownloadRequest
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinMediaStream
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.SpatialFinSources
import dev.jdtech.jellyfin.models.SpatialFinTrickplayInfo
import dev.jdtech.jellyfin.models.UiText
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
import java.io.File
import java.net.URLConnection
import java.util.UUID
import kotlin.Exception
import kotlin.math.ceil
import kotlinx.coroutines.coroutineScope
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
    ): Pair<Long, UiText?> = coroutineScope {
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
                return@coroutineScope Pair(
                    -1,
                    UiText.StringResource(CoreR.string.storage_unavailable),
                )
            }
            val targetFile = buildTargetFile(item, source, request)
            val path = Uri.fromFile(targetFile)
            val stats = android.os.StatFs(downloadsRoot.path)
            if (stats.availableBytes < source.size) {
                return@coroutineScope Pair(
                    -1,
                    UiText.StringResource(
                        CoreR.string.not_enough_storage,
                        Formatter.formatFileSize(context, source.size),
                        Formatter.formatFileSize(context, stats.availableBytes),
                    ),
                )
            }
            val requestUrl = buildDownloadUrl(item, source, request)
            val downloadManagerRequest =
                DownloadManager.Request(requestUrl.toUri())
                    .setTitle(item.name)
                    .setAllowedOverMetered(
                        appPreferences.getValue(appPreferences.downloadOverMobileData)
                    )
                    .setAllowedOverRoaming(
                        appPreferences.getValue(appPreferences.downloadWhenRoaming)
                    )
                    .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    .setDestinationUri(path)
            val downloadId = downloadManager.enqueue(downloadManagerRequest)

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

            database.insertSource(sourceDto.copy(downloadId = downloadId))
            database.insertUserData(item.toSpatialFinUserDataDto(jellyfinRepository.getUserId()))

            if (request.mode == DownloadMode.ORIGINAL) {
                downloadExternalMediaStreams(item, source)
            }

            segments.forEach { database.insertSegment(it.toSpatialFinSegmentsDto(item.id)) }

            if (trickplayInfo != null) {
                downloadTrickplayData(item.id, request.sourceId, trickplayInfo)
            }

            startImagesDownloader(item)
            return@coroutineScope Pair(downloadId, null)
        } catch (e: Exception) {
            try {
                val source =
                    jellyfinRepository.getMediaSources(item.id).first { it.id == request.sourceId }
                deleteItem(item, source)
            } catch (_: Exception) {}
            Timber.e(e)
            return@coroutineScope Pair(
                -1,
                if (e.message != null) UiText.DynamicString(e.message!!)
                else UiText.StringResource(CoreR.string.unknown_error),
            )
        }
    }

    override suspend fun cancelDownload(item: SpatialFinItem, downloadId: Long) {
        val source =
            database.getSourceByDownloadId(downloadId)?.toSpatialFinSource(database) ?: return
        if (source.downloadId != null) {
            downloadManager.remove(source.downloadId!!)
        }
        deleteItem(item, source)
    }

    override suspend fun deleteItem(item: SpatialFinItem, source: SpatialFinSource) {
        source.downloadId?.let { downloadManager.remove(it) }
        database.getMediaStreamsBySourceId(source.id)
            .mapNotNull { it.downloadId }
            .forEach { downloadManager.remove(it) }
        downloadStorageManager.deleteItem(item, source, deletePhysicalFile = true)
    }

    private fun buildTargetFile(
        item: SpatialFinItem,
        source: SpatialFinSource,
        request: DownloadRequest,
    ): File {
        return when (request.mode) {
            DownloadMode.ORIGINAL -> {
                val extension = inferExtension(source.path, fallback = "mkv")
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

    override suspend fun getProgress(downloadId: Long?): Pair<Int, Int> {
        var downloadStatus = -1
        var progress = -1
        if (downloadId == null) {
            return Pair(downloadStatus, progress)
        }
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = downloadManager.query(query)
        if (cursor.moveToFirst()) {
            downloadStatus =
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            when (downloadStatus) {
                DownloadManager.STATUS_RUNNING -> {
                    val totalBytes =
                        cursor.getLong(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        )
                    if (totalBytes > 0) {
                        val downloadedBytes =
                            cursor.getLong(
                                cursor.getColumnIndexOrThrow(
                                    DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
                                )
                            )
                        progress = downloadedBytes.times(100).div(totalBytes).toInt()
                    }
                }
                DownloadManager.STATUS_SUCCESSFUL -> {
                    progress = 100
                }
            }
        } else {
            downloadStatus = DownloadManager.STATUS_FAILED
        }
        return Pair(downloadStatus, progress)
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
                )
            val streamPath = Uri.fromFile(streamFile)
            database.insertMediaStream(
                mediaStream.toSpatialFinMediaStreamDto(id, source.id, streamPath.path.orEmpty())
            )
            val request =
                DownloadManager.Request(mediaStream.path!!.toUri())
                    .setTitle(mediaStream.title)
                    .setAllowedOverMetered(
                        appPreferences.getValue(appPreferences.downloadOverMobileData)
                    )
                    .setAllowedOverRoaming(
                        appPreferences.getValue(appPreferences.downloadWhenRoaming)
                    )
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                    .setDestinationUri(streamPath)
            val downloadId = downloadManager.enqueue(request)
            database.setMediaStreamDownloadId(id, downloadId)
        }
    }

    private fun inferMediaStreamExtension(mediaStream: SpatialFinMediaStream): String {
        val codec = mediaStream.codec.lowercase()
        return when {
            codec.isBlank() -> inferExtension(mediaStream.path.orEmpty(), fallback = "srt")
            codec == "subrip" -> "srt"
            codec == "mov_text" -> "srt"
            else -> codec
        }
    }

    private fun inferExtension(urlOrPath: String, fallback: String): String {
        val extensionFromPath =
            URLConnection.guessContentTypeFromName(urlOrPath)
                ?.substringAfterLast('/')
                ?.lowercase()
                ?.takeIf { it.isNotBlank() }
        return extensionFromPath
            ?: urlOrPath.substringBefore('?').substringAfterLast('.', "")
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
}
