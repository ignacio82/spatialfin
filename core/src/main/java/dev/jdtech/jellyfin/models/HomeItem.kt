package dev.jdtech.jellyfin.models

import java.util.UUID

sealed class HomeItem {
    data object OfflineCard : HomeItem() {
        override val id: UUID = UUID.fromString("dbfef8a9-7ff0-4c36-9e36-81dfd65fdd46")
    }

    data class Suggestions(override val id: UUID, val items: List<SpatialFinItem>) : HomeItem()

    data class Section(val homeSection: HomeSection) : HomeItem() {
        override val id = homeSection.id
    }

    data class ViewItem(val view: View) : HomeItem() {
        override val id = view.id
    }

    /**
     * A configured network share (SMB/NFS) surfaced as a home row. Carries the
     * backing [shareId] so a "See all" affordance can open the share's full
     * content listing.
     */
    data class NetworkShareSection(val shareId: String, val homeSection: HomeSection) : HomeItem() {
        override val id = homeSection.id
    }

    abstract val id: UUID
}
