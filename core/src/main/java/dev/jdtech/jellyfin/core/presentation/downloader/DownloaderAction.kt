package dev.jdtech.jellyfin.core.presentation.downloader

import dev.jdtech.jellyfin.models.SpatialFinItem

sealed interface DownloaderAction {
    data class Download(val item: SpatialFinItem, val storageIndex: Int = 0) : DownloaderAction

    data class DeleteDownload(val item: SpatialFinItem) : DownloaderAction

    data class CancelDownload(val item: SpatialFinItem) : DownloaderAction
}
