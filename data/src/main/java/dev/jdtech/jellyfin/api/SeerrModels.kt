package dev.jdtech.jellyfin.api

import kotlinx.serialization.Serializable

@Serializable
data class SeerrSearchResult(
    val id: Int? = null,
    val mediaType: String,
    val tmdbId: Int,
    val tvdbId: Int? = null,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val mediaInfo: SeerrMediaInfo? = null,
)

@Serializable
data class SeerrMediaInfo(
    val id: Int? = null,
    val tmdbId: Int? = null,
    val tvdbId: Int? = null,
    val status: Int, // 1 = Unknown, 2 = Pending, 3 = Processing, 4 = Partially Available, 5 = Available
    val requests: List<SeerrRequest>? = null,
)

@Serializable
data class SeerrRequest(
    val id: Int,
    val status: Int, // 1 = Pending, 2 = Approved, 3 = Declined
)

@Serializable
data class SeerrSearchResponse(
    val page: Int,
    val totalPages: Int,
    val totalResults: Int,
    val results: List<SeerrSearchResult>,
)

@Serializable
data class SeerrCreateRequest(
    val mediaType: String,
    val mediaId: Int,
    val seasons: List<Int>? = null,
    val is4k: Boolean = false,
)
