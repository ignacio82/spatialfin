package dev.jdtech.jellyfin.film.presentation.search

import dev.jdtech.jellyfin.models.SpatialFinItem

data class SearchState(val items: List<SpatialFinItem> = emptyList(), val loading: Boolean = false)
