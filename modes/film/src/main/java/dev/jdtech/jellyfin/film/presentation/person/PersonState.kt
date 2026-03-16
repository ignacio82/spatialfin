package dev.jdtech.jellyfin.film.presentation.person

import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinPerson
import dev.jdtech.jellyfin.models.SpatialFinShow

data class PersonState(
    val person: SpatialFinPerson? = null,
    val starredInMovies: List<SpatialFinMovie> = emptyList(),
    val starredInShows: List<SpatialFinShow> = emptyList(),
    val error: Exception? = null,
)
