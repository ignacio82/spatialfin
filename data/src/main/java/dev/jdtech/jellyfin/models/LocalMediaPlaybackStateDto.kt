package dev.jdtech.jellyfin.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "localMediaPlaybackState")
data class LocalMediaPlaybackStateDto(
    @PrimaryKey val mediaStoreId: Long,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val watched: Boolean = false,
    val lastPlayedAtEpochMs: Long = 0L,
)
