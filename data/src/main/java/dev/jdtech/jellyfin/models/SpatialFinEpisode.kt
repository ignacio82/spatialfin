package dev.jdtech.jellyfin.models

import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import org.jellyfin.sdk.model.DateTime
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.PlayAccess

data class SpatialFinEpisode(
    override val id: UUID,
    override val name: String,
    override val originalTitle: String?,
    override val overview: String,
    val indexNumber: Int,
    val indexNumberEnd: Int?,
    val parentIndexNumber: Int,
    override val sources: List<SpatialFinSource>,
    override val played: Boolean,
    override val favorite: Boolean,
    override val canPlay: Boolean,
    override val canDownload: Boolean,
    override val runtimeTicks: Long,
    override val playbackPositionTicks: Long,
    val premiereDate: DateTime?,
    val seriesId: UUID,
    val seriesName: String,
    val seasonId: UUID,
    val seasonName: String?,
    val communityRating: Float?,
    val people: List<SpatialFinItemPerson>,
    override val unplayedItemCount: Int? = null,
    val missing: Boolean = false,
    override val images: SpatialFinImages,
    override val chapters: List<SpatialFinChapter>,
    override val trickplayInfo: Map<String, SpatialFinTrickplayInfo>?,
) : SpatialFinItem, SpatialFinSources

suspend fun BaseItemDto.toSpatialFinEpisode(
    jellyfinRepository: JellyfinRepository,
    database: ServerDatabaseDao? = null,
): SpatialFinEpisode? {
    val sources = mutableListOf<SpatialFinSource>()
    sources.addAll(mediaSources?.map { it.toSpatialFinSource(jellyfinRepository, id) } ?: emptyList())
    if (database != null) {
        sources.addAll(database.getSources(id).map { it.toSpatialFinSource(database) })
    }
    return try {
        SpatialFinEpisode(
            id = id,
            name = name.orEmpty(),
            originalTitle = originalTitle,
            overview = overview.orEmpty(),
            indexNumber = indexNumber ?: 0,
            indexNumberEnd = indexNumberEnd,
            parentIndexNumber = parentIndexNumber ?: 0,
            sources = sources,
            played = userData?.played == true,
            favorite = userData?.isFavorite == true,
            canPlay = playAccess != PlayAccess.NONE,
            canDownload = canDownload == true,
            runtimeTicks = runTimeTicks ?: 0,
            playbackPositionTicks = userData?.playbackPositionTicks ?: 0L,
            premiereDate = premiereDate,
            seriesId = seriesId!!,
            seriesName = seriesName.orEmpty(),
            seasonId = seasonId!!,
            seasonName = seasonName,
            communityRating = communityRating,
            people = people?.map { it.toSpatialFinPerson(jellyfinRepository) } ?: emptyList(),
            missing = locationType == LocationType.VIRTUAL,
            images = toSpatialFinImages(jellyfinRepository),
            chapters = toSpatialFinChapters(),
            trickplayInfo =
                trickplay?.mapValues { it.value[it.value.keys.max()]!!.toSpatialFinTrickplayInfo() },
        )
    } catch (_: NullPointerException) {
        null
    }
}

fun SpatialFinEpisodeDto.toSpatialFinEpisode(
    database: ServerDatabaseDao,
    userId: UUID,
): SpatialFinEpisode {
    val userData = database.getUserDataOrCreateNew(id, userId)
    val sources = database.getSources(id).map { it.toSpatialFinSource(database) }
    val trickplayInfos = mutableMapOf<String, SpatialFinTrickplayInfo>()
    for (source in sources) {
        database.getTrickplayInfo(source.id)?.toSpatialFinTrickplayInfo()?.let {
            trickplayInfos[source.id] = it
        }
    }
    return SpatialFinEpisode(
        id = id,
        name = name,
        originalTitle = "",
        overview = overview,
        indexNumber = indexNumber,
        indexNumberEnd = indexNumberEnd,
        parentIndexNumber = parentIndexNumber,
        sources = sources,
        played = userData.played,
        favorite = userData.favorite,
        canPlay = true,
        canDownload = false,
        runtimeTicks = runtimeTicks,
        playbackPositionTicks = userData.playbackPositionTicks,
        premiereDate = premiereDate,
        seriesId = seriesId,
        seriesName = seriesName,
        seasonId = seasonId,
        seasonName = null,
        communityRating = communityRating,
        people = emptyList(),
        images = toLocalSpatialFinImages(itemId = id),
        chapters = chapters ?: emptyList(),
        trickplayInfo = trickplayInfos,
    )
}
