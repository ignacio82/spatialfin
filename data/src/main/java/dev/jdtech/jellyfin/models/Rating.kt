package dev.jdtech.jellyfin.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Rating(
    val type: RatingType,
    val value: String,
    val url: String? = null
) : Parcelable

@Parcelize
enum class RatingType(val label: String) : Parcelable {
    ANILIST("AniList"),
    IMDB("IMDb"),
    LETTERBOXD("Letterboxd"),
    METACRITIC("Metacritic"),
    METACRITIC_USER("Metacritic User"),
    MYANIMELIST("MyAnimeList"),
    ROGER_EBERT("Roger Ebert"),
    ROTTEN_TOMATOES_AUDIENCE("Rotten Tomatoes (Audience)"),
    ROTTEN_TOMATOES_CRITICS("Rotten Tomatoes (Critics)"),
    TMDB("TMDb"),
    TRAKT("Trakt"),
    UNKNOWN("Unknown");

    companion object {
        fun fromLabel(label: String): RatingType {
            val normalized = label.trim().lowercase()
            return when {
                normalized.contains("anilist") -> ANILIST
                normalized.contains("imdb") -> IMDB
                normalized.contains("letterboxd") -> LETTERBOXD
                normalized.contains("metacritic user") -> METACRITIC_USER
                normalized.contains("metacritic") -> METACRITIC
                normalized.contains("myanimelist") -> MYANIMELIST
                normalized.contains("roger ebert") -> ROGER_EBERT
                normalized.contains("rotten tomatoes (audience)") || normalized.contains("rt audience") -> ROTTEN_TOMATOES_AUDIENCE
                normalized.contains("rotten tomatoes (critics)") || normalized.contains("rotten tomatoes") || normalized.contains("rt critics") -> ROTTEN_TOMATOES_CRITICS
                normalized.contains("tmdb") || normalized.contains("the movie database") -> TMDB
                normalized.contains("trakt") -> TRAKT
                else -> entries.find { it.label.equals(label, ignoreCase = true) } ?: UNKNOWN
            }
        }
    }
}
