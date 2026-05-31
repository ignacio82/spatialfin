package dev.jdtech.jellyfin.plugins.model

import kotlinx.serialization.Serializable

@Serializable
data class ResolvedVideoUrl(
    val url: String,
    val mimeType: String? = null,
    val videoUrl: String? = null,
    val audioUrl: String? = null,
    val videoMimeType: String? = null,
    val audioMimeType: String? = null
)