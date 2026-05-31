package dev.jdtech.jellyfin.film.presentation.home

import dev.jdtech.jellyfin.models.HomeItem
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.offline.OfflineSyncStatus
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class HomeState(
    val server: Server? = null,
    val suggestionsSection: HomeItem.Suggestions? = null,
    val resumeSection: HomeItem.Section? = null,
    val nextUpSection: HomeItem.Section? = null,
    val universalPluginSections: ImmutableList<HomeItem.Section> = persistentListOf(),
    val offlineLibrarySections: ImmutableList<HomeItem.Section> = persistentListOf(),

    val views: ImmutableList<HomeItem.ViewItem> = persistentListOf(),
    val isOfflineMode: Boolean = false,
    val isConnectionDegraded: Boolean = false,
    val manualOfflineMode: Boolean = false,
    val syncStatus: OfflineSyncStatus = OfflineSyncStatus(),
    val isLoading: Boolean = false,
    val error: HomeLoadError? = null,
)

@Immutable
data class HomeLoadError(val message: String)
