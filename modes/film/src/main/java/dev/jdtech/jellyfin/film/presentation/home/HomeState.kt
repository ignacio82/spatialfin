package dev.jdtech.jellyfin.film.presentation.home

import dev.jdtech.jellyfin.models.HomeItem
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.offline.OfflineSyncStatus

data class HomeState(
    val server: Server? = null,
    val suggestionsSection: HomeItem.Suggestions? = null,
    val resumeSection: HomeItem.Section? = null,
    val nextUpSection: HomeItem.Section? = null,
    val offlineLibrarySections: List<HomeItem.Section> = emptyList(),
    val views: List<HomeItem.ViewItem> = emptyList(),
    val isOfflineMode: Boolean = false,
    val isConnectionDegraded: Boolean = false,
    val manualOfflineMode: Boolean = false,
    val syncStatus: OfflineSyncStatus = OfflineSyncStatus(),
    val isLoading: Boolean = false,
    val error: Exception? = null,
)
