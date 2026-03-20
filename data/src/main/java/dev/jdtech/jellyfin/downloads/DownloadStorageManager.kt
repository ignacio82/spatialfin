package dev.jdtech.jellyfin.downloads

import android.content.Context
import android.os.Build
import android.os.Environment
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.SpatialFinSourceType
import dev.jdtech.jellyfin.models.toSpatialFinEpisode
import dev.jdtech.jellyfin.models.toSpatialFinMovie
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DownloadStorageManager
constructor(
    private val context: Context,
    private val database: ServerDatabaseDao,
) {
    fun downloadsRoot(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DOWNLOADS_FOLDER_NAME,
        )

    fun ensureDownloadsRoot(): File = downloadsRoot().apply { mkdirs() }

    fun buildTargetFile(
        item: SpatialFinItem,
        source: SpatialFinSource,
        modeSuffix: String,
        extension: String,
        inProgress: Boolean = true,
    ): File {
        val safeTitle = sanitizeFileName(item.name)
        val safeSource = sanitizeFileName(source.name).ifBlank { "source" }
        val baseName = "${safeTitle}_${item.id}_${safeSource}_${modeSuffix}.$extension"
        return File(
            ensureDownloadsRoot(),
            if (inProgress) "$baseName$DOWNLOAD_TEMP_SUFFIX" else baseName,
        )
    }

    fun completedPathFor(path: String): String =
        if (path.endsWith(DOWNLOAD_TEMP_SUFFIX)) {
            path.removeSuffix(DOWNLOAD_TEMP_SUFFIX)
        } else {
            path
        }

    suspend fun reconcileCurrentServerDownloads(serverId: String?, userId: UUID?) {
        if (serverId == null || userId == null) return
        withContext(Dispatchers.IO) {
            database.getMoviesByServerId(serverId).forEach {
                reconcileItem(it.id, userId)
            }
            database.getEpisodesByServerId(serverId).forEach {
                reconcileItem(it.id, userId)
            }
        }
    }

    suspend fun reconcileItem(itemId: UUID, userId: UUID?) {
        if (userId == null) return
        withContext(Dispatchers.IO) {
            database.getMovieOrNull(itemId)?.let {
                reconcileItemSources(it.toSpatialFinMovie(database, userId))
                return@withContext
            }
            database.getEpisodeOrNull(itemId)?.let {
                reconcileItemSources(it.toSpatialFinEpisode(database, userId))
            }
        }
    }

    suspend fun deleteItem(item: SpatialFinItem, source: SpatialFinSource, deletePhysicalFile: Boolean) {
        withContext(Dispatchers.IO) {
            when (item) {
                is SpatialFinMovie -> database.deleteMovie(item.id)
                is SpatialFinEpisode -> {
                    database.deleteEpisode(item.id)
                    val remainingEpisodes = database.getEpisodesBySeasonId(item.seasonId)
                    if (remainingEpisodes.isEmpty()) {
                        database.deleteSeason(item.seasonId)
                        database.deleteUserData(item.seasonId)
                        File(context.filesDir, "trickplay/${item.seasonId}").deleteRecursively()
                        File(context.filesDir, "images/${item.seasonId}").deleteRecursively()
                        val remainingSeasons = database.getSeasonsByShowId(item.seriesId)
                        if (remainingSeasons.isEmpty()) {
                            database.deleteShow(item.seriesId)
                            database.deleteUserData(item.seriesId)
                            File(context.filesDir, "trickplay/${item.seriesId}").deleteRecursively()
                            File(context.filesDir, "images/${item.seriesId}").deleteRecursively()
                        }
                    }
                }
            }

            database.deleteSource(source.id)
            if (deletePhysicalFile) {
                File(source.path).delete()
            }

            val mediaStreams = database.getMediaStreamsBySourceId(source.id)
            mediaStreams.forEach { mediaStream ->
                if (deletePhysicalFile) {
                    File(mediaStream.path).delete()
                }
                database.deleteMediaStream(mediaStream.id)
            }

            database.deleteUserData(item.id)
            File(context.filesDir, "trickplay/${item.id}").deleteRecursively()
            File(context.filesDir, "images/${item.id}").deleteRecursively()
        }
    }

    private fun reconcileItemSources(item: SpatialFinItem) {
        item.sources
            .filter { it.type == SpatialFinSourceType.LOCAL }
            .forEach { source ->
                if (!File(source.path).exists()) {
                    deleteItemBlocking(item, source)
                } else {
                    database.getMediaStreamsBySourceId(source.id).forEach { mediaStream ->
                        if (mediaStream.path.isNotBlank() && !File(mediaStream.path).exists()) {
                            database.deleteMediaStream(mediaStream.id)
                        }
                    }
                }
            }
    }

    private fun deleteItemBlocking(item: SpatialFinItem, source: SpatialFinSource) {
        when (item) {
            is SpatialFinMovie -> database.deleteMovie(item.id)
            is SpatialFinEpisode -> {
                database.deleteEpisode(item.id)
                val remainingEpisodes = database.getEpisodesBySeasonId(item.seasonId)
                if (remainingEpisodes.isEmpty()) {
                    database.deleteSeason(item.seasonId)
                    database.deleteUserData(item.seasonId)
                    File(context.filesDir, "trickplay/${item.seasonId}").deleteRecursively()
                    File(context.filesDir, "images/${item.seasonId}").deleteRecursively()
                    val remainingSeasons = database.getSeasonsByShowId(item.seriesId)
                    if (remainingSeasons.isEmpty()) {
                        database.deleteShow(item.seriesId)
                        database.deleteUserData(item.seriesId)
                        File(context.filesDir, "trickplay/${item.seriesId}").deleteRecursively()
                        File(context.filesDir, "images/${item.seriesId}").deleteRecursively()
                    }
                }
            }
        }

        database.deleteSource(source.id)
        database.getMediaStreamsBySourceId(source.id).forEach { database.deleteMediaStream(it.id) }
        database.deleteUserData(item.id)
        File(context.filesDir, "trickplay/${item.id}").deleteRecursively()
        File(context.filesDir, "images/${item.id}").deleteRecursively()
    }

    private fun sanitizeFileName(value: String): String =
        value
            .trim()
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .replace(Regex("\\s+"), " ")
            .take(80)
            .trim()
            .ifBlank { "download" }

    companion object {
        private const val DOWNLOADS_FOLDER_NAME = "SpatialFin"
        const val DOWNLOAD_TEMP_SUFFIX = ".download"
    }
}
