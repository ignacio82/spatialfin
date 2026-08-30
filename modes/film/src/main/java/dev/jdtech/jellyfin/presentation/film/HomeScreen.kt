package dev.jdtech.jellyfin.presentation.film

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skydoves.compose.stability.runtime.TraceRecomposition
import kotlinx.collections.immutable.persistentListOf
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyHomeSection
import dev.jdtech.jellyfin.core.presentation.dummy.dummyHomeSuggestions
import dev.jdtech.jellyfin.core.presentation.dummy.dummyHomeView
import dev.jdtech.jellyfin.core.presentation.dummy.dummyServer
import dev.jdtech.jellyfin.film.presentation.home.HomeAction
import dev.jdtech.jellyfin.film.presentation.home.HomeState
import dev.jdtech.jellyfin.film.presentation.home.HomeViewModel
import dev.jdtech.jellyfin.film.presentation.home.rowId
import dev.jdtech.jellyfin.film.R as FilmR
import dev.jdtech.jellyfin.models.HomeItem
import dev.jdtech.jellyfin.models.SpatialFinCollection
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.View
import dev.jdtech.jellyfin.models.deduplicateMovieVersions
import dev.jdtech.jellyfin.models.movieVersionGroupKey
import dev.jdtech.jellyfin.presentation.components.ErrorDialog
import dev.jdtech.jellyfin.presentation.components.FinishSetupCard
import dev.jdtech.jellyfin.presentation.components.HiddenHomeRowsCard
import dev.jdtech.jellyfin.presentation.components.HomeRowArrangeState
import dev.jdtech.jellyfin.presentation.film.components.HomeCarousel
import dev.jdtech.jellyfin.presentation.film.components.HomeHeader
import dev.jdtech.jellyfin.presentation.film.components.HomeSection
import dev.jdtech.jellyfin.presentation.film.components.HomeSkeleton
import dev.jdtech.jellyfin.presentation.film.components.HomeView
import dev.jdtech.jellyfin.presentation.player.PlayRequest
import dev.jdtech.jellyfin.presentation.film.components.ServerSelectionBottomSheet
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.HomeRowIds
import dev.jdtech.jellyfin.settings.domain.HomeRowLayout
import dev.jdtech.jellyfin.settings.domain.restorableHiddenRows
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.spatialfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding
import kotlinx.coroutines.launch

import android.app.Activity
import timber.log.Timber

@Composable
@TraceRecomposition(tag = "home", threshold = 3)
fun HomeScreen(
    appPreferences: AppPreferences,
    onLibraryClick: (library: SpatialFinCollection) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onManageServers: () -> Unit,
    onReconnectClick: () -> Unit,
    onLanguageSettingsClick: () -> Unit,
    onItemClick: (item: SpatialFinItem) -> Unit,
    onPluginBrowse: (pluginId: String, rowId: String?) -> Unit,
    onNetworkShareSeeAll: (shareId: String) -> Unit,
    onPlay: (PlayRequest) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rowLayout by viewModel.rowLayout.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // One-shot language prompt: once dismissed it never reappears. Language
    // setup stays reachable from Settings. Persisted so dismissal survives
    // restarts; local state drives instant recomposition.
    var languagesDismissed by remember {
        mutableStateOf(appPreferences.getValue(appPreferences.finishSetupLanguagesDismissed))
    }
    val needsLanguageSetup =
        appPreferences.getValue(appPreferences.smartSpokenLanguages).isNullOrBlank() &&
            !languagesDismissed
    val displayRatings = appPreferences.getValue(appPreferences.displayRatings)

    LaunchedEffect(true) { viewModel.loadData() }

    HomeScreenLayout(
        state = state,
        displayRatings = displayRatings,
        needsLanguageSetup = needsLanguageSetup,
        onLanguageSettingsClick = onLanguageSettingsClick,
        onDismissLanguagePrompt = {
            appPreferences.setValue(appPreferences.finishSetupLanguagesDismissed, true)
            languagesDismissed = true
        },
        onAction = { action ->
            when (action) {
                is HomeAction.OnItemClick -> onItemClick(action.item)
                is HomeAction.OnLibraryClick -> onLibraryClick(action.library)
                is HomeAction.OnSearchClick -> onSearchClick()
                is HomeAction.OnSettingsClick -> onSettingsClick()
                is HomeAction.OnManageServers -> onManageServers()
                is HomeAction.OnReconnectClick -> onReconnectClick()
                is HomeAction.OnCloseClick -> (context as? Activity)?.finish()
                is HomeAction.OnPluginBrowse -> onPluginBrowse(action.pluginId, null)
                else -> Unit
            }
            viewModel.onAction(action)
        },
        onPluginBrowse = onPluginBrowse,
        onNetworkShareSeeAll = onNetworkShareSeeAll,
        onPlay = onPlay,
        rowLayout = rowLayout,
        onMoveRow = viewModel::moveRow,
        onSetRowVisible = viewModel::setRowVisible,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreenLayout(
    state: HomeState,
    displayRatings: Boolean,
    needsLanguageSetup: Boolean,
    onLanguageSettingsClick: () -> Unit,
    onDismissLanguagePrompt: () -> Unit,
    onAction: (HomeAction) -> Unit,
    onPluginBrowse: (pluginId: String, rowId: String?) -> Unit,
    onNetworkShareSeeAll: (shareId: String) -> Unit,
    onPlay: (PlayRequest) -> Unit,
    rowLayout: HomeRowLayout = HomeRowLayout(),
    onMoveRow: (rowId: String, currentOrder: List<String>, up: Boolean) -> Unit = { _, _, _ -> },
    onSetRowVisible: (rowId: String, visible: Boolean) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val safePadding = rememberSafePadding(handleStartInsets = false)

    val paddingStart = safePadding.start + MaterialTheme.spacings.default
    val paddingTop = safePadding.top + MaterialTheme.spacings.small
    val paddingEnd = safePadding.end + MaterialTheme.spacings.default
    val paddingBottom = safePadding.bottom + MaterialTheme.spacings.default

    val itemsPadding = PaddingValues(start = paddingStart, end = paddingEnd)
    val visibleHomeSections = remember(state) { 
        val filtered = state.filteredForUniqueHomeItems()
        Timber.d(
            "Visible sections: SUG=%s RES=%s NEXT=%s UNV=%d OFF=%d VIEW=%d",
            filtered.suggestionsSection != null,
            filtered.resumeSection != null,
            filtered.nextUpSection != null,
            filtered.universalPluginSections.size,
            filtered.offlineLibrarySections.size,
            filtered.views.size,
        )
        filtered
    }
    val statusCardModel = remember(state) { state.toStatusCardModel() }
    val finishSetupItems =
        buildList {
            if (needsLanguageSetup) {
                add(
                    FinishSetupItem(
                        titleRes = CoreR.string.finish_setup_languages_title,
                        bodyRes = CoreR.string.finish_setup_languages_body,
                        actionRes = CoreR.string.finish_setup_languages_action,
                        onClick = onLanguageSettingsClick,
                        onDismiss = onDismissLanguagePrompt,
                    )
                )
            }
        }

    val contentPaddingTop = safePadding.top + 88.dp

    var showErrorDialog by rememberSaveable { mutableStateOf(false) }
    val showServerSelectionSheetState = rememberModalBottomSheetState()
    var showServerSelectionBottomSheet by remember { mutableStateOf(false) }

    // Long-press target for a Music Assistant home card → actions sheet,
    // rendered through the :core:ui seam so this screen stays free of the app
    // MA types (see LocalMaCardActionsRenderer).
    val maCardActionsRenderer = dev.jdtech.jellyfin.presentation.music.LocalMaCardActionsRenderer.current
    var maMenuItem by remember { mutableStateOf<dev.jdtech.jellyfin.models.SpatialFinItem?>(null) }
    maMenuItem?.let { menuItem ->
        maCardActionsRenderer?.invoke(menuItem) { maMenuItem = null }
    }

    // Long-press a row's title to arrange it. Order and visibility live in
    // HomeRowPreferences, so the layout matches the Beam and TV homes and
    // survives process death.
    var arrangingRowId by rememberSaveable { mutableStateOf<String?>(null) }
    val suggestionsTitle = stringResource(FilmR.string.suggestions)
    val naturalRows =
        buildList<FilmHomeRow> {
            visibleHomeSections.suggestionsSection?.let { section ->
                add(
                    FilmHomeRow(HomeRowIds.SUGGESTIONS) { arrangeState ->
                        HomeCarousel(
                            items = section.items,
                            displayRatings = displayRatings,
                            itemsPadding = itemsPadding,
                            onAction = onAction,
                            title = suggestionsTitle,
                            arrangeState = arrangeState,
                        )
                    }
                )
            }
            visibleHomeSections.resumeSection?.let { section ->
                add(
                    FilmHomeRow(HomeRowIds.CONTINUE_WATCHING) { arrangeState ->
                        HomeSection(
                            section = section.homeSection,
                            displayRatings = displayRatings,
                            itemsPadding = itemsPadding,
                            onAction = onAction,
                            arrangeState = arrangeState,
                        )
                    }
                )
            }
            visibleHomeSections.nextUpSection?.let { section ->
                add(
                    FilmHomeRow(HomeRowIds.NEXT_UP) { arrangeState ->
                        HomeSection(
                            section = section.homeSection,
                            displayRatings = displayRatings,
                            itemsPadding = itemsPadding,
                            onAction = onAction,
                            arrangeState = arrangeState,
                        )
                    }
                )
            }
            visibleHomeSections.musicAssistantSections.forEach { section ->
                add(
                    FilmHomeRow(section.rowId) { arrangeState ->
                        HomeSection(
                            section = section.homeSection,
                            itemsPadding = itemsPadding,
                            onAction = onAction,
                            // Long-press an MA card → actions (add to playlist/queue,
                            // play next) instead of only play-on-tap.
                            onItemLongClick = { maMenuItem = it },
                            arrangeState = arrangeState,
                        )
                    }
                )
            }
            visibleHomeSections.offlineLibrarySections.forEach { section ->
                add(
                    FilmHomeRow(section.rowId) { arrangeState ->
                        HomeSection(
                            section = section.homeSection,
                            displayRatings = displayRatings,
                            itemsPadding = itemsPadding,
                            onAction = onAction,
                            arrangeState = arrangeState,
                        )
                    }
                )
            }
            visibleHomeSections.views.forEach { view ->
                add(
                    FilmHomeRow(view.rowId) { arrangeState ->
                        HomeView(
                            view = view,
                            displayRatings = displayRatings,
                            itemsPadding = itemsPadding,
                            onAction = onAction,
                            arrangeState = arrangeState,
                        )
                    }
                )
            }
            state.networkShareSections.forEach { section ->
                add(
                    FilmHomeRow(section.rowId) { arrangeState ->
                        HomeSection(
                            section = section.homeSection,
                            displayRatings = displayRatings,
                            itemsPadding = itemsPadding,
                            onAction = onAction,
                            onSeeAll = { onNetworkShareSeeAll(section.shareId) },
                            arrangeState = arrangeState,
                        )
                    }
                )
            }
            visibleHomeSections.universalPluginSections.forEach { section ->
                val firstItem =
                    section.homeSection.items.firstOrNull()
                        as? dev.jdtech.jellyfin.plugins.model.UniversalSpatialFinItem
                val pluginId = firstItem?.universalMediaItem?.pluginId
                val rowId = firstItem?.universalMediaItem?.homeRowId
                add(
                    FilmHomeRow(section.rowId) { arrangeState ->
                        HomeSection(
                            section = section.homeSection,
                            displayRatings = displayRatings,
                            itemsPadding = itemsPadding,
                            onAction = { action ->
                                if (
                                    action is HomeAction.OnItemClick &&
                                        action.item is dev.jdtech.jellyfin.plugins.model.UniversalSpatialFinItem
                                ) {
                                    val uItem = action.item
                                    onPlay(
                                        PlayRequest.UniversalMedia(
                                            pluginId = uItem.universalMediaItem.pluginId,
                                            id = uItem.universalMediaItem.id,
                                            videoUrl = uItem.universalMediaItem.videoUrl,
                                            name = uItem.name,
                                            stereoMode = uItem.universalMediaItem.stereoMode,
                                            projection = uItem.universalMediaItem.projection,
                                        )
                                    )
                                } else {
                                    onAction(action)
                                }
                            },
                            onSeeAll =
                                if (pluginId != null) {
                                    { onPluginBrowse(pluginId, rowId) }
                                } else {
                                    null
                                },
                            arrangeState = arrangeState,
                        )
                    }
                )
            }
        }
    val orderedRows = rowLayout.arrange(naturalRows) { it.id }
    val hiddenRows =
        remember(rowLayout, state.libraries, state.networkShareSections, context) {
            val customTitles = buildMap {
                state.libraries.forEach { put(HomeRowIds.latest(it.id), it.name) }
                state.networkShareSections.forEach { put(it.rowId, it.homeSection.name.asString(context.resources)) }
            }
            rowLayout.restorableHiddenRows(customTitles)
        }

    Box(modifier = Modifier.fillMaxSize().semantics { isTraversalGroup = true }) {
        PullToRefreshBox(isRefreshing = false, onRefresh = { onAction(HomeAction.OnRetryClick) }) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().semantics { traversalIndex = 1f },
                contentPadding = PaddingValues(top = contentPaddingTop, bottom = paddingBottom),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
            ) {
                if (finishSetupItems.isNotEmpty()) {
                    item(key = "finish_setup_cards") {
                        Box(modifier = Modifier.padding(horizontal = paddingStart, vertical = 0.dp)) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                            ) {
                                finishSetupItems.forEach { item ->
                                    FinishSetupCard(
                                        title = stringResource(item.titleRes),
                                        body = stringResource(item.bodyRes),
                                        actionLabel = stringResource(item.actionRes),
                                        onActionClick = item.onClick,
                                        onDismiss = item.onDismiss,
                                    )
                                }
                                Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))
                            }
                        }
                    }
                }
                statusCardModel?.let { status ->
                    item(key = "offline_status") {
                        HomeStatusCard(
                            model = status,
                            onReconnectClick = { onAction(HomeAction.OnReconnectClick) },
                            modifier =
                                Modifier.padding(start = paddingStart, end = paddingEnd),
                        )
                    }
                }
                // The shape of the home, not a spinner: the first paint already
                // has the shelf geometry in place, so nothing jumps when the real
                // rows land. Suppressed once anything is on screen — a refresh
                // over cached rows must not blank them out. See HomeSkeleton.
                if (state.isLoading && orderedRows.isEmpty()) {
                    item(key = "home_skeleton") {
                        HomeSkeleton(
                            modifier = Modifier.padding(start = paddingStart, end = paddingEnd),
                            // Matches ItemCard(Direction.HORIZONTAL) + HomeSection's LazyRow.
                            cardWidth = 360.dp,
                            cardAspect = 1.77f,
                            cardShape = MaterialTheme.shapes.small,
                            cardSpacing = MaterialTheme.spacings.default,
                            // The XR home opens on the Suggestions carousel, not a hero.
                            showHero = false,
                        )
                    }
                }
                orderedRows.forEachIndexed { index, row ->
                    item(key = row.id) {
                        row.content(
                            HomeRowArrangeState(
                                isArranging = arrangingRowId == row.id,
                                canMoveUp = index > 0,
                                canMoveDown = index < orderedRows.lastIndex,
                                onStartArranging = { arrangingRowId = row.id },
                                onMoveUp = { onMoveRow(row.id, orderedRows.map { it.id }, true) },
                                onMoveDown = { onMoveRow(row.id, orderedRows.map { it.id }, false) },
                                onHide = {
                                    onSetRowVisible(row.id, false)
                                },
                                onDone = { arrangingRowId = null },
                            )
                        )
                    }
                }

                // Only while arranging or when all rows have been hidden: the rows
                // the user switched off, so hiding one from home never becomes a one-way trip.
                // Gated on the load having finished — an empty row list means
                // "nothing has arrived yet" until then, and offering to restore
                // hidden rows over a blank screen is how a cold start came to look
                // like a broken one.
                if (!state.isLoading && (arrangingRowId != null || orderedRows.isEmpty()) && hiddenRows.isNotEmpty()) {
                    item(key = "hidden_home_rows") {
                        HiddenHomeRowsCard(
                            rows = hiddenRows,
                            onShow = { rowId -> onSetRowVisible(rowId, true) },
                            onDone = { arrangingRowId = null },
                            modifier = Modifier.padding(start = paddingStart, end = paddingEnd),
                        )
                    }
                }
            }
        }

        if (state.error != null && showErrorDialog) {
            ErrorDialog(
                exception = Exception(state.error.message),
                onDismissRequest = { showErrorDialog = false },
            )
        }
    }

    HomeHeader(
        serverName = state.server?.name ?: "",
        isLoading = state.isLoading,
        isError = state.error != null,
        onServerClick = { showServerSelectionBottomSheet = true },
        onErrorClick = { showErrorDialog = true },
        onRetryClick = { onAction(HomeAction.OnRetryClick) },
        onSearchClick = { onAction(HomeAction.OnSearchClick) },
        onUserClick = { onAction(HomeAction.OnSettingsClick) },
        onCloseClick = { onAction(HomeAction.OnCloseClick) },
        modifier = Modifier.padding(start = paddingStart, top = paddingTop, end = paddingEnd),
    )

    if (showServerSelectionBottomSheet) {
        ServerSelectionBottomSheet(
            currentServerId = state.server?.id ?: "",
            onUpdate = {
                onAction(HomeAction.OnRetryClick)
                scope
                    .launch { showServerSelectionSheetState.hide() }
                    .invokeOnCompletion {
                        if (!showServerSelectionSheetState.isVisible) {
                            showServerSelectionBottomSheet = false
                        }
                    }
            },
            onManage = {
                onAction(HomeAction.OnManageServers)
                scope.launch { showServerSelectionSheetState.hide() }
            },
            onDismissRequest = { showServerSelectionBottomSheet = false },
            sheetState = showServerSelectionSheetState,
        )
    }
}

@PreviewScreenSizes
@Composable
private fun HomeScreenLayoutPreview() {
    SpatialFinTheme {
        HomeScreenLayout(
            state =
                HomeState(
                    server = dummyServer,
                    suggestionsSection = dummyHomeSuggestions,
                    resumeSection = dummyHomeSection,
                    views = persistentListOf(dummyHomeView),
                    error = dev.jdtech.jellyfin.film.presentation.home.HomeLoadError("Failed to load data"),
                ),
            displayRatings = true,
            needsLanguageSetup = false,
            onLanguageSettingsClick = {},
            onDismissLanguagePrompt = {},
            onAction = {},
            onPluginBrowse = { _, _ -> },
            onNetworkShareSeeAll = {},
            onPlay = {},
        )
    }
}

/**
 * One arrangeable row on the XR home: its stable layout [id] plus the body,
 * which receives the arrange state to wire into its own shelf header.
 */
private data class FilmHomeRow(
    val id: String,
    val content: @Composable (HomeRowArrangeState) -> Unit,
)

private data class FinishSetupItem(
    val titleRes: Int,
    val bodyRes: Int,
    val actionRes: Int,
    val onClick: () -> Unit,
    val onDismiss: () -> Unit,
)

private data class HomeStatusCardModel(
    val title: UiText,
    val body: UiText,
    val syncSummary: UiText? = null,
    val useOfflineIcon: Boolean = false,
    val showReconnect: Boolean = false,
)

@Composable
private fun HomeStatusCard(
    model: HomeStatusCardModel,
    onReconnectClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacings.default),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
                Icon(
                    imageVector = if (model.useOfflineIcon) Icons.Rounded.WifiOff else Icons.Rounded.Refresh,
                    contentDescription = null,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.extraSmall),
                ) {
                    Text(
                        text = model.title.asString(),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = model.body.asString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    model.syncSummary?.let { summary ->
                        Text(
                            text = summary.asString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (model.showReconnect) {
                FilledTonalButton(onClick = onReconnectClick) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(MaterialTheme.spacings.extraSmall))
                    Text(text = stringResource(CoreR.string.offline_reconnect_action))
                }
            }
        }
    }
}

private data class FilteredHomeSections(
    val suggestionsSection: HomeItem.Suggestions?,
    val resumeSection: HomeItem.Section?,
    val nextUpSection: HomeItem.Section?,
    val universalPluginSections: List<HomeItem.Section>,
    val musicAssistantSections: List<HomeItem.Section>,
    val offlineLibrarySections: List<HomeItem.Section>,
    val views: List<HomeItem.ViewItem>,
)

private fun HomeState.toStatusCardModel(nowMs: Long = System.currentTimeMillis()): HomeStatusCardModel? {
    val recentSyncResult =
        syncStatus.lastAttemptAtEpochMs?.let { nowMs - it <= 5 * 60_000L } == true
    val syncSummary =
        when {
            syncStatus.isRunning -> {
                UiText.StringResource(
                    CoreR.string.offline_sync_running,
                    syncStatus.runningChangeCount.coerceAtLeast(syncStatus.pendingChanges),
                )
            }
            syncStatus.pendingChanges > 0 -> {
                if (recentSyncResult || !syncStatus.lastErrorMessage.isNullOrBlank()) {
                    UiText.StringResource(CoreR.string.offline_sync_failed, syncStatus.pendingChanges)
                } else {
                    UiText.StringResource(CoreR.string.offline_sync_pending, syncStatus.pendingChanges)
                }
            }
            else -> null
        }

    return when {
        manualOfflineMode -> {
            HomeStatusCardModel(
                title = UiText.StringResource(CoreR.string.offline_status_manual_title),
                body = UiText.StringResource(CoreR.string.offline_status_manual_body),
                syncSummary = syncSummary,
                useOfflineIcon = true,
            )
        }
        isConnectionDegraded -> {
            HomeStatusCardModel(
                title = UiText.StringResource(CoreR.string.offline_status_retrying_title),
                body = UiText.StringResource(CoreR.string.offline_status_retrying_body),
                syncSummary = syncSummary,
                useOfflineIcon = true,
                showReconnect = true,
            )
        }
        isOfflineMode -> {
            HomeStatusCardModel(
                title = UiText.StringResource(CoreR.string.offline_status_offline_title),
                body = UiText.StringResource(CoreR.string.offline_status_offline_body),
                syncSummary = syncSummary,
                useOfflineIcon = true,
                showReconnect = true,
            )
        }
        syncSummary != null -> {
            HomeStatusCardModel(
                title = UiText.StringResource(CoreR.string.offline_sync_title),
                body = syncSummary,
                showReconnect = syncStatus.pendingChanges > 0 && !syncStatus.isRunning,
            )
        }
        else -> null
    }
}

private fun HomeState.filteredForUniqueHomeItems(): FilteredHomeSections {
    val seenKeys = mutableSetOf<String>()

    fun List<SpatialFinItem>.filterUniqueForSection(sectionLabel: String): List<SpatialFinItem> {
        val deduplicated = deduplicateMovieVersions()
        return deduplicated.filter { item ->
            val uniqueKey = item.movieVersionGroupKey() ?: item.id.toString()
            val isNew = seenKeys.add(uniqueKey)
            isNew
        }
    }

    val filteredSuggestions =
        suggestionsSection
            ?.let { section ->
                section.items
                    .filterUniqueForSection("suggestions")
                    .takeIf { it.isNotEmpty() }
                    ?.let { items -> section.copy(items = items) }
            }

    val filteredResume =
        resumeSection
            ?.let { section ->
                section.homeSection.items
                    .filterUniqueForSection("resume")
                    .takeIf { it.isNotEmpty() }
                    ?.let { items -> section.copy(homeSection = section.homeSection.copy(items = items)) }
            }

    val filteredNextUp =
        nextUpSection
            ?.let { section ->
                section.homeSection.items
                    .filterUniqueForSection("next_up")
                    .takeIf { it.isNotEmpty() }
                    ?.let { items -> section.copy(homeSection = section.homeSection.copy(items = items)) }
            }

    val filteredUniversalPluginSections =
        universalPluginSections.mapNotNull { section ->
            section.homeSection.items
                .takeIf { it.isNotEmpty() }
                ?.let { items -> section.copy(homeSection = section.homeSection.copy(items = items)) }
        }

    val filteredMusicAssistantSections =
        musicAssistantSections.mapNotNull { section ->
            section.homeSection.items
                .takeIf { it.isNotEmpty() }
                ?.let { items -> section.copy(homeSection = section.homeSection.copy(items = items)) }
        }

    val filteredOfflineLibrarySections =
        offlineLibrarySections.mapNotNull { section ->
            section.homeSection.items
                .filterUniqueForSection("offline:${section.homeSection.name}")
                .takeIf { it.isNotEmpty() }
                ?.let { items -> section.copy(homeSection = section.homeSection.copy(items = items)) }
        }

    val filteredViews =
        views.mapNotNull { viewItem ->
            viewItem.view.items
                .filterUniqueForSection("view:${viewItem.view.name}")
                .takeIf { it.isNotEmpty() }
                ?.let { items -> viewItem.copy(view = viewItem.view.copy(items = items)) }
        }

    return FilteredHomeSections(
        suggestionsSection = filteredSuggestions,
        resumeSection = filteredResume,
        nextUpSection = filteredNextUp,
        universalPluginSections = filteredUniversalPluginSections,
        musicAssistantSections = filteredMusicAssistantSections,
        offlineLibrarySections = filteredOfflineLibrarySections,
        views = filteredViews,
    )
}
