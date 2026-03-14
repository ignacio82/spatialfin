package dev.jdtech.jellyfin.film.presentation.collection

import dev.jdtech.jellyfin.models.SpatialFinItem

sealed interface CollectionAction {
    data class OnItemClick(val item: SpatialFinItem) : CollectionAction

    data object OnBackClick : CollectionAction
}
