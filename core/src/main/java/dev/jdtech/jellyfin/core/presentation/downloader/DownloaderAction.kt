package dev.jdtech.jellyfin.core.presentation.downloader

import dev.jdtech.jellyfin.models.DownloadRequest
import dev.jdtech.jellyfin.models.SpatialFinItem

sealed interface DownloaderAction {
    data class Download(val item: SpatialFinItem, val request: DownloadRequest) : DownloaderAction

    data class DeleteDownload(val item: SpatialFinItem) : DownloaderAction

    data class CancelDownload(val item: SpatialFinItem) : DownloaderAction
}
