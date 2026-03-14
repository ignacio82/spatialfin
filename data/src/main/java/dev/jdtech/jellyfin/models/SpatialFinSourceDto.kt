package dev.jdtech.jellyfin.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "sources")
data class SpatialFinSourceDto(
    @PrimaryKey val id: String,
    val itemId: UUID,
    val name: String,
    val type: SpatialFinSourceType,
    val path: String,
    val downloadId: Long? = null,
)

fun SpatialFinSource.toSpatialFinSourceDto(itemId: UUID, path: String): SpatialFinSourceDto {
    return SpatialFinSourceDto(
        id = id,
        itemId = itemId,
        name = name,
        type = SpatialFinSourceType.LOCAL,
        path = path,
    )
}
