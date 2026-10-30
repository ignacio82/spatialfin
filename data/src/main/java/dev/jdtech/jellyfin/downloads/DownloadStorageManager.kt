package dev.jdtech.jellyfin.downloads

import android.app.DownloadManager
import android.content.Context
import android.os.Build
import android.os.Environment
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.DownloadTaskKind
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinEpisodeDto
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSeasonDto
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.SpatialFinSourceDto
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
            healCompletedSourcePaths()
            recoverOrphanedDownloads(serverId, userId)
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
            healCompletedSourcePathsForItem(itemId)
            database.getMovieOrNull(itemId)?.let {
                reconcileItemSources(it.toSpatialFinMovie(database, userId))
                return@withContext
            }
            database.getEpisodeOrNull(itemId)?.let {
                reconcileItemSources(it.toSpatialFinEpisode(database, userId))
            }
        }
    }

    private fun healCompletedSourcePaths() {
        database.getAllSources().forEach { source ->
            healSourceIfCompletedOnDisk(source)
        }
    }

    private fun healCompletedSourcePathsForItem(itemId: UUID) {
        database.getSources(itemId).forEach { source ->
            healSourceIfCompletedOnDisk(source)
        }
    }

    private fun healSourceIfCompletedOnDisk(source: SpatialFinSourceDto) {
        if (source.type != SpatialFinSourceType.LOCAL) return
        if (!source.path.endsWith(DOWNLOAD_TEMP_SUFFIX)) return
        val completedFile = File(completedPathFor(source.path))
        if (completedFile.exists() && completedFile.length() > 0L && !File(source.path).exists()) {
            database.setSourcePath(source.id, completedFile.absolutePath)
            val fileLength = completedFile.length()
            database.getDownloadTasksBySourceId(source.id)
                .filter { it.kind == DownloadTaskKind.PRIMARY }
                .forEach { task ->
                    if (
                        task.status != DownloadManager.STATUS_SUCCESSFUL ||
                            task.totalBytes != fileLength ||
                            task.bytesDownloaded != fileLength ||
                            task.progress != 100
                    ) {
                        database.updateDownloadTask(
                            id = task.id,
                            downloadId = task.downloadId,
                            bytesDownloaded = fileLength,
                            totalBytes = fileLength,
                            eTag = task.eTag,
                            lastModified = task.lastModified,
                            status = DownloadManager.STATUS_SUCCESSFUL,
                            progress = 100,
                            errorMessage = null,
                            updatedAt = System.currentTimeMillis(),
                        )
                    }
                }
        }
    }

    private fun recoverOrphanedDownloads(serverId: String, userId: UUID) {
        // 1. Scan downloads folder for any completed video files not yet in `sources`.
        val root = downloadsRoot()
        if (root.exists()) {
            val knownPaths = database.getAllSources()
                .flatMap { listOf(it.path, completedPathFor(it.path)) }
                .toSet()
            root.listFiles()
                ?.filter { file ->
                    file.isFile &&
                        !file.name.endsWith(DOWNLOAD_TEMP_SUFFIX) &&
                        !file.name.contains("_subtitle_") &&
                        file.length() > 0L &&
                        file.absolutePath !in knownPaths
                }
                ?.forEach { file ->
                    val match = TARGET_FILE_REGEX.matchEntire(file.name) ?: return@forEach
                    val itemId = runCatching { UUID.fromString(match.groupValues[2]) }.getOrNull() ?: return@forEach
                    val safeSourceName = match.groupValues[3]
                    if (database.getSources(itemId).isEmpty()) {
                        val syntheticSourceId =
                            database.getDownloadTasksByItemId(itemId)
                                .firstOrNull { it.kind == DownloadTaskKind.PRIMARY }
                                ?.sourceId
                                ?: itemId.toString().replace("-", "")
                        database.insertSource(
                            SpatialFinSourceDto(
                                id = syntheticSourceId,
                                itemId = itemId,
                                name = safeSourceName,
                                type = SpatialFinSourceType.LOCAL,
                                path = file.absolutePath,
                            )
                        )
                    }
                }
        }

        // 2. For any LOCAL source whose file exists on disk but whose item row was cascade-deleted,
        // reconstruct the missing Episode/Season row so it is immediately browsable and playable.
        val orphanedSources = database.getAllSources().filter { source ->
            source.type == SpatialFinSourceType.LOCAL &&
                database.getMovieOrNull(source.itemId) == null &&
                database.getEpisodeOrNull(source.itemId) == null
        }
        if (orphanedSources.isEmpty()) return

        val shows = database.getShowsByServerId(serverId).ifEmpty { database.getShows() }
        if (shows.isEmpty()) return

        val targetShow =
            shows.firstOrNull { database.getSeasonsByShowId(it.id).isNotEmpty() }
                ?: shows.first()
        val targetSeason =
            database.getSeasonsByShowId(targetShow.id).firstOrNull()
                ?: SpatialFinSeasonDto(
                    id = UUID.nameUUIDFromBytes("${targetShow.id}:season:1".toByteArray()),
                    seriesId = targetShow.id,
                    name = "Season 1",
                    seriesName = targetShow.name,
                    overview = "",
                    indexNumber = 1,
                ).also { database.upsertSeason(it) }

        var nextIndex =
            (database.getEpisodesBySeasonId(targetSeason.id).maxOfOrNull { it.indexNumber } ?: 0) + 1

        for (source in orphanedSources) {
            val finalFile = File(completedPathFor(source.path))
            if (!finalFile.exists() || finalFile.length() <= 0L) continue
            if (source.path != finalFile.absolutePath) {
                database.setSourcePath(source.id, finalFile.absolutePath)
            }
            val recoveredTitle =
                finalFile.name
                    .substringBefore("_${source.itemId}")
                    .replace('_', ' ')
                    .trim()
                    .ifBlank { source.name.ifBlank { "Episode $nextIndex" } }
            database.upsertEpisode(
                SpatialFinEpisodeDto(
                    id = source.itemId,
                    serverId = serverId,
                    seasonId = targetSeason.id,
                    seriesId = targetShow.id,
                    name = recoveredTitle,
                    seriesName = targetShow.name,
                    overview = "",
                    indexNumber = nextIndex++,
                    indexNumberEnd = null,
                    parentIndexNumber = targetSeason.indexNumber.coerceAtLeast(1),
                    runtimeTicks = 0L,
                    premiereDate = null,
                    communityRating = null,
                    chapters = null,
                )
            )
            database.getUserDataOrCreateNew(source.itemId, userId)
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
                    val statuses = database.getDownloadTasksBySourceId(source.id).map { it.status }
                    if (DownloadReconciliationPolicy.shouldDeleteMissingSource(statuses)) {
                        deleteItemBlocking(item, source)
                    }
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
        database.executeInTransaction {
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
        }
        File(context.filesDir, "trickplay/${item.id}").deleteRecursively()
        File(context.filesDir, "images/${item.id}").deleteRecursively()
    }

    fun computeTotalDownloadsSize(): Long {
        val root = downloadsRoot()
        if (!root.exists()) return 0L
        return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
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
        private val TARGET_FILE_REGEX =
            Regex(
                """^(.+)_([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})_(.+)_(original|transcoded)\.([A-Za-z0-9]+)$"""
            )
    }
}
