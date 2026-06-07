package dev.jdtech.jellyfin.film.presentation.search

import dev.jdtech.jellyfin.api.SeerrSearchResult
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.SearchResult
import dev.jdtech.jellyfin.models.SpatialFinItem

data class SearchState(
    val query: String = "",
    val items: List<SpatialFinItem> = emptyList(),
    val seerrItems: List<SeerrSearchResult> = emptyList(),
    val maItems: SearchResult? = null,
    val displayRatings: Boolean = true,
    val loading: Boolean = false,
    val hasSearched: Boolean = false,
    val errorMessage: String? = null,
)
