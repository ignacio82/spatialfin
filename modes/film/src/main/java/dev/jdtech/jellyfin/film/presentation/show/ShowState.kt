package dev.jdtech.jellyfin.film.presentation.show

import dev.jdtech.jellyfin.film.domain.DetailRelatedRows
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItemPerson
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.SpatialFinShow

data class ShowState(
    val show: SpatialFinShow? = null,
    val nextUp: SpatialFinEpisode? = null,
    val seasons: List<SpatialFinSeason> = emptyList(),
    val actors: List<SpatialFinItemPerson> = emptyList(),
    val director: SpatialFinItemPerson? = null,
    val writers: List<SpatialFinItemPerson> = emptyList(),
    val related: DetailRelatedRows = DetailRelatedRows(),
    val displayRatings: Boolean = true,
    val error: Exception? = null,
)
