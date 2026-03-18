package dev.jdtech.jellyfin.presentation.film

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyCollections
import dev.jdtech.jellyfin.film.presentation.media.MediaAction
import dev.jdtech.jellyfin.film.presentation.media.MediaState
import dev.jdtech.jellyfin.film.presentation.media.MediaViewModel
import dev.jdtech.jellyfin.film.presentation.search.SearchAction
import dev.jdtech.jellyfin.film.presentation.search.SearchState
import dev.jdtech.jellyfin.film.presentation.search.SearchViewModel
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.presentation.components.ErrorDialog
import dev.jdtech.jellyfin.presentation.film.components.Direction
import dev.jdtech.jellyfin.presentation.film.components.ErrorCard
import dev.jdtech.jellyfin.presentation.film.components.FavoritesCard
import dev.jdtech.jellyfin.presentation.film.components.FilmSearchBar
import dev.jdtech.jellyfin.presentation.film.components.ItemCard
import dev.jdtech.jellyfin.presentation.film.components.BrowseHeaderAction
import dev.jdtech.jellyfin.presentation.film.components.XrBrowseHeader
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.spatialfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding

@Composable
fun MediaScreen(
    onItemClick: (SpatialFinItem) -> Unit,
    onFavoritesClick: () -> Unit,
    searchExpanded: Boolean,
    onSearchExpand: (Boolean) -> Unit,
    initialSearchQuery: String? = null,
    onInitialSearchConsumed: () -> Unit = {},
    viewModel: MediaViewModel = hiltViewModel(),
    searchViewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val searchState by searchViewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadData() }

    MediaScreenLayout(
        state = state,
        searchState = searchState,
        searchExpanded = searchExpanded,
        initialSearchQuery = initialSearchQuery,
        onSearchExpand = onSearchExpand,
        onInitialSearchConsumed = onInitialSearchConsumed,
        onAction = { action ->
            when (action) {
                is MediaAction.OnItemClick -> onItemClick(action.item)
                is MediaAction.OnFavoritesClick -> onFavoritesClick()
                else -> Unit
            }
            viewModel.onAction(action)
        },
        onSearchAction = { action ->
            when (action) {
                is SearchAction.OnItemClick -> onItemClick(action.item)
                else -> Unit
            }
            searchViewModel.onAction(action)
        },
    )
}

@Composable
private fun MediaScreenLayout(
    state: MediaState,
    searchState: SearchState,
    searchExpanded: Boolean,
    initialSearchQuery: String?,
    onSearchExpand: (Boolean) -> Unit,
    onInitialSearchConsumed: () -> Unit,
    onAction: (MediaAction) -> Unit,
    onSearchAction: (SearchAction) -> Unit,
) {
    val safePadding = rememberSafePadding(handleStartInsets = false)

    val paddingStart = safePadding.start + MaterialTheme.spacings.default
    val paddingEnd = safePadding.end + MaterialTheme.spacings.default
    val paddingBottom = safePadding.bottom + MaterialTheme.spacings.default

    val contentPaddingTop by
        animateDpAsState(
            targetValue =
                if (state.error != null) {
                    safePadding.top + 236.dp
                } else {
                    safePadding.top + 180.dp
                },
            label = "content_padding",
        )

    var showErrorDialog by rememberSaveable { mutableStateOf(false) }

    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val minColumnSize =
        when {
            windowSizeClass.isWidthAtLeastBreakpoint(
                WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND
            ) -> 320.dp
            windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) ->
                240.dp
            else -> 160.dp
        }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = minColumnSize),
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = paddingStart,
                    top = contentPaddingTop,
                    end = paddingEnd,
                    bottom = paddingBottom,
                ),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.large),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.large),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                FavoritesCard(onClick = { onAction(MediaAction.OnFavoritesClick) })
            }
            items(state.libraries, key = { it.id }) { library ->
                ItemCard(
                    item = library,
                    direction = Direction.HORIZONTAL,
                    onClick = { onAction(MediaAction.OnItemClick(library)) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (!searchExpanded) {
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(
                            start = paddingStart,
                            top = safePadding.top + MaterialTheme.spacings.default,
                            end = paddingEnd,
                        ),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
            ) {
                XrBrowseHeader(
                    title = stringResource(CoreR.string.title_media),
                    primaryAction =
                        BrowseHeaderAction(
                            label = "Search",
                            icon = CoreR.drawable.ic_search,
                            onClick = { onSearchExpand(true) },
                        ),
                )
                FilmSearchBar(
                    state = searchState,
                    expanded = false,
                    initialQuery = initialSearchQuery,
                    onExpand = onSearchExpand,
                    onInitialQueryConsumed = onInitialSearchConsumed,
                    onAction = onSearchAction,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxSize().zIndex(2f),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
            ) {
                Column(
                    modifier =
                        Modifier.fillMaxSize().padding(
                            start = paddingStart,
                            top = safePadding.top + MaterialTheme.spacings.default,
                            end = paddingEnd,
                            bottom = paddingBottom,
                        ),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
                ) {
                    XrBrowseHeader(
                        title = "Search",
                        onBackClick = { onSearchExpand(false) },
                    )
                    FilmSearchBar(
                        state = searchState,
                        expanded = true,
                        initialQuery = initialSearchQuery,
                        onExpand = onSearchExpand,
                        onInitialQueryConsumed = onInitialSearchConsumed,
                        onAction = onSearchAction,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
        }
        if (!searchExpanded && state.error != null) {
            ErrorCard(
                onShowStacktrace = { showErrorDialog = true },
                onRetryClick = { onAction(MediaAction.OnRetryClick) },
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(
                            start = paddingStart,
                            top = safePadding.top + 80.dp,
                            end = paddingEnd,
                        ),
            )
            if (showErrorDialog) {
                ErrorDialog(
                    exception = state.error!!,
                    onDismissRequest = { showErrorDialog = false },
                )
            }
        }
    }
}

@PreviewScreenSizes
@Composable
private fun MediaScreenLayoutPreview() {
    SpatialFinTheme {
        MediaScreenLayout(
            state =
                MediaState(libraries = dummyCollections, error = Exception("Failed to load data")),
            searchState = SearchState(),
            searchExpanded = false,
            initialSearchQuery = null,
            onSearchExpand = {},
            onInitialSearchConsumed = {},
            onAction = {},
            onSearchAction = {},
        )
    }
}
