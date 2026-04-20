package dev.jdtech.jellyfin.models

import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.time.LocalDateTime
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.PlayAccess

data class SpatialFinMovie(
    override val id: UUID,
    override val name: String,
    override val originalTitle: String?,
    override val overview: String,
    override val sources: List<SpatialFinSource>,
    override val played: Boolean,
    override val favorite: Boolean,
    override val canPlay: Boolean,
    override val canDownload: Boolean,
    override val runtimeTicks: Long,
    override val playbackPositionTicks: Long,
    val premiereDate: LocalDateTime?,
    val people: List<SpatialFinItemPerson>,
    val genres: List<String>,
    val communityRating: Float?,
    val officialRating: String?,
    val status: String,
    val productionYear: Int?,
    val endDate: LocalDateTime?,
    val trailer: String?,
    val video3DFormat: String? = null,
    override val unplayedItemCount: Int? = null,
    override val images: SpatialFinImages,
    override val chapters: List<SpatialFinChapter>,
    override val ratings: List<Rating> = emptyList(),
    override val trickplayInfo: Map<String, SpatialFinTrickplayInfo>?,
) : SpatialFinItem, SpatialFinSources

suspend fun BaseItemDto.toSpatialFinMovie(
    jellyfinRepository: JellyfinRepository,
    serverDatabase: ServerDatabaseDao? = null,
): SpatialFinMovie {
    val sources = mutableListOf<SpatialFinSource>()
    sources.addAll(mediaSources?.map { it.toSpatialFinSource(jellyfinRepository, id) } ?: emptyList())
    if (serverDatabase != null) {
        sources.addAll(serverDatabase.getSources(id).map { it.toSpatialFinSource(serverDatabase) })
    }
    return SpatialFinMovie(
        id = id,
        name = name.orEmpty(),
        originalTitle = originalTitle,
        overview = overview.orEmpty(),
        sources = sources,
        played = userData?.played == true,
        favorite = userData?.isFavorite == true,
        canPlay = playAccess != PlayAccess.NONE,
        canDownload = canDownload == true,
        runtimeTicks = runTimeTicks ?: 0,
        playbackPositionTicks = userData?.playbackPositionTicks ?: 0,
        premiereDate = premiereDate,
        communityRating = communityRating,
        genres = genres ?: emptyList(),
        people = people?.map { it.toSpatialFinPerson(jellyfinRepository) } ?: emptyList(),
        officialRating = officialRating,
        status = status ?: "Ended",
        productionYear = productionYear,
        endDate = endDate,
        trailer = remoteTrailers?.getOrNull(0)?.url,
        video3DFormat = video3dFormat?.name,
        images = toSpatialFinImages(jellyfinRepository),
        chapters = toSpatialFinChapters(jellyfinRepository, id),
        ratings = toSpatialFinRatings(),
        trickplayInfo =
            trickplay?.mapValues { it.value[it.value.keys.max()]!!.toSpatialFinTrickplayInfo() },
    )
}

fun SpatialFinMovieDto.toSpatialFinMovie(database: ServerDatabaseDao, userId: UUID): SpatialFinMovie {
    val userData = database.getUserDataOrCreateNew(id, userId)
    val sources = database.getSources(id).map { it.toSpatialFinSource(database) }
    val trickplayInfos = mutableMapOf<String, SpatialFinTrickplayInfo>()
    for (source in sources) {
        database.getTrickplayInfo(source.id)?.toSpatialFinTrickplayInfo()?.let {
            trickplayInfos[source.id] = it
        }
    }
    return SpatialFinMovie(
        id = id,
        name = name,
        originalTitle = originalTitle,
        overview = overview,
        played = userData.played,
        favorite = userData.favorite,
        runtimeTicks = runtimeTicks,
        playbackPositionTicks = userData.playbackPositionTicks,
        premiereDate = premiereDate,
        genres = emptyList(),
        people = emptyList(),
        communityRating = communityRating,
        officialRating = officialRating,
        status = status,
        productionYear = productionYear,
        endDate = endDate,
        canDownload = false,
        canPlay = true,
        sources = database.getSources(id).map { it.toSpatialFinSource(database) },
        trailer = null,
        images = toLocalSpatialFinImages(itemId = id),
        chapters = chapters ?: emptyList(),
        trickplayInfo = trickplayInfos,
    )
}
