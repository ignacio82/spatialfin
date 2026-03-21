package dev.jdtech.jellyfin.models

import android.net.Uri
import java.util.UUID

data class NetworkVideoItem(
    val networkVideoId: String,
    val shareId: String,
    val filePath: String,
    val fileName: String,
    val sizeBytes: Long,
    val tmdbId: Int?,
    val tmdbType: String?,
    val seriesGroupKey: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val releaseYear: Int?,
    override val id: UUID = UUID.nameUUIDFromBytes("network-video:$networkVideoId".toByteArray()),
    override val name: String,
    override val originalTitle: String? = null,
    override val overview: String = "",
    override val played: Boolean = false,
    override val favorite: Boolean = false,
    override val canPlay: Boolean = true,
    override val canDownload: Boolean = false,
    override val sources: List<SpatialFinSource>,
    override val runtimeTicks: Long,
    override val playbackPositionTicks: Long,
    override val unplayedItemCount: Int? = null,
    override val images: SpatialFinImages = SpatialFinImages(),
    override val chapters: List<SpatialFinChapter> = emptyList(),
    override val ratings: List<Rating> = emptyList(),
) : SpatialFinItem
