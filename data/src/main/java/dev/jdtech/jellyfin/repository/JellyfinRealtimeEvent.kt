package dev.jdtech.jellyfin.repository

import java.util.UUID

sealed interface JellyfinRealtimeEvent {
    val itemIds: Set<UUID>

    fun affects(itemId: UUID): Boolean = itemId in itemIds

    data class UserDataChanged(
        val userId: UUID,
        override val itemIds: Set<UUID>,
    ) : JellyfinRealtimeEvent

    data class LibraryChanged(
        val addedItemIds: Set<UUID> = emptySet(),
        val updatedItemIds: Set<UUID> = emptySet(),
        val removedItemIds: Set<UUID> = emptySet(),
    ) : JellyfinRealtimeEvent {
        override val itemIds: Set<UUID> = addedItemIds + updatedItemIds + removedItemIds
    }
}
