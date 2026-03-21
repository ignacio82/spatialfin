package dev.jdtech.jellyfin.film.presentation.search

import dev.jdtech.jellyfin.api.SeerrSearchResult
import dev.jdtech.jellyfin.models.SpatialFinItem

sealed interface SearchAction {
    data class Search(val query: String) : SearchAction

    data class OnItemClick(val item: SpatialFinItem) : SearchAction

    data class RequestSeerrItem(val item: SeerrSearchResult) : SearchAction
}
