package dev.jdtech.jellyfin.models

import java.util.UUID
import org.jellyfin.sdk.model.api.GroupInfoDto

data class SyncPlayGroup(
    val id: UUID,
    val name: String,
    val state: String,
    val participants: List<String>,
)

fun GroupInfoDto.toSyncPlayGroup(): SyncPlayGroup =
    SyncPlayGroup(
        id = groupId,
        name = groupName,
        state = state.toString(),
        participants = participants,
    )
