package dev.jdtech.jellyfin.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "networkShares")
data class NetworkShareDto(
    @PrimaryKey val id: String,
    val protocol: String,
    val host: String,
    val shareName: String,
    val path: String,
    val displayName: String?,
    val username: String?,
    val password: String?,
    val domain: String?,
    val addedAtEpochMs: Long,
    val lastScannedAtEpochMs: Long?,
)
