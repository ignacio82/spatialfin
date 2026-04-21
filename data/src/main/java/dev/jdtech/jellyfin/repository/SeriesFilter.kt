package dev.jdtech.jellyfin.repository

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * Drops BaseItemDto entries for series that have no playable children — an empty
 * show skeleton the user can't actually watch.
 *
 * Jellyfin exposes two count fields; we prefer `recursiveItemCount` (total
 * descendants, i.e. episodes) but fall back to `childCount` (direct children,
 * i.e. seasons) because not every endpoint populates both. The request sites
 * must ask for `ItemFields.CHILD_COUNT` / `RECURSIVE_ITEM_COUNT`; without that,
 * both fields are null and the filter is conservative (keeps the item).
 */
object SeriesFilter {
    fun dropEmptyShows(items: List<BaseItemDto>): List<BaseItemDto> =
        items.filterNot { it.isEmptySeries() }

    private fun BaseItemDto.isEmptySeries(): Boolean {
        if (type != BaseItemKind.SERIES) return false
        val recursive = recursiveItemCount
        val direct = childCount
        if (recursive == null && direct == null) return false
        return (recursive ?: 0) <= 0 && (direct ?: 0) <= 0
    }
}
