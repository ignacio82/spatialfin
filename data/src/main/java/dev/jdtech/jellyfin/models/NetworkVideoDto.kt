package dev.jdtech.jellyfin.models

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "networkVideos",
    foreignKeys = [
        ForeignKey(
            entity = NetworkShareDto::class,
            parentColumns = ["id"],
            childColumns = ["shareId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("shareId")],
)
data class NetworkVideoDto(
    @PrimaryKey val id: String,
    val shareId: String,
    val filePath: String,
    val fileName: String,
    val sizeBytes: Long,
    val durationMs: Long?,
    val tmdbId: Int?,
    val tmdbType: String?,
    val title: String,
    val overview: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val voteAverage: Double?,
    val releaseYear: Int?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val seriesGroupKey: String?,
    val lastModifiedEpochMs: Long?,
    val metadataFetchedAtEpochMs: Long?,
    val genres: String?,    // comma-separated genre names from TMDB/OMDb
    val director: String?,  // director name from TMDB credits / OMDb
    val writers: String?,   // comma-separated writer names from TMDB credits / OMDb
    val imdbId: String?,    // IMDb ID from OMDb
    val imdbRating: String?, // IMDb rating from OMDb (e.g. "7.4")
)
