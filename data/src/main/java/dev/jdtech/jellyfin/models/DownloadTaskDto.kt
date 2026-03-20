package dev.jdtech.jellyfin.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "downloadtasks")
data class DownloadTaskDto(
    @PrimaryKey val id: String,
    val itemId: UUID,
    val sourceId: String,
    @ColumnInfo(defaultValue = "PRIMARY") val kind: DownloadTaskKind,
    val mediaStreamId: UUID?,
    val downloadId: Long?,
    @ColumnInfo(defaultValue = "") val requestUrl: String,
    val accessToken: String?,
    @ColumnInfo(defaultValue = "") val tempPath: String,
    @ColumnInfo(defaultValue = "") val finalPath: String,
    @ColumnInfo(defaultValue = "0") val bytesDownloaded: Long,
    val totalBytes: Long?,
    val eTag: String?,
    val lastModified: String?,
    val status: Int,
    val progress: Int,
    val errorMessage: String?,
    val updatedAt: Long,
)

fun downloadTaskId(itemId: UUID, sourceId: String): String = "$itemId:$sourceId"

fun subtitleDownloadTaskId(itemId: UUID, mediaStreamId: UUID): String = "$itemId:subtitle:$mediaStreamId"
