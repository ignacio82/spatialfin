package dev.jdtech.jellyfin.utils

import dev.jdtech.jellyfin.models.DownloadRequest
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

interface Downloader {
    suspend fun downloadItem(item: SpatialFinItem, request: DownloadRequest): UiText?

    suspend fun cancelDownload(item: SpatialFinItem)

    suspend fun deleteItem(item: SpatialFinItem, source: SpatialFinSource)

    fun observeDownloadStatus(itemId: UUID): Flow<DownloadStatusSnapshot?>
}
