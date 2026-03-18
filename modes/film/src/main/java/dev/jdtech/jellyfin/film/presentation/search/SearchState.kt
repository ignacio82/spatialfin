package dev.jdtech.jellyfin.film.presentation.search

import dev.jdtech.jellyfin.models.SpatialFinItem

data class SearchState(
    val query: String = "",
    val items: List<SpatialFinItem> = emptyList(),
    val loading: Boolean = false,
    val hasSearched: Boolean = false,
    val errorMessage: String? = null,
)
