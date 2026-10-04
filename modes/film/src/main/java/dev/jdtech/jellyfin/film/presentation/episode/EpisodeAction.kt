package dev.jdtech.jellyfin.film.presentation.episode

import java.util.UUID

sealed interface EpisodeAction {
    data class Play(
        val startFromBeginning: Boolean = false,
        val force3dMode: String? = null,
        val mediaSourceIndex: Int? = null,
        val maxBitrate: Long? = null,
        val multitask: Boolean = false,
        /** Track picked on the detail screen; null defers to preferred-language resolution. */
        val audioStreamIndex: Int? = null,
        val subtitleStreamIndex: Int? = null,
        val subtitlesDisabled: Boolean = false,
    ) : EpisodeAction

    data object MarkAsPlayed : EpisodeAction

    data object UnmarkAsPlayed : EpisodeAction

    data object MarkAsFavorite : EpisodeAction

    data object UnmarkAsFavorite : EpisodeAction

    data object OnBackClick : EpisodeAction

    data object OnHomeClick : EpisodeAction

    data class NavigateToPerson(val personId: UUID) : EpisodeAction

    data class NavigateToSeason(val seasonId: UUID) : EpisodeAction

    /** A sibling in the "More from this season" row was picked. */
    data class NavigateToEpisode(val episodeId: UUID) : EpisodeAction

    data class SelectSource(val index: Int) : EpisodeAction

    /** See MovieAction.ReloadAfterMetadataEdit. */
    data object ReloadAfterMetadataEdit : EpisodeAction
}
