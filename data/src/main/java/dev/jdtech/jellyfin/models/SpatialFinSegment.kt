package dev.jdtech.jellyfin.models

import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType

enum class SpatialFinSegmentType {
    INTRO,
    OUTRO,
    RECAP,
    PREVIEW,
    COMMERCIAL,
    UNKNOWN,
}

private fun MediaSegmentType.toSpatialFinSegmentType(): SpatialFinSegmentType =
    when (this) {
        MediaSegmentType.UNKNOWN -> SpatialFinSegmentType.UNKNOWN
        MediaSegmentType.INTRO -> SpatialFinSegmentType.INTRO
        MediaSegmentType.OUTRO -> SpatialFinSegmentType.OUTRO
        MediaSegmentType.RECAP -> SpatialFinSegmentType.RECAP
        MediaSegmentType.PREVIEW -> SpatialFinSegmentType.PREVIEW
        MediaSegmentType.COMMERCIAL -> SpatialFinSegmentType.COMMERCIAL
    }

data class SpatialFinSegment(val type: SpatialFinSegmentType, val startTicks: Long, val endTicks: Long)

fun SpatialFinSegmentDto.toSpatialFinSegment(): SpatialFinSegment {
    return SpatialFinSegment(type = type, startTicks = startTicks, endTicks = endTicks)
}

fun MediaSegmentDto.toSpatialFinSegment(): SpatialFinSegment {
    return SpatialFinSegment(
        type = type.toSpatialFinSegmentType(),
        startTicks = startTicks / 10000,
        endTicks = endTicks / 10000,
    )
}
