package dev.jdtech.jellyfin.repository

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.LocalMediaPlaybackStateDto
import dev.jdtech.jellyfin.models.LocalVideoItem
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.SpatialFinSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalMediaRepositoryImpl(
    private val context: Context,
    private val database: ServerDatabaseDao,
) : LocalMediaRepository {
    override suspend fun getVideos(): List<LocalVideoItem> = withContext(Dispatchers.IO) {
        if (!hasReadPermission()) return@withContext emptyList()

        val stateById = database.getAllLocalMediaPlaybackStates().associateBy { it.mediaStoreId }
        val collection =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

        val projection =
            arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            )

        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val displayNameIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateAddedIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val bucketIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)

            buildList {
                while (cursor.moveToNext()) {
                    val mediaStoreId = cursor.getLong(idIndex)
                    val fileName = cursor.getString(displayNameIndex).orEmpty()
                    val parsed = parseMetadata(fileName)
                    val state = stateById[mediaStoreId]
                    val contentUri = ContentUris.withAppendedId(collection, mediaStoreId)
                    val durationMs = cursor.getLong(durationIndex).coerceAtLeast(0L)

                    add(
                        LocalVideoItem(
                            mediaStoreId = mediaStoreId,
                            contentUri = contentUri,
                            fileName = fileName,
                            folderName = cursor.getString(bucketIndex),
                            sizeBytes = cursor.getLong(sizeIndex).coerceAtLeast(0L),
                            dateAddedEpochSeconds = cursor.getLong(dateAddedIndex).coerceAtLeast(0L),
                            durationMs = durationMs,
                            seasonNumber = parsed.seasonNumber,
                            episodeNumber = parsed.episodeNumber,
                            productionYear = parsed.productionYear,
                            name = parsed.displayTitle,
                            overview = parsed.overview(cursor.getString(bucketIndex)),
                            played = state?.watched == true,
                            sources =
                                listOf(
                                    SpatialFinSource(
                                        id = "local-$mediaStoreId",
                                        name = fileName,
                                        type = SpatialFinSourceType.LOCAL,
                                        path = contentUri.toString(),
                                        size = cursor.getLong(sizeIndex).coerceAtLeast(0L),
                                        mediaStreams = emptyList(),
                                    )
                                ),
                            runtimeTicks = durationMs * 10000L,
                            playbackPositionTicks = (state?.positionMs ?: 0L) * 10000L,
                        )
                    )
                }
            }
        } ?: emptyList()
    }

    override suspend fun searchVideos(query: String): List<LocalVideoItem> {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return emptyList()
        return getVideos().filter { item ->
            item.name.lowercase().contains(normalized) ||
                item.fileName.lowercase().contains(normalized) ||
                item.folderName.orEmpty().lowercase().contains(normalized)
        }
    }

    override suspend fun getVideo(mediaStoreId: Long): LocalVideoItem? {
        return getVideos().firstOrNull { it.mediaStoreId == mediaStoreId }
    }

    override suspend fun updatePlaybackState(mediaStoreId: Long, positionMs: Long, durationMs: Long) {
        withContext(Dispatchers.IO) {
            val watched =
                when {
                    durationMs <= 0L -> false
                    positionMs >= durationMs * WATCHED_THRESHOLD -> true
                    else -> false
                }
            database.insertLocalMediaPlaybackState(
                LocalMediaPlaybackStateDto(
                    mediaStoreId = mediaStoreId,
                    positionMs = positionMs.coerceAtLeast(0L),
                    durationMs = durationMs.coerceAtLeast(0L),
                    watched = watched,
                    lastPlayedAtEpochMs = System.currentTimeMillis(),
                )
            )
        }
    }

    override suspend fun markPlayed(mediaStoreId: Long, played: Boolean) {
        withContext(Dispatchers.IO) {
            val current = database.getLocalMediaPlaybackState(mediaStoreId)
            database.insertLocalMediaPlaybackState(
                LocalMediaPlaybackStateDto(
                    mediaStoreId = mediaStoreId,
                    positionMs =
                        if (played) current?.durationMs ?: 0L else 0L,
                    durationMs = current?.durationMs ?: 0L,
                    watched = played,
                    lastPlayedAtEpochMs = System.currentTimeMillis(),
                )
            )
        }
    }

    private fun hasReadPermission(): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO) ==
                    PackageManager.PERMISSION_GRANTED ||
                    context.checkSelfPermission(
                        android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                    ) == PackageManager.PERMISSION_GRANTED
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO) ==
                    PackageManager.PERMISSION_GRANTED
            else ->
                context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private data class ParsedMetadata(
        val displayTitle: String,
        val productionYear: Int?,
        val seasonNumber: Int?,
        val episodeNumber: Int?,
    ) {
        fun overview(folderName: String?): String {
            val details =
                buildList {
                    productionYear?.let { add(it.toString()) }
                    if (seasonNumber != null && episodeNumber != null) {
                        add("S${seasonNumber}:E${episodeNumber}")
                    }
                    folderName?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            return details.joinToString(" • ")
        }
    }

    private fun parseMetadata(fileName: String): ParsedMetadata {
        val withoutExtension = fileName.substringBeforeLast('.')
        val normalized = withoutExtension.replace(Regex("[._]+"), " ").trim()
        val seasonEpisode = Regex("(?i)\\bS(\\d{1,2})E(\\d{1,2})\\b").find(normalized)
        val year = Regex("\\b(19\\d{2}|20\\d{2}|21\\d{2})\\b").find(normalized)?.value?.toIntOrNull()
        val cleaned =
            normalized
                .replace(Regex("(?i)\\bS\\d{1,2}E\\d{1,2}\\b"), "")
                .replace(Regex("\\b(19\\d{2}|20\\d{2}|21\\d{2})\\b"), "")
                .replace(Regex("\\s{2,}"), " ")
                .trim()
                .ifBlank { withoutExtension }

        return ParsedMetadata(
            displayTitle = cleaned,
            productionYear = year,
            seasonNumber = seasonEpisode?.groupValues?.getOrNull(1)?.toIntOrNull(),
            episodeNumber = seasonEpisode?.groupValues?.getOrNull(2)?.toIntOrNull(),
        )
    }

    private companion object {
        private const val WATCHED_THRESHOLD = 0.9
    }
}
