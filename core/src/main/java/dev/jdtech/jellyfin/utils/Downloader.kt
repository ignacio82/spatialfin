package dev.jdtech.jellyfin.utils

import dev.jdtech.jellyfin.models.BulkDownloadSettings
import dev.jdtech.jellyfin.models.DownloadRequest
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.UiText
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderState

data class DownloadStatusSnapshot(
    val downloadId: Long?,
    val state: DownloaderState,
)

data class BulkDownloadResult(
    val queued: Int,
    val skipped: Int,
    val failed: Int,
    /** Set when estimated download size exceeds available storage. */
    val storageShortfallBytes: Long? = null,
)

data class ActiveDownloadEntry(
    val taskId: String,
    val itemId: UUID,
    val itemName: String,
    val progress: Int,
    val status: Int,
    val errorMessage: String?,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val downloadSpeedBytesPerSec: Long? = null,
)

interface Downloader {
    suspend fun downloadItem(item: SpatialFinItem, request: DownloadRequest): UiText?

    /**
     * Queue downloads for a list of episodes using shared settings.
     * Already-downloaded episodes are skipped automatically.
     * Returns a [BulkDownloadResult] with counts.
     */
    suspend fun downloadItems(
        episodes: List<SpatialFinEpisode>,
        settings: BulkDownloadSettings,
    ): BulkDownloadResult

    suspend fun cancelDownload(item: SpatialFinItem)
    suspend fun cancelDownloadById(itemId: UUID)

    /** Cancel the active worker without deleting the partial file or DB record. */
    suspend fun pauseDownload(item: SpatialFinItem)
    suspend fun pauseDownloadById(itemId: UUID)

    /** Re-enqueue a paused or failed download to resume from where it left off. */
    suspend fun resumeDownload(item: SpatialFinItem)
    suspend fun resumeDownloadById(itemId: UUID)

    suspend fun deleteItem(item: SpatialFinItem, source: SpatialFinSource)

    fun observeDownloadStatus(itemId: UUID): Flow<DownloadStatusSnapshot?>

    /** Observe all non-completed download tasks as [ActiveDownloadEntry] items. */
    fun observeActiveDownloads(): Flow<List<ActiveDownloadEntry>>

    suspend fun getStorageUsedBytes(): Long
}
