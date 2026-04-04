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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyHomeSection
import dev.jdtech.jellyfin.core.presentation.dummy.dummyHomeSuggestions
import dev.jdtech.jellyfin.core.presentation.dummy.dummyHomeView
import dev.jdtech.jellyfin.core.presentation.dummy.dummyServer
import dev.jdtech.jellyfin.film.presentation.home.HomeAction
import dev.jdtech.jellyfin.film.presentation.home.HomeState
import dev.jdtech.jellyfin.film.presentation.home.HomeViewModel
import dev.jdtech.jellyfin.models.HomeItem
import dev.jdtech.jellyfin.models.SpatialFinCollection
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.View
import dev.jdtech.jellyfin.models.deduplicateMovieVersions
import dev.jdtech.jellyfin.models.movieVersionGroupKey
import dev.jdtech.jellyfin.presentation.components.ErrorDialog
import dev.jdtech.jellyfin.presentation.components.FinishSetupCard
import dev.jdtech.jellyfin.presentation.film.components.HomeCarousel
import dev.jdtech.jellyfin.presentation.film.components.HomeHeader
import dev.jdtech.jellyfin.presentation.film.components.HomeSection
import dev.jdtech.jellyfin.presentation.film.components.HomeView
import dev.jdtech.jellyfin.presentation.film.components.ServerSelectionBottomSheet
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.spatialfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding
import kotlinx.coroutines.launch

import android.app.Activity
import timber.log.Timber

@Composable
fun HomeScreen(
    appPreferences: AppPreferences,
    onLibraryClick: (library: SpatialFinCollection) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onManageServers: () -> Unit,
    onReconnectClick: () -> Unit,
    onLanguageSettingsClick: () -> Unit,
    onItemClick: (item: SpatialFinItem) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val needsLanguageSetup =
        appPreferences.getValue(appPreferences.smartSpokenLanguages).isNullOrBlank()
    val displayRatings = appPreferences.getValue(appPreferences.displayRatings)

    LaunchedEffect(true) { viewModel.loadData() }

    HomeScreenLayout(
        state = state,
        displayRatings = displayRatings,
        needsLanguageSetup = needsLanguageSetup,
        onLanguageSettingsClick = onLanguageSettingsClick,
        onAction = { action ->
            when (action) {
                is HomeAction.OnItemClick -> onItemClick(action.item)
                is HomeAction.OnLibraryClick -> onLibraryClick(action.library)
                is HomeAction.OnSearchClick -> onSearchClick()
                is HomeAction.OnSettingsClick -> onSettingsClick()
                is HomeAction.OnManageServers -> onManageServers()
                is HomeAction.OnReconnectClick -> onReconnectClick()
                is HomeAction.OnCloseClick -> (context as? Activity)?.finish()
                else -> Unit
            }
            viewModel.onAction(action)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreenLayout(
    state: HomeState,
    displayRatings: Boolean,
    needsLanguageSetup: Boolean,
    onLanguageSettingsClick: () -> Unit,
    onAction: (HomeAction) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val safePadding = rememberSafePadding(handleStartInsets = false)

    val paddingStart = safePadding.start + MaterialTheme.spacings.default
    val paddingTop = safePadding.top + MaterialTheme.spacings.small
    val paddingEnd = safePadding.end + MaterialTheme.spacings.default
    val paddingBottom = safePadding.bottom + MaterialTheme.spacings.default

    val itemsPadding = PaddingValues(start = paddingStart, end = paddingEnd)
    val visibleHomeSections = remember(state) { state.filteredForUniqueHomeItems() }
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
                    )
                )
            }
        }

    val contentPaddingTop = safePadding.top + 88.dp

    var showErrorDialog by rememberSaveable { mutableStateOf(false) }
    val showServerSelectionSheetState = rememberModalBottomSheetState()
    var showServerSelectionBottomSheet by remember { mutableStateOf(false) }

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
                visibleHomeSections.suggestionsSection?.let { section ->
                    item(key = section.id) {
                        HomeCarousel(
                            items = section.items,
                            displayRatings = displayRatings,
                            itemsPadding = itemsPadding,
                            onAction = onAction,
                        )
                    }
                }
                visibleHomeSections.resumeSection?.let { section ->
                    item(key = section.id) {
                        HomeSection(
                            section = section.homeSection,
                            displayRatings = displayRatings,
                            itemsPadding = itemsPadding,
                            onAction = onAction,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
                visibleHomeSections.nextUpSection?.let { section ->
                    item(key = section.id) {
                        HomeSection(
                            section = section.homeSection,
                            displayRatings = displayRatings,
                            itemsPadding = itemsPadding,
                            onAction = onAction,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
                items(visibleHomeSections.offlineLibrarySections, key = { it.id }) { section ->
                    HomeSection(
                        section = section.homeSection,
                        displayRatings = displayRatings,
                        itemsPadding = itemsPadding,
                        onAction = onAction,
                        modifier = Modifier.animateItem(),
                    )
                }
                items(visibleHomeSections.views, key = { it.id }) { view ->
                    HomeView(
                        view = view,
                        displayRatings = displayRatings,
                        itemsPadding = itemsPadding,
                        onAction = onAction,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }

        if (state.error != null && showErrorDialog) {
            ErrorDialog(exception = state.error!!, onDismissRequest = { showErrorDialog = false })
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
                    views = listOf(dummyHomeView),
                    error = Exception("Failed to load data"),
                ),
            displayRatings = true,
            needsLanguageSetup = false,
            onLanguageSettingsClick = {},
            onAction = {},
        )
    }
}

private data class FinishSetupItem(
    val titleRes: Int,
    val bodyRes: Int,
    val actionRes: Int,
    val onClick: () -> Unit,
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
            recentSyncResult && syncStatus.lastSyncedChanges > 0 -> {
                UiText.StringResource(CoreR.string.offline_sync_success, syncStatus.lastSyncedChanges)
            }
            recentSyncResult && syncStatus.lastFailedChanges > 0 -> {
                UiText.StringResource(
                    CoreR.string.offline_sync_failed,
                    syncStatus.lastFailedChanges.coerceAtLeast(syncStatus.pendingChanges),
                )
            }
            recentSyncResult && !syncStatus.lastErrorMessage.isNullOrBlank() -> {
                UiText.DynamicString(syncStatus.lastErrorMessage!!)
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
        offlineLibrarySections = filteredOfflineLibrarySections,
        views = filteredViews,
    )
}
