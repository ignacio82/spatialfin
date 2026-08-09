package dev.jdtech.jellyfin.models

import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.time.LocalDateTime
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto

/**
 * A still image from a Jellyfin "Home videos and photos" / "Photos" library.
 *
 * Photos are not playable, so [canPlay] and [sources] stay empty and the browse
 * screens route them to the photo viewer instead of the player. [parentId] is what
 * lets the viewer page through the rest of the folder without the caller having to
 * hand it a list.
 */
data class SpatialFinPhoto(
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
    val parentId: UUID? = null,
    val width: Int? = null,
    val height: Int? = null,
    val taken: LocalDateTime? = null,
) : SpatialFinItem

fun BaseItemDto.toSpatialFinPhoto(jellyfinRepository: JellyfinRepository): SpatialFinPhoto {
    return SpatialFinPhoto(
        id = id,
        name = name.orEmpty(),
        overview = overview.orEmpty(),
        played = userData?.played == true,
        favorite = userData?.isFavorite == true,
        images = toSpatialFinImages(jellyfinRepository),
        ratings = toSpatialFinRatings(),
        parentId = parentId,
        width = width,
        height = height,
        // Jellyfin puts the EXIF capture date in premiereDate for photos and falls
        // back to the file's creation date when the image carries no EXIF.
        taken = premiereDate ?: dateCreated,
    )
}
