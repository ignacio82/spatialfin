package dev.jdtech.jellyfin.core.presentation.downloader

import dev.jdtech.jellyfin.models.BulkDownloadSettings
import dev.jdtech.jellyfin.models.DownloadRequest
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem

sealed interface DownloaderAction {
    data class Download(val item: SpatialFinItem, val request: DownloadRequest) : DownloaderAction

    data class DownloadEpisodes(
        val episodes: List<SpatialFinEpisode>,
        val settings: BulkDownloadSettings,
    ) : DownloaderAction

    data class DeleteDownload(val item: SpatialFinItem) : DownloaderAction

    data class CancelDownload(val item: SpatialFinItem) : DownloaderAction

    data class PauseDownload(val item: SpatialFinItem) : DownloaderAction

    data class ResumeDownload(val item: SpatialFinItem) : DownloaderAction
}
