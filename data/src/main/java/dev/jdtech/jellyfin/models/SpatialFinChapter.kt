package dev.jdtech.jellyfin.models

import kotlinx.serialization.Serializable
import org.jellyfin.sdk.model.api.BaseItemDto

@Serializable
data class SpatialFinChapter(
    /** The start position. */
    val startPosition: Long,
    /** The name. */
    val name: String? = null,
)

fun BaseItemDto.toSpatialFinChapters(): List<SpatialFinChapter> {
    return chapters?.map { chapter ->
        SpatialFinChapter(startPosition = chapter.startPositionTicks / 10000, name = chapter.name)
    } ?: emptyList()
}
