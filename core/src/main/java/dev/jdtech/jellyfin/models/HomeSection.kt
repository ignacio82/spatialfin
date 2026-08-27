package dev.jdtech.jellyfin.models

import java.util.UUID

/**
 * @param rowId stable id used by `HomeRowPreferences` to order and hide the row.
 *   Null for sections whose id is derived by the caller (Jellyfin's own resume /
 *   next-up rows) or for previews.
 */
data class HomeSection(
    val id: UUID,
    val name: UiText,
    var items: List<SpatialFinItem>,
    val rowId: String? = null,
)
