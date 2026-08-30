package dev.jdtech.jellyfin.film.presentation.home

import dev.jdtech.jellyfin.models.HomeItem
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.SpatialFinCollection
import dev.jdtech.jellyfin.offline.OfflineSyncStatus
import dev.jdtech.jellyfin.settings.domain.HomeRowIds
import dev.jdtech.jellyfin.settings.domain.HomeRowLayout
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

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
    /**
     * Starts true: every shell calls `loadData()` from a `LaunchedEffect` as it
     * enters, so "loading" is the honest first state. Defaulting to false meant
     * the first frame rendered the *loaded* layout over empty data — a bare top
     * bar, no hero, no rows — which read as a broken gray screen for as long as
     * the first fetch took.
     */
    val isLoading: Boolean = true,
    val error: HomeLoadError? = null,
)

@Immutable
data class HomeLoadError(val message: String)

/** The layout row id for a section, falling back to its section-declared id. */
val HomeItem.Section.rowId: String
    get() = homeSection.rowId ?: homeSection.id.toString()

/** The layout row id for a "Latest in <library>" row. */
val HomeItem.ViewItem.rowId: String
    get() = HomeRowIds.latest(view.id)

/** The layout row id for a network share row. */
val HomeItem.NetworkShareSection.rowId: String
    get() = HomeRowIds.networkShare(shareId)

/**
 * Applies the user's saved row layout to a freshly loaded — or cached — home
 * state: hidden rows are dropped and the remaining rows within each family are
 * put into the saved order. Cross-family ordering is applied by each shell when
 * it flattens these fields into one list, since only the shell knows which rows
 * it actually renders.
 */
fun HomeState.arrangedBy(layout: HomeRowLayout): HomeState =
    copy(
        suggestionsSection = suggestionsSection?.takeIf { layout.isVisible(HomeRowIds.SUGGESTIONS) },
        resumeSection = resumeSection?.takeIf { layout.isVisible(HomeRowIds.CONTINUE_WATCHING) },
        nextUpSection = nextUpSection?.takeIf { layout.isVisible(HomeRowIds.NEXT_UP) },
        universalPluginSections =
            layout.arrange(universalPluginSections) { it.rowId }.toImmutableList(),
        musicAssistantSections =
            layout.arrange(musicAssistantSections) { it.rowId }.toImmutableList(),
        offlineLibrarySections =
            layout.arrange(offlineLibrarySections) { it.rowId }.toImmutableList(),
        networkShareSections = layout.arrange(networkShareSections) { it.rowId }.toImmutableList(),
        views = layout.arrange(views) { it.rowId }.toImmutableList(),
    )
