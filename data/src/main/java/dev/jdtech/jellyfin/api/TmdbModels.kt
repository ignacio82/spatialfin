package dev.jdtech.jellyfin.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TmdbSearchResponse<T>(
    val page: Int = 1,
    val results: List<T> = emptyList(),
    @SerialName("total_results") val totalResults: Int = 0,
)

@Serializable
data class TmdbMovieResult(
    val id: Int,
    val title: String = "",
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
)

@Serializable
data class TmdbTvResult(
    val id: Int,
    val name: String = "",
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
)

@Serializable
data class TmdbMovieDetails(
    val id: Int,
    val title: String = "",
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val runtime: Int? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val credits: TmdbCredits? = null,
)

@Serializable
data class TmdbTvDetails(
    val id: Int,
    val name: String = "",
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("number_of_seasons") val numberOfSeasons: Int = 0,
    val genres: List<TmdbGenre> = emptyList(),
    val credits: TmdbCredits? = null,
)

@Serializable
data class TmdbGenre(
    val id: Int,
    val name: String = "",
)

@Serializable
data class TmdbCredits(
    val cast: List<TmdbCastMember> = emptyList(),
    val crew: List<TmdbCrewMember> = emptyList(),
)

@Serializable
data class TmdbCastMember(
    val id: Int,
    val name: String = "",
    val character: String = "",
    @SerialName("profile_path") val profilePath: String? = null,
    val order: Int = 0,
)

@Serializable
data class TmdbCrewMember(
    val id: Int,
    val name: String = "",
    val job: String = "",
    val department: String = "",
)

@Serializable
data class TmdbConfiguration(
    val images: TmdbImagesConfiguration,
)

@Serializable
data class TmdbImagesConfiguration(
    @SerialName("secure_base_url") val secureBaseUrl: String = "https://image.tmdb.org/t/p/",
    @SerialName("poster_sizes") val posterSizes: List<String> = emptyList(),
    @SerialName("backdrop_sizes") val backdropSizes: List<String> = emptyList(),
)
