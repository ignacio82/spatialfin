package dev.jdtech.jellyfin.film.presentation.media

import dev.jdtech.jellyfin.models.SpatialFinCollection

data class MediaState(
    val libraries: List<SpatialFinCollection> = emptyList(),
    val displayRatings: Boolean = true,
    val isLoading: Boolean = false,
    val error: Exception? = null,
)
