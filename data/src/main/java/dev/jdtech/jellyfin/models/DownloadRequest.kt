package dev.jdtech.jellyfin.models

import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod

enum class DownloadMode {
    ORIGINAL,
    TRANSCODED,
}

data class DownloadRequest(
    val sourceId: String,
    val mode: DownloadMode = DownloadMode.ORIGINAL,
    val videoBitrate: Int? = null,
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null,
    val subtitleDeliveryMethod: SubtitleDeliveryMethod? = null,
)
