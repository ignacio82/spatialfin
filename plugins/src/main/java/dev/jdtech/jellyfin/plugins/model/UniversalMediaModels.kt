package dev.jdtech.jellyfin.plugins.model

import kotlinx.serialization.Serializable

@Serializable
data class UniversalMediaItem(
    val id: String,
    val pluginId: String,
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val thumbnailUrl: String? = null,
    val videoUrl: String,
    val durationMs: Long? = null,
    val viewCount: Long? = null,
    val uploadDate: String? = null,
    val isLive: Boolean = false,
    val homeRowId: String? = null,
    /**
     * Optional spatial hints a plugin can declare for immersive XR playback.
     * [projection] is `"flat"` / `"180"` / `"360"`; [stereoMode] is
     * `"mono"` / `"sbs"` / `"top_bottom"` / `"multiview"`. When null, the XR
     * player falls back to detecting them from the title / video URL.
     */
    val projection: String? = null,
    val stereoMode: String? = null,
)

@Serializable
data class UniversalMediaPager(
    val items: List<UniversalMediaItem>,
    val hasMore: Boolean,
    val context: String? = null
)
