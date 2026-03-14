package dev.jdtech.jellyfin.film.presentation.person

import dev.jdtech.jellyfin.models.SpatialFinItem

sealed interface PersonAction {
    data object NavigateBack : PersonAction

    data object NavigateHome : PersonAction

    data class NavigateToItem(val item: SpatialFinItem) : PersonAction
}
