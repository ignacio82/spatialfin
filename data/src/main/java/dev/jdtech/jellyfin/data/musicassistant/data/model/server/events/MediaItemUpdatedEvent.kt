package dev.jdtech.jellyfin.data.musicassistant.data.model.server.events

import dev.jdtech.jellyfin.data.musicassistant.data.model.server.EventType
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerMediaItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MediaItemUpdatedEvent(
    @SerialName("event") override val event: EventType,
    @SerialName("object_id") override val objectId: String,
    @SerialName("data") override val data: ServerMediaItem,
) : Event<ServerMediaItem>
