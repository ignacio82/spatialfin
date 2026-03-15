package dev.jdtech.jellyfin.film.presentation.movie

import java.util.UUID

sealed interface MovieAction {
    data class Play(
        val startFromBeginning: Boolean = false,
        val mediaSourceIndex: Int? = null,
        val maxBitrate: Long? = null,
    ) : MovieAction

    data class SelectVersion(val movieId: UUID) : MovieAction

    data class PlayTrailer(val trailer: String) : MovieAction

    data object MarkAsPlayed : MovieAction

    data object UnmarkAsPlayed : MovieAction

    data object MarkAsFavorite : MovieAction

    data object UnmarkAsFavorite : MovieAction

    data object OnBackClick : MovieAction

    data object OnHomeClick : MovieAction

    data class NavigateToPerson(val personId: UUID) : MovieAction
}
