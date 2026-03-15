package dev.jdtech.jellyfin.film.presentation.movie

import dev.jdtech.jellyfin.models.SpatialFinItemPerson
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.VideoMetadata

data class MovieState(
    val movie: SpatialFinMovie? = null,
    val availableVersions: List<SpatialFinMovie> = emptyList(),
    val videoMetadata: VideoMetadata? = null,
    val actors: List<SpatialFinItemPerson> = emptyList(),
    val director: SpatialFinItemPerson? = null,
    val writers: List<SpatialFinItemPerson> = emptyList(),
    val displayExtraInfo: Boolean = false,
    val error: Exception? = null,
)
