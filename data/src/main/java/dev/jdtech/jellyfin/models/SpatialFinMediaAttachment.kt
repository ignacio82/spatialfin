package dev.jdtech.jellyfin.models

import org.jellyfin.sdk.model.api.MediaAttachment

data class SpatialFinMediaAttachment(
    val index: Int,
    val fileName: String,
    val mimeType: String,
    val codec: String,
)

fun MediaAttachment.toSpatialFinMediaAttachment(): SpatialFinMediaAttachment {
    return SpatialFinMediaAttachment(
        index = index,
        fileName = fileName.orEmpty(),
        mimeType = mimeType.orEmpty(),
        codec = codec.orEmpty(),
    )
}
