package dev.jdtech.jellyfin.film.presentation.media

import dev.jdtech.jellyfin.models.SpatialFinCollection

sealed interface MediaAction {
    data class OnItemClick(val item: SpatialFinCollection) : MediaAction

    data object OnFavoritesClick : MediaAction

    data object OnRetryClick : MediaAction
}
