package dev.jdtech.jellyfin.models

import org.jellyfin.sdk.model.api.TrickplayInfoDto

data class SpatialFinTrickplayInfo(
    val width: Int,
    val height: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val thumbnailCount: Int,
    val interval: Int,
    val bandwidth: Int,
)

fun TrickplayInfoDto.toSpatialFinTrickplayInfo(): SpatialFinTrickplayInfo {
    return SpatialFinTrickplayInfo(
        width = width,
        height = height,
        tileWidth = tileWidth,
        tileHeight = tileHeight,
        thumbnailCount = thumbnailCount,
        interval = interval,
        bandwidth = bandwidth,
    )
}

fun SpatialFinTrickplayInfoDto.toSpatialFinTrickplayInfo(): SpatialFinTrickplayInfo {
    return SpatialFinTrickplayInfo(
        width = width,
        height = height,
        tileWidth = tileWidth,
        tileHeight = tileHeight,
        thumbnailCount = thumbnailCount,
        interval = interval,
        bandwidth = bandwidth,
    )
}
