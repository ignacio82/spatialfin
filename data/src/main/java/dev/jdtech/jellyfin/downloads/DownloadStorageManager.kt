package dev.jdtech.jellyfin.downloads

import android.app.DownloadManager
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.DownloadTaskKind
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinEpisodeDto
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSeasonDto
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.SpatialFinSourceDto
import dev.jdtech.jellyfin.models.SpatialFinSourceType
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.models.toSpatialFinEpisode
import dev.jdtech.jellyfin.models.toSpatialFinMovie
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class OrphanedFileEntry(
    val path: String,
    val fileName: String,
    val sizeBytes: Long,
    val mediaStoreUri: Uri? = null,
)

data class OrphanedDownloadsReport(
    val entries: List<OrphanedFileEntry> = emptyList(),
) {
    val totalCount: Int get() = entries.size
    val totalBytes: Long get() = entries.sumOf { it.sizeBytes }
}

data class OrphanedDownloadCleanupResult(
    val deletedCount: Int,
    val freedBytes: Long,
    val requiresSystemConfirmationUris: List<Uri> = emptyList(),
)

class DownloadStorageManager
constructor(
    private val context: Context,
    private val database: ServerDatabaseDao,
) {
    private val tombstoneLock = Any()

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
        if (inProgress) {
            clearDeletedTombstone(item.id, listOf(baseName))
        }
        return File(
            ensureDownloadsRoot(),
            if (inProgress) "$baseName$DOWNLOAD_TEMP_SUFFIX" else baseName,
        )
    }

    fun markDownloadDeleted(itemId: UUID, paths: Collection<String> = emptyList()) {
        synchronized(tombstoneLock) {
            val entries = readTombstones()
            entries.add(itemId.toString().lowercase(Locale.ROOT))
            paths.filter { it.isNotBlank() }.forEach { path ->
                entries.add(File(path).name)
                entries.add(File(completedPathFor(path)).name)
            }
            writeTombstones(entries)
        }
    }

    fun clearDeletedTombstone(itemId: UUID, paths: Collection<String> = emptyList()) {
        synchronized(tombstoneLock) {
            val entries = readTombstones()
            val normalizedId = itemId.toString().lowercase(Locale.ROOT)
            val pathNames =
                paths
                    .filter { it.isNotBlank() }
                    .flatMap { listOf(File(it).name, File(completedPathFor(it)).name) }
                    .toSet()
            val removed =
                entries.removeAll { entry ->
                    entry.equals(normalizedId, ignoreCase = true) ||
                        entry.contains("_${itemId}_", ignoreCase = true) ||
                        entry in pathNames
                }
            if (removed) {
                writeTombstones(entries)
            }
        }
    }

    fun isDownloadMarkedDeleted(itemId: UUID, path: String? = null): Boolean {
        synchronized(tombstoneLock) {
            val entries = readTombstones()
            if (entries.contains(itemId.toString().lowercase(Locale.ROOT))) return true
            if (!path.isNullOrBlank()) {
                val rawName = File(path).name
                val completedName = File(completedPathFor(path)).name
                if (entries.contains(rawName) || entries.contains(completedName)) return true
            }
            return false
        }
    }

    private fun tombstoneFile(): File = File(context.filesDir, DELETED_TOMBSTONES_FILE_NAME)

    private fun readTombstones(): MutableSet<String> {
        val file = tombstoneFile()
        if (!file.exists()) return mutableSetOf()
        return runCatching {
            file.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableSet()
        }.getOrElse { mutableSetOf() }
    }

    private fun writeTombstones(entries: Set<String>) {
        runCatching {
            val file = tombstoneFile()
            file.parentFile?.mkdirs()
            file.writeText(entries.joinToString("\n"))
        }
    }

    private fun deletePhysicalFileAndMediaStore(path: String) {
        if (path.isBlank()) return
        val candidates = listOf(path, completedPathFor(path)).filter { it.isNotBlank() }.distinct()
        for (candidate in candidates) {
            runCatching { File(candidate).delete() }
            runCatching {
                context.contentResolver.delete(
                    MediaStore.Files.getContentUri("external"),
                    "${MediaStore.MediaColumns.DATA} = ?",
                    arrayOf(candidate),
                )
            }
        }
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
            pruneEmptySeasonsAndShows(serverId, userId)
        }
    }

    private fun pruneEmptySeasonsAndShows(serverId: String, userId: UUID) {
        val allEpisodes = database.getEpisodesByServerId(serverId)
        val activeLocalItemIds =
            database.getAllSources()
                .filter { it.type == SpatialFinSourceType.LOCAL && !isDownloadMarkedDeleted(it.itemId, it.path) }
                .map { it.itemId }
                .toSet()
        if (allEpisodes.isEmpty() && activeLocalItemIds.isNotEmpty()) return

        val activeEpisodes =
            allEpisodes.filter { ep ->
                !isDownloadMarkedDeleted(ep.id) &&
                    (ep.id in activeLocalItemIds ||
                        ep.toSpatialFinEpisode(database, userId).isDownloaded() ||
                        database.getDownloadTasksByItemId(ep.id).any {
                            it.status == DownloadManager.STATUS_PENDING ||
                                it.status == DownloadManager.STATUS_RUNNING ||
                                it.status == DownloadManager.STATUS_PAUSED
                        })
            }
        val activeSeasonIds = activeEpisodes.map { it.seasonId }.toSet()
        val activeShowIds = activeEpisodes.map { it.seriesId }.toSet()

        database.getShowsByServerId(serverId).forEach { show ->
            database.getSeasonsByShowId(show.id).forEach { season ->
                if (season.id !in activeSeasonIds) {
                    database.deleteSeason(season.id)
                    database.deleteUserData(season.id)
                    File(context.filesDir, "trickplay/${season.id}").deleteRecursively()
                    File(context.filesDir, "images/${season.id}").deleteRecursively()
                }
            }
            if (show.id !in activeShowIds) {
                database.deleteShow(show.id)
                database.deleteUserData(show.id)
                File(context.filesDir, "trickplay/${show.id}").deleteRecursively()
                File(context.filesDir, "images/${show.id}").deleteRecursively()
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
        // 0. Remove any movie ID that was erroneously duplicated into `episodes` by earlier recovery.
        database.getMoviesByServerId(serverId).forEach { movie ->
            if (database.getEpisodeOrNull(movie.id) != null) {
                database.deleteEpisode(movie.id)
            }
        }

        // Purge any local source/item that has already been marked deleted by the user.
        database.getAllSources()
            .filter { it.type == SpatialFinSourceType.LOCAL && isDownloadMarkedDeleted(it.itemId, it.path) }
            .forEach { source ->
                deletePhysicalFileAndMediaStore(source.path)
                database.getMediaStreamsBySourceId(source.id).forEach { ms ->
                    deletePhysicalFileAndMediaStore(ms.path)
                    database.deleteMediaStream(ms.id)
                }
                database.deleteSource(source.id)
                database.deleteDownloadTasksByItemId(source.itemId)
                database.deleteMovie(source.itemId)
                database.deleteEpisode(source.itemId)
            }

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
                    if (isDownloadMarkedDeleted(itemId, file.absolutePath)) {
                        deletePhysicalFileAndMediaStore(file.absolutePath)
                        return@forEach
                    }
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
                !isDownloadMarkedDeleted(source.itemId, source.path) &&
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
            markDownloadDeleted(item.id, listOf(source.path, completedPathFor(source.path)))
            when (item) {
                is SpatialFinMovie -> {
                    database.deleteMovie(item.id)
                    database.deleteEpisode(item.id)
                }
                is SpatialFinEpisode -> {
                    database.deleteEpisode(item.id)
                    database.deleteMovie(item.id)
                    cleanupEmptySeasonAndShow(item.seasonId, item.seriesId)
                }
            }

            database.deleteSource(source.id)
            if (deletePhysicalFile) {
                deletePhysicalFileAndMediaStore(source.path)
            }

            val mediaStreams = database.getMediaStreamsBySourceId(source.id)
            mediaStreams.forEach { mediaStream ->
                if (deletePhysicalFile) {
                    deletePhysicalFileAndMediaStore(mediaStream.path)
                }
                database.deleteMediaStream(mediaStream.id)
            }

            database.deleteSyncedUserData(item.id)
            File(context.filesDir, "trickplay/${item.id}").deleteRecursively()
            File(context.filesDir, "images/${item.id}").deleteRecursively()
        }
    }

    suspend fun purgeItem(item: SpatialFinItem, deletePhysicalFile: Boolean = true) {
        withContext(Dispatchers.IO) {
            if (item is SpatialFinShow) {
                val episodes =
                    (database.getEpisodesByShowId(item.id) +
                        database.getSeasonsByShowId(item.id).flatMap { database.getEpisodesBySeasonId(it.id) })
                        .distinctBy { it.id }
                episodes.forEach { ep ->
                    purgeSingleItemById(
                        itemId = ep.id,
                        seasonId = ep.seasonId,
                        seriesId = ep.seriesId,
                        extraPaths = emptyList(),
                        deletePhysicalFile = deletePhysicalFile,
                    )
                }
                database.getSeasonsByShowId(item.id).forEach { season ->
                    database.deleteSeason(season.id)
                    database.deleteUserData(season.id)
                    File(context.filesDir, "trickplay/${season.id}").deleteRecursively()
                    File(context.filesDir, "images/${season.id}").deleteRecursively()
                }
                database.deleteShow(item.id)
                database.deleteUserData(item.id)
                File(context.filesDir, "trickplay/${item.id}").deleteRecursively()
                File(context.filesDir, "images/${item.id}").deleteRecursively()
                return@withContext
            }

            val epDto = database.getEpisodeOrNull(item.id)
            purgeSingleItemById(
                itemId = item.id,
                seasonId = (item as? SpatialFinEpisode)?.seasonId ?: epDto?.seasonId,
                seriesId = (item as? SpatialFinEpisode)?.seriesId ?: epDto?.seriesId,
                extraPaths = item.sources.map { it.path },
                deletePhysicalFile = deletePhysicalFile,
            )
        }
    }

    private fun purgeSingleItemById(
        itemId: UUID,
        seasonId: UUID?,
        seriesId: UUID?,
        extraPaths: List<String>,
        deletePhysicalFile: Boolean,
    ) {
        val dbSources = database.getSources(itemId)
        val matchingDiskFiles =
            downloadsRoot()
                .takeIf { it.exists() }
                ?.listFiles()
                ?.filter { it.name.contains("_${itemId}_", ignoreCase = true) }
                .orEmpty()
        val allPaths =
            (extraPaths + dbSources.map { it.path } + matchingDiskFiles.map { it.absolutePath })
                .filter { it.isNotBlank() }
                .distinct()

        markDownloadDeleted(itemId, allPaths)

        dbSources.forEach { src ->
            if (deletePhysicalFile) {
                deletePhysicalFileAndMediaStore(src.path)
            }
            database.getMediaStreamsBySourceId(src.id).forEach { ms ->
                if (deletePhysicalFile) {
                    deletePhysicalFileAndMediaStore(ms.path)
                }
                database.deleteMediaStream(ms.id)
            }
            database.deleteSource(src.id)
        }

        if (deletePhysicalFile) {
            allPaths.forEach { deletePhysicalFileAndMediaStore(it) }
        }

        database.deleteDownloadTasksByItemId(itemId)
        database.deleteMovie(itemId)
        database.deleteEpisode(itemId)
        if (seasonId != null && seriesId != null) {
            cleanupEmptySeasonAndShow(seasonId, seriesId)
        }
        database.deleteSyncedUserData(itemId)
        File(context.filesDir, "trickplay/$itemId").deleteRecursively()
        File(context.filesDir, "images/$itemId").deleteRecursively()
    }

    private fun cleanupEmptySeasonAndShow(seasonId: UUID, seriesId: UUID) {
        val remainingEpisodes = database.getEpisodesBySeasonId(seasonId)
        if (remainingEpisodes.isEmpty()) {
            database.deleteSeason(seasonId)
            database.deleteUserData(seasonId)
            File(context.filesDir, "trickplay/$seasonId").deleteRecursively()
            File(context.filesDir, "images/$seasonId").deleteRecursively()
            val remainingSeasons = database.getSeasonsByShowId(seriesId)
            if (remainingSeasons.isEmpty() && database.getEpisodesByShowId(seriesId).isEmpty()) {
                database.deleteShow(seriesId)
                database.deleteUserData(seriesId)
                File(context.filesDir, "trickplay/$seriesId").deleteRecursively()
                File(context.filesDir, "images/$seriesId").deleteRecursively()
            }
        }
    }

    private fun reconcileItemSources(item: SpatialFinItem) {
        item.sources
            .filter { it.type == SpatialFinSourceType.LOCAL }
            .forEach { source ->
                if (!File(source.path).exists() || isDownloadMarkedDeleted(item.id, source.path)) {
                    val statuses = database.getDownloadTasksBySourceId(source.id).map { it.status }
                    if (
                        isDownloadMarkedDeleted(item.id, source.path) ||
                            DownloadReconciliationPolicy.shouldDeleteMissingSource(statuses)
                    ) {
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
        markDownloadDeleted(item.id, listOf(source.path, completedPathFor(source.path)))
        database.executeInTransaction {
            when (item) {
                is SpatialFinMovie -> {
                    database.deleteMovie(item.id)
                    database.deleteEpisode(item.id)
                }
                is SpatialFinEpisode -> {
                    database.deleteEpisode(item.id)
                    database.deleteMovie(item.id)
                    cleanupEmptySeasonAndShow(item.seasonId, item.seriesId)
                }
            }

            database.deleteSource(source.id)
            database.getMediaStreamsBySourceId(source.id).forEach { database.deleteMediaStream(it.id) }
            database.deleteSyncedUserData(item.id)
        }
        File(context.filesDir, "trickplay/${item.id}").deleteRecursively()
        File(context.filesDir, "images/${item.id}").deleteRecursively()
    }

    private fun collectActivePaths(): Set<String> {
        val activePaths = mutableSetOf<String>()
        val activeSources =
            database.getAllSources().filter { source ->
                source.type == SpatialFinSourceType.LOCAL &&
                    !isDownloadMarkedDeleted(source.itemId, source.path)
            }
        val activeSourceIds = activeSources.map { it.id }.toSet()

        for (source in activeSources) {
            if (source.path.isNotBlank()) {
                activePaths.add(File(source.path).absolutePath)
                activePaths.add(File(completedPathFor(source.path)).absolutePath)
            }
        }

        for (stream in database.getAllMediaStreams()) {
            if (stream.sourceId in activeSourceIds && stream.path.isNotBlank()) {
                activePaths.add(File(stream.path).absolutePath)
                activePaths.add(File(completedPathFor(stream.path)).absolutePath)
            }
        }

        for (task in database.getAllDownloadTasks()) {
            if (
                task.status != DownloadManager.STATUS_FAILED &&
                    !isDownloadMarkedDeleted(task.itemId, task.finalPath.ifBlank { task.tempPath })
            ) {
                if (task.tempPath.isNotBlank()) {
                    activePaths.add(File(task.tempPath).absolutePath)
                }
                if (task.finalPath.isNotBlank()) {
                    activePaths.add(File(task.finalPath).absolutePath)
                }
            }
        }

        return activePaths
    }

    suspend fun scanOrphanedFiles(): OrphanedDownloadsReport =
        withContext(Dispatchers.IO) {
            val activePaths = collectActivePaths()
            val activeFileNames = activePaths.map { File(it).name }.toSet()
            val byFileName = linkedMapOf<String, OrphanedFileEntry>()

            val root = downloadsRoot()
            if (root.exists()) {
                root.walkTopDown()
                    .filter { it.isFile }
                    .forEach { file ->
                        val absPath = file.absolutePath
                        if (absPath !in activePaths && file.name !in activeFileNames) {
                            byFileName[file.name] = OrphanedFileEntry(
                                path = absPath,
                                fileName = file.name,
                                sizeBytes = file.length().coerceAtLeast(0L),
                                mediaStoreUri = null,
                            )
                        }
                    }
            }

            queryMediaStoreOrphans(
                activePaths = activePaths,
                activeFileNames = activeFileNames,
                byFileName = byFileName,
            )

            OrphanedDownloadsReport(entries = byFileName.values.toList())
        }

    private fun queryMediaStoreOrphans(
        activePaths: Set<String>,
        activeFileNames: Set<String>,
        byFileName: MutableMap<String, OrphanedFileEntry>,
    ) {
        val collections = buildList<Uri> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
            }
            add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            add(MediaStore.Files.getContentUri("external"))
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATA,
        )
        val selection =
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.DATA} LIKE ?"
        val selectionArgs = arrayOf(
            "%Download/$DOWNLOADS_FOLDER_NAME%",
            "%/Download/$DOWNLOADS_FOLDER_NAME/%",
        )

        for (collectionUri in collections) {
            runCatching {
                context.contentResolver.query(
                    collectionUri,
                    projection,
                    selection,
                    selectionArgs,
                    null,
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)

                    while (cursor.moveToNext()) {
                        val rowId = if (idCol >= 0) cursor.getLong(idCol) else continue
                        val dataPath = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""
                        val displayName =
                            (if (nameCol >= 0) cursor.getString(nameCol) else null)
                                ?.takeIf { it.isNotBlank() }
                                ?: dataPath.takeIf { it.isNotBlank() }?.let { File(it).name }
                                ?: continue
                        val rowSize = if (sizeCol >= 0) cursor.getLong(sizeCol).coerceAtLeast(0L) else 0L
                        val normalizedPath =
                            if (dataPath.isNotBlank()) File(dataPath).absolutePath
                            else File(downloadsRoot(), displayName).absolutePath

                        if (normalizedPath in activePaths || displayName in activeFileNames) {
                            continue
                        }

                        val rowUri = ContentUris.withAppendedId(collectionUri, rowId)
                        val existing = byFileName[displayName]
                        byFileName[displayName] = OrphanedFileEntry(
                            path = existing?.path?.takeIf { it.isNotBlank() } ?: normalizedPath,
                            fileName = displayName,
                            sizeBytes = maxOf(existing?.sizeBytes ?: 0L, rowSize),
                            mediaStoreUri = existing?.mediaStoreUri ?: rowUri,
                        )
                    }
                }
            }
        }
    }

    suspend fun cleanOrphanedFiles(): OrphanedDownloadCleanupResult =
        withContext(Dispatchers.IO) {
            val report = scanOrphanedFiles()
            if (report.entries.isEmpty()) {
                return@withContext OrphanedDownloadCleanupResult(
                    deletedCount = 0,
                    freedBytes = 0L,
                    requiresSystemConfirmationUris = emptyList(),
                )
            }

            var deletedCount = 0
            var freedBytes = 0L
            val pendingUris = mutableListOf<Uri>()

            for (entry in report.entries) {
                val fileObj = if (entry.path.isNotBlank()) File(entry.path) else null
                val existedBefore = fileObj?.exists() == true
                val fileDeleted = if (existedBefore) {
                    runCatching { fileObj.delete() }.getOrDefault(false)
                } else {
                    false
                }
                val stillExistsOnDisk = fileObj?.exists() == true

                var uriSecurityBlocked = false
                var uriDeletedRows = 0

                entry.mediaStoreUri?.let { uri ->
                    try {
                        uriDeletedRows = context.contentResolver.delete(uri, null, null)
                    } catch (_: SecurityException) {
                        uriSecurityBlocked = true
                    } catch (_: Exception) {
                        // Ignore other provider failures
                    }
                }

                if (entry.path.isNotBlank()) {
                    runCatching {
                        context.contentResolver.delete(
                            MediaStore.Files.getContentUri("external"),
                            "${MediaStore.MediaColumns.DATA} = ?",
                            arrayOf(entry.path),
                        )
                    }
                }

                val removedSuccessfully =
                    (!stillExistsOnDisk && (fileDeleted || uriDeletedRows > 0)) ||
                        (!existedBefore && uriDeletedRows > 0 && !uriSecurityBlocked)

                if (removedSuccessfully) {
                    deletedCount++
                    freedBytes += entry.sizeBytes
                } else if (entry.mediaStoreUri != null) {
                    pendingUris.add(entry.mediaStoreUri)
                }
            }

            OrphanedDownloadCleanupResult(
                deletedCount = deletedCount,
                freedBytes = freedBytes,
                requiresSystemConfirmationUris = pendingUris.distinct(),
            )
        }

    fun computeTotalDownloadsSize(): Long {
        val root = downloadsRoot()
        if (!root.exists()) return 0L
        val activePaths = collectActivePaths()
        if (activePaths.isEmpty()) return 0L
        val activeFileNames = activePaths.map { File(it).name }.toSet()
        return root.walkTopDown()
            .filter { it.isFile && (it.absolutePath in activePaths || it.name in activeFileNames) }
            .sumOf { it.length() }
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
        private const val DELETED_TOMBSTONES_FILE_NAME = "deleted_download_tombstones.txt"
        const val DOWNLOAD_TEMP_SUFFIX = ".download"
        private val TARGET_FILE_REGEX =
            Regex(
                """^(.+)_([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})_(.+)_(original|transcoded)\.([A-Za-z0-9]+)$"""
            )
    }
}
