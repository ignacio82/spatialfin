package dev.jdtech.jellyfin.film.presentation.episode

import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItemPerson
import dev.jdtech.jellyfin.models.VideoMetadata

data class EpisodeState(
    val episode: SpatialFinEpisode? = null,
    val videoMetadata: VideoMetadata? = null,
    val actors: List<SpatialFinItemPerson> = emptyList(),
    val displayExtraInfo: Boolean = false,
    val error: Exception? = null,
)
