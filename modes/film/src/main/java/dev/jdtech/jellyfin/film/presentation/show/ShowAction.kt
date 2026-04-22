package dev.jdtech.jellyfin.film.presentation.show

import dev.jdtech.jellyfin.models.SpatialFinItem
import java.util.UUID

sealed interface ShowAction {
    data class Play(val startFromBeginning: Boolean = false, val multitask: Boolean = false) : ShowAction

    data class PlayTrailer(val trailer: String) : ShowAction

    data object MarkAsPlayed : ShowAction

    data object UnmarkAsPlayed : ShowAction

    data object MarkAsFavorite : ShowAction

    data object UnmarkAsFavorite : ShowAction

    data object OnBackClick : ShowAction

    data object OnHomeClick : ShowAction

    data class NavigateToItem(val item: SpatialFinItem) : ShowAction

    data class NavigateToPerson(val personId: UUID) : ShowAction

    /** See MovieAction.ReloadAfterMetadataEdit. */
    data object ReloadAfterMetadataEdit : ShowAction
}
