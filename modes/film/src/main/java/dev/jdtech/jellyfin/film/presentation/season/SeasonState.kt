package dev.jdtech.jellyfin.film.presentation.season

import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.utils.BulkDownloadResult

data class SeasonState(
    val season: SpatialFinSeason? = null,
    val episodes: List<SpatialFinEpisode> = emptyList(),
    val error: Exception? = null,
    val isQueuingBulkDownload: Boolean = false,
    val bulkDownloadResult: BulkDownloadResult? = null,
)
