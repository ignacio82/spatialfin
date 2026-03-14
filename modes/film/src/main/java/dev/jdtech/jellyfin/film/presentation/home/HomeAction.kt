package dev.jdtech.jellyfin.film.presentation.home

import dev.jdtech.jellyfin.models.SpatialFinCollection
import dev.jdtech.jellyfin.models.SpatialFinItem

sealed interface HomeAction {
    data class OnItemClick(val item: SpatialFinItem) : HomeAction

    data class OnLibraryClick(val library: SpatialFinCollection) : HomeAction

    data object OnRetryClick : HomeAction

    data object OnSearchClick : HomeAction

    data object OnSettingsClick : HomeAction

    data object OnManageServers : HomeAction

    data object OnCloseClick : HomeAction
}
