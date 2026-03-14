package dev.jdtech.jellyfin.models

import androidx.room.Entity
import java.util.UUID

@Entity(tableName = "segments", primaryKeys = ["itemId", "type"])
data class SpatialFinSegmentDto(
    val itemId: UUID,
    val type: SpatialFinSegmentType,
    val startTicks: Long,
    val endTicks: Long,
)

fun SpatialFinSegment.toSpatialFinSegmentsDto(itemId: UUID): SpatialFinSegmentDto {
    return SpatialFinSegmentDto(
        itemId = itemId,
        type = type,
        startTicks = startTicks,
        endTicks = endTicks,
    )
}
