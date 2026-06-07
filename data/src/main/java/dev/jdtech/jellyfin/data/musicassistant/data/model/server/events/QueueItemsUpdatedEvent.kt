package dev.jdtech.jellyfin.data.musicassistant.data.model.server.events

import dev.jdtech.jellyfin.data.musicassistant.data.model.server.EventType
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerQueue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class QueueItemsUpdatedEvent(
    @SerialName("event") override val event: EventType,
    @SerialName("object_id") override val objectId: String,
    @SerialName("data") override val data: ServerQueue,
) : Event<ServerQueue>
