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
    // Phase 2 encryption. Existing rows default to false (plain file on disk).
    // New rows set this to true after finalize if content encryption is enabled.
    @ColumnInfo(defaultValue = "0") val isEncrypted: Boolean = false,
    // 16-byte AES-CTR IV, base64-encoded (no wrap). Null for unencrypted downloads.
    val encryptionIv: String? = null,
)

fun downloadTaskId(itemId: UUID, sourceId: String): String = "$itemId:$sourceId"

fun subtitleDownloadTaskId(itemId: UUID, mediaStreamId: UUID): String = "$itemId:subtitle:$mediaStreamId"
