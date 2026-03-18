package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.R as FilmR
import dev.jdtech.jellyfin.film.presentation.search.SearchAction
import dev.jdtech.jellyfin.film.presentation.search.SearchState
import dev.jdtech.jellyfin.models.deduplicateMovieVersions
import dev.jdtech.jellyfin.presentation.utils.GridCellsAdaptiveWithMinColumns
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding
import dev.spatialfin.presentation.theme.spacings
import kotlinx.coroutines.delay

@Composable
fun FilmSearchBar(
    state: SearchState,
    expanded: Boolean,
    initialQuery: String? = null,
    onExpand: (Boolean) -> Unit,
    onInitialQueryConsumed: () -> Unit = {},
    onAction: (SearchAction) -> Unit,
    modifier: Modifier = Modifier,
    paddingStart: Dp = 0.dp,
    paddingEnd: Dp = 0.dp,
) {
    val focusRequester = remember { FocusRequester() }
    val safePadding = rememberSafePadding()

    var query by rememberSaveable { mutableStateOf("") }

    val contentPaddingStart by
        animateDpAsState(
            targetValue = if (expanded) 0.dp else paddingStart,
            label = "search_content_padding_start",
        )

    val contentPaddingEnd by
        animateDpAsState(
            targetValue = if (expanded) 0.dp else paddingEnd,
            label = "search_content_padding_end",
        )

    LaunchedEffect(expanded) {
        if (expanded) {
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(initialQuery) {
        val trimmed = initialQuery?.trim().orEmpty()
        if (trimmed.isNotEmpty() && query != trimmed) {
            query = trimmed
            onExpand(true)
            onInitialQueryConsumed()
        }
    }

    LaunchedEffect(query) {
        if (query.isNotBlank()) {
            delay(minOf(50L + (query.count() * 50L), 300L))
        }
        onAction(SearchAction.Search(query))
    }

    if (!expanded) {
        CollapsedSearchField(
            query = query,
            loading = state.loading,
            onClick = { onExpand(true) },
            modifier = modifier.padding(start = contentPaddingStart, end = contentPaddingEnd),
        )
        return
    }

    val visibleItems = state.items.deduplicateMovieVersions()

    Column(
        modifier = modifier.padding(start = contentPaddingStart, end = contentPaddingEnd),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.large),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            textStyle = MaterialTheme.typography.titleLarge,
            placeholder = {
                Text(
                    text = stringResource(FilmR.string.search_placeholder),
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_search),
                    contentDescription = null,
                )
            },
            trailingIcon = {
                when {
                    state.loading -> {
                        Box(modifier = Modifier.size(32.dp)) { CircularProgressIndicator() }
                    }
                    query.isNotEmpty() -> {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_x),
                                contentDescription = null,
                            )
                        }
                    }
                }
            },
            shape = RoundedCornerShape(28.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )

        SearchSummaryCard(
            state = state,
            visibleItemsCount = visibleItems.size,
            query = query,
        )

        when {
            state.loading -> {
                SearchStatusPanel(
                    title = stringResource(FilmR.string.search_loading_title),
                    subtitle = stringResource(FilmR.string.search_loading_subtitle, state.query),
                    showLoading = true,
                )
            }
            state.errorMessage != null -> {
                SearchStatusPanel(
                    title = stringResource(FilmR.string.search_error_title),
                    subtitle = state.errorMessage ?: "",
                )
            }
            query.isBlank() -> {
                SearchStatusPanel(
                    title = stringResource(FilmR.string.search_prompt_title),
                    subtitle = stringResource(FilmR.string.search_prompt_subtitle),
                )
            }
            state.hasSearched && visibleItems.isEmpty() -> {
                SearchStatusPanel(
                    title = stringResource(FilmR.string.search_empty_title),
                    subtitle = stringResource(FilmR.string.search_empty_subtitle, state.query),
                )
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCellsAdaptiveWithMinColumns(minSize = 220.dp, minColumns = 2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding =
                        PaddingValues(
                            start = safePadding.start,
                            end = safePadding.end,
                            bottom = safePadding.bottom + MaterialTheme.spacings.large,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.large),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.large),
                ) {
                    items(items = visibleItems, key = { it.id }) { item ->
                        ItemCard(
                            item = item,
                            direction = Direction.VERTICAL,
                            onClick = { onAction(SearchAction.OnItemClick(item)) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsedSearchField(
    query: String,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_search),
                contentDescription = null,
            )
            Text(
                text =
                    if (query.isBlank()) {
                        stringResource(FilmR.string.search_placeholder)
                    } else {
                        query
                    },
                style = MaterialTheme.typography.titleMedium,
                color =
                    if (query.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (loading) {
                Box(modifier = Modifier.size(28.dp)) { CircularProgressIndicator() }
            } else {
                FilledTonalButton(onClick = onClick) {
                    Text(stringResource(FilmR.string.search_open_action))
                }
            }
        }
    }
}

@Composable
private fun SearchSummaryCard(
    state: SearchState,
    visibleItemsCount: Int,
    query: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.large),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.extraSmall),
        ) {
            Text(
                text = stringResource(FilmR.string.search_results_title),
                style = MaterialTheme.typography.titleLarge,
            )
            val subtitle =
                when {
                    state.loading && state.query.isNotBlank() ->
                        stringResource(FilmR.string.search_loading_subtitle, state.query)
                    state.errorMessage != null ->
                        stringResource(FilmR.string.search_error_subtitle)
                    query.isBlank() ->
                        stringResource(FilmR.string.search_prompt_subtitle)
                    state.hasSearched && visibleItemsCount > 0 ->
                        stringResource(
                            FilmR.string.search_results_subtitle,
                            visibleItemsCount,
                            state.query,
                        )
                    state.hasSearched ->
                        stringResource(FilmR.string.search_empty_subtitle, state.query)
                    else -> stringResource(FilmR.string.search_prompt_subtitle)
                }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SearchStatusPanel(
    title: String,
    subtitle: String,
    showLoading: Boolean = false,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        ) {
            if (showLoading) {
                CircularProgressIndicator()
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
