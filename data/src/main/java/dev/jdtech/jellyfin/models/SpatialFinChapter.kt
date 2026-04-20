package dev.jdtech.jellyfin.models

import android.net.Uri
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.model.api.BaseItemDto

@Serializable
data class SpatialFinChapter(
    /** The start position. */
    val startPosition: Long,
    /** The name. */
    val name: String? = null,
    /** Chapter thumbnail image URI. Built from Jellyfin's `/Items/{id}/Images/Chapter/{index}?tag=…`
     *  at mapping time so the UI layer can render without knowing the server URL.
     *  Marked @Transient so Room's JSON converter skips it — offline items are refetched
     *  when back online, and chapter thumbnails aren't stored locally. */
    @kotlinx.serialization.Transient
    val imageUri: Uri? = null,
)

fun BaseItemDto.toSpatialFinChapters(
    repository: JellyfinRepository? = null,
    itemId: UUID? = null,
): List<SpatialFinChapter> {
    val baseUrl = repository?.let { Uri.parse(it.getBaseUrl()) }
    return chapters?.mapIndexed { index, chapter ->
        val imageUri =
            if (baseUrl != null && itemId != null && chapter.imageTag != null) {
                baseUrl
                    .buildUpon()
                    .appendEncodedPath("items/$itemId/Images/Chapter/$index")
                    .appendQueryParameter("tag", chapter.imageTag)
                    .build()
            } else {
                null
            }
        SpatialFinChapter(
            startPosition = chapter.startPositionTicks / 10000,
            name = chapter.name,
            imageUri = imageUri,
        )
    } ?: emptyList()
}

/** Legacy callers without access to a repository (e.g. offline DTOs). */
fun BaseItemDto.toSpatialFinChapters(): List<SpatialFinChapter> =
    toSpatialFinChapters(repository = null, itemId = null)
