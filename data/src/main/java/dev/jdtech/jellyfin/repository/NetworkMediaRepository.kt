package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.NetworkShareDto
import dev.jdtech.jellyfin.models.NetworkVideoItem
import dev.jdtech.jellyfin.network.DiscoveredShare

interface NetworkMediaRepository {
    // Share management
    suspend fun getShares(): List<NetworkShareDto>

    suspend fun addShare(
        protocol: String,
        host: String,
        shareName: String,
        username: String?,
        password: String?,
        domain: String?,
        displayName: String?,
    ): NetworkShareDto

    suspend fun removeShare(shareId: String)

    // Discovery
    suspend fun discoverShares(): List<DiscoveredShare>

    // Scanning
    suspend fun scanShare(shareId: String)

    // Video library
    suspend fun getVideos(): List<NetworkVideoItem>

    suspend fun getVideosByShare(shareId: String): List<NetworkVideoItem>

    suspend fun getVideo(videoId: String): NetworkVideoItem?

    suspend fun searchVideos(query: String): List<NetworkVideoItem>

    suspend fun getResumeItems(): List<NetworkVideoItem>

    // Playback state
    suspend fun updatePlaybackState(videoId: String, positionMs: Long, durationMs: Long)

    suspend fun markPlayed(videoId: String, played: Boolean)

    // Metadata
    suspend fun enrichMetadata(shareId: String)

    // Streaming
    fun getStreamUrl(videoId: String): String?
}
