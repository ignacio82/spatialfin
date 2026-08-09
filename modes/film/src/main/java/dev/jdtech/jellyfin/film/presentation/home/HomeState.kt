package dev.jdtech.jellyfin.film.presentation.home

import dev.jdtech.jellyfin.models.HomeItem
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.SpatialFinCollection
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
    val musicAssistantSections: ImmutableList<HomeItem.Section> = persistentListOf(),
    val offlineLibrarySections: ImmutableList<HomeItem.Section> = persistentListOf(),
    val networkShareSections: ImmutableList<HomeItem.NetworkShareSection> = persistentListOf(),

    /**
     * "Latest in <library>" rows. Gated on the `homeLatest` preference and pruned to
     * libraries that actually returned recent media — so this is a *content* list, not
     * an inventory of the server. Use [libraries] whenever you need the latter.
     */
    val views: ImmutableList<HomeItem.ViewItem> = persistentListOf(),
    /**
     * Every library on the server, independent of the `homeLatest` preference and of
     * whether a library has recent media. The TV and Beam library pickers read this;
     * deriving them from [views] used to leave those shells with no way at all to reach
     * a library once "Latest" was switched off.
     */
    val libraries: ImmutableList<SpatialFinCollection> = persistentListOf(),
    val isOfflineMode: Boolean = false,
    val isConnectionDegraded: Boolean = false,
    val manualOfflineMode: Boolean = false,
    val syncStatus: OfflineSyncStatus = OfflineSyncStatus(),
    val isLoading: Boolean = false,
    val error: HomeLoadError? = null,
)

@Immutable
data class HomeLoadError(val message: String)
