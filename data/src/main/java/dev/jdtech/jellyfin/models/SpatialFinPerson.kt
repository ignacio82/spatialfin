package dev.jdtech.jellyfin.models

import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto

data class SpatialFinPerson(
    val id: UUID,
    val name: String,
    val overview: String,
    val images: SpatialFinImages,
)

fun BaseItemDto.toSpatialFinPerson(repository: JellyfinRepository): SpatialFinPerson {
    return SpatialFinPerson(
        id = id,
        name = name.orEmpty(),
        overview = overview.orEmpty(),
        images = toSpatialFinImages(repository),
    )
}
