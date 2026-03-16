package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.LocalVideoItem

interface LocalMediaRepository {
    suspend fun getVideos(): List<LocalVideoItem>

    suspend fun searchVideos(query: String): List<LocalVideoItem>

    suspend fun getVideo(mediaStoreId: Long): LocalVideoItem?

    suspend fun updatePlaybackState(mediaStoreId: Long, positionMs: Long, durationMs: Long)

    suspend fun markPlayed(mediaStoreId: Long, played: Boolean)
}
