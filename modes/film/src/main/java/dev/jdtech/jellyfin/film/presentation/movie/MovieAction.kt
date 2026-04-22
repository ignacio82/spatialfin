package dev.jdtech.jellyfin.film.presentation.movie

import java.util.UUID

sealed interface MovieAction {
    data class Play(
        val startFromBeginning: Boolean = false,
        val mediaSourceIndex: Int? = null,
        val maxBitrate: Long? = null,
        val multitask: Boolean = false,
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

    /**
     * Fired after the user saves an IMDb ID from the external-IDs dialog.
     * Handled by the ViewModel with a short delay so Jellyfin has time to
     * finish its async metadata refresh before we re-fetch the movie and
     * replace the UI with the newly-matched title, overview, and images.
     */
    data object ReloadAfterMetadataEdit : MovieAction
}
