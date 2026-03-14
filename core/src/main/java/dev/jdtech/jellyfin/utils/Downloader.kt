package dev.jdtech.jellyfin.utils

import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.UiText

interface Downloader {
    suspend fun downloadItem(
        item: SpatialFinItem,
        sourceId: String,
        storageIndex: Int = 0,
    ): Pair<Long, UiText?>

    suspend fun cancelDownload(item: SpatialFinItem, downloadId: Long)

    suspend fun deleteItem(item: SpatialFinItem, source: SpatialFinSource)

    suspend fun getProgress(downloadId: Long?): Pair<Int, Int>
}
