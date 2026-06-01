package dev.jdtech.jellyfin.plugins.model

import androidx.core.net.toUri
import dev.jdtech.jellyfin.models.Rating
import dev.jdtech.jellyfin.models.SpatialFinChapter
import dev.jdtech.jellyfin.models.SpatialFinImages
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.SpatialFinSourceType
import java.util.UUID

data class UniversalSpatialFinItem(
    override val id: UUID,
    override val name: String,
    override val originalTitle: String? = null,
    override val overview: String = "",
    override val played: Boolean = false,
    override val favorite: Boolean = false,
    override val canPlay: Boolean = true,
    override val canDownload: Boolean = false,
    override val sources: List<SpatialFinSource> = emptyList(),
    override val runtimeTicks: Long = 0,
    override val playbackPositionTicks: Long = 0,
    override val unplayedItemCount: Int? = null,
    override val images: SpatialFinImages = SpatialFinImages(),
    override val chapters: List<SpatialFinChapter> = emptyList(),
    override val ratings: List<Rating> = emptyList(),
    val universalMediaItem: UniversalMediaItem
) : SpatialFinItem

fun UniversalMediaItem.toSpatialFinItem(): UniversalSpatialFinItem {
    return UniversalSpatialFinItem(
        id = UUID.nameUUIDFromBytes("universal:$pluginId:${homeRowId ?: "home"}:$id".toByteArray()),
        name = title,
        overview = description ?: "",
        images = SpatialFinImages(
            primary = thumbnailUrl?.toUri(),
            backdrop = thumbnailUrl?.toUri()
        ),
        sources = listOf(

            SpatialFinSource(
                id = id,
                name = title,
                type = SpatialFinSourceType.UNIVERSAL,
                path = videoUrl,
                size = 0,
                mediaStreams = emptyList()
            )
        ),
        runtimeTicks = (durationMs ?: 0L) * 10000, // Ticks are 100ns units
        universalMediaItem = this
    )
}
