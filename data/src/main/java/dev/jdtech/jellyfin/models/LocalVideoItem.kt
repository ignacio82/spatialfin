package dev.jdtech.jellyfin.models

import android.net.Uri
import java.util.UUID

data class LocalVideoItem(
    val mediaStoreId: Long,
    val contentUri: Uri,
    val fileName: String,
    val folderName: String?,
    val sizeBytes: Long,
    val dateAddedEpochSeconds: Long,
    val durationMs: Long,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val productionYear: Int? = null,
    override val id: UUID = UUID.nameUUIDFromBytes("local-video:$mediaStoreId".toByteArray()),
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
