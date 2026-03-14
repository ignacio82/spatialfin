package dev.jdtech.jellyfin.models

import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.PlayAccess

data class SpatialFinSeason(
    override val id: UUID,
    override val name: String,
    val seriesId: UUID,
    val seriesName: String,
    override val originalTitle: String?,
    override val overview: String,
    override val sources: List<SpatialFinSource>,
    val indexNumber: Int,
    val episodes: Collection<SpatialFinEpisode>,
    override val played: Boolean,
    override val favorite: Boolean,
    override val canPlay: Boolean,
    override val canDownload: Boolean,
    override val runtimeTicks: Long = 0L,
    override val playbackPositionTicks: Long = 0L,
    override val unplayedItemCount: Int?,
    override val images: SpatialFinImages,
    override val chapters: List<SpatialFinChapter> = emptyList(),
) : SpatialFinItem

fun BaseItemDto.toSpatialFinSeason(jellyfinRepository: JellyfinRepository): SpatialFinSeason {
    return SpatialFinSeason(
        id = id,
        name = name.orEmpty(),
        originalTitle = originalTitle,
        overview = overview.orEmpty(),
        played = userData?.played == true,
        favorite = userData?.isFavorite == true,
        canPlay = playAccess != PlayAccess.NONE,
        canDownload = canDownload == true,
        unplayedItemCount = userData?.unplayedItemCount,
        indexNumber = indexNumber ?: 0,
        sources = emptyList(),
        episodes = emptyList(),
        seriesId = seriesId!!,
        seriesName = seriesName.orEmpty(),
        images = toSpatialFinImages(jellyfinRepository),
    )
}

fun SpatialFinSeasonDto.toSpatialFinSeason(database: ServerDatabaseDao, userId: UUID): SpatialFinSeason {
    val userData = database.getUserDataOrCreateNew(id, userId)
    return SpatialFinSeason(
        id = id,
        name = name,
        originalTitle = null,
        overview = overview,
        played = userData.played,
        favorite = userData.favorite,
        canPlay = true,
        canDownload = false,
        unplayedItemCount = null,
        indexNumber = indexNumber,
        sources = emptyList(),
        episodes = emptyList(),
        seriesId = seriesId,
        seriesName = seriesName,
        images = toLocalSpatialFinImages(itemId = id),
    )
}
