package dev.jdtech.jellyfin.models

import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto

data class SpatialFinBoxSet(
    override val id: UUID,
    override val name: String,
    override val originalTitle: String? = null,
    override val overview: String = "",
    override val played: Boolean = false,
    override val favorite: Boolean = false,
    override val canPlay: Boolean = false,
    override val canDownload: Boolean = false,
    override val sources: List<SpatialFinSource> = emptyList(),
    override val runtimeTicks: Long = 0L,
    override val playbackPositionTicks: Long = 0L,
    override val unplayedItemCount: Int? = null,
    override val images: SpatialFinImages,
    override val chapters: List<SpatialFinChapter> = emptyList(),
    override val ratings: List<Rating> = emptyList(),
) : SpatialFinItem

fun BaseItemDto.toSpatialFinBoxSet(jellyfinRepository: JellyfinRepository): SpatialFinBoxSet {
    return SpatialFinBoxSet(
        id = id,
        name = name.orEmpty(),
        images = toSpatialFinImages(jellyfinRepository),
        ratings = toSpatialFinRatings(),
    )
}
