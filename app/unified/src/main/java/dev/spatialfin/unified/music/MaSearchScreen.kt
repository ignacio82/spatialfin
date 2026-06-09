package dev.spatialfin.unified.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.SearchResult
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerMediaItem

/**
 * Dedicated Music Assistant search screen. Mirrors the official mobile app's
 * structure — sticky search field at the top, type tabs for the result
 * categories, library-only filter chip. Result taps fan out:
 *   - track → play through [LocalMaPlayDispatcher]
 *   - album / artist / playlist → callback to the host, which decides what
 *     detail screen to push.
 *
 * The screen owns its own [MaSearchViewModel] (Hilt-scoped to the
 * NavBackStackEntry / hostingComposable) — that's why it can debounce + cancel
 * stale requests without the host having to thread any flow state in.
 *
 * `onOpenItem` is a structured callback so the same composable hosts cleanly
 * inside Beam (route push), TV (route push), and XR Home Space (overlay) — the
 * navigation glue lives at the call site, not in here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaSearchScreen(
    onBack: () -> Unit,
    onOpenItem: (MaBrowseTarget) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MaSearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val libraryOnly by viewModel.libraryOnly.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dispatcher = dev.spatialfin.unified.LocalMaPlayDispatcher.current
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            SearchHeader(
                query = query,
                onQueryChange = viewModel::setQuery,
                onBack = onBack,
                onSearchAction = { keyboard?.hide() },
                focusRequester = focusRequester,
            )
            FilterChipsRow(
                libraryOnly = libraryOnly,
                onLibraryOnlyChange = viewModel::setLibraryOnly,
            )
            HorizontalDivider()
            when (val s = state) {
                is MaSearchState.Empty -> EmptyView(lastQuery = s.lastQuery)
                is MaSearchState.Loading -> LoadingView(query = s.query)
                is MaSearchState.Error -> ErrorView(message = s.message, onRetry = viewModel::retry)
                is MaSearchState.Results -> ResultsView(
                    result = s.result,
                    onPlayTrack = { item ->
                        if (dispatcher == null) warnNoDispatcher() else dispatcher.play(item)
                    },
                    onOpenItem = onOpenItem,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onSearchAction: () -> Unit,
    focusRequester: FocusRequester,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search Music Assistant") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = { onSearchAction() },
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
            )
        }
    }
}

@Composable
private fun FilterChipsRow(
    libraryOnly: Boolean,
    onLibraryOnlyChange: (Boolean) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = libraryOnly,
                onClick = { onLibraryOnlyChange(!libraryOnly) },
                label = { Text("Library only") },
            )
        }
    }
}

@Composable
private fun EmptyView(lastQuery: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = if (lastQuery.isBlank()) {
                "Type to search across tracks, albums, artists, playlists, podcasts, and radio."
            } else {
                "No results for \"$lastQuery\". Try a shorter query or turn off the Library filter."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LoadingView(query: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = "Searching for \"$query\"…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun ErrorView(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            androidx.compose.material3.TextButton(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) {
                Text("Retry")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultsView(
    result: SearchResult,
    onPlayTrack: (ServerMediaItem) -> Unit,
    onOpenItem: (MaBrowseTarget) -> Unit,
) {
    // Build the visible-tabs list dynamically — never show an empty tab so the
    // user doesn't have to click through dead categories ("no podcasts" is
    // signal, not UI to navigate to).
    val tabs = remember(result) {
        buildList {
            if (result.tracks.isNotEmpty()) add(SearchTab.Tracks to result.tracks)
            if (result.albums.isNotEmpty()) add(SearchTab.Albums to result.albums)
            if (result.artists.isNotEmpty()) add(SearchTab.Artists to result.artists)
            if (result.playlists.isNotEmpty()) add(SearchTab.Playlists to result.playlists)
            if (result.podcasts.isNotEmpty()) add(SearchTab.Podcasts to result.podcasts)
            if (result.radio.isNotEmpty()) add(SearchTab.Radio to result.radio)
        }
    }
    if (tabs.isEmpty()) {
        EmptyView(lastQuery = "")
        return
    }
    var selectedIndex by remember(tabs) { mutableStateOf(0) }
    val dispatcher = dev.spatialfin.unified.LocalMaPlayDispatcher.current
    val selection = rememberMaSelection()
    // Leaving a tab abandons its selection — keys aren't comparable across tabs.
    androidx.compose.runtime.LaunchedEffect(selectedIndex) { selection.clear() }
    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryScrollableTabRow(selectedTabIndex = selectedIndex, edgePadding = 0.dp) {
            tabs.forEachIndexed { index, (tab, items) ->
                Tab(
                    selected = index == selectedIndex,
                    onClick = { selectedIndex = index },
                    text = { Text("${tab.label} (${items.size})") },
                )
            }
        }
        MaSelectionBar(selection = selection, dispatcher = dispatcher)
        val (tab, items) = tabs[selectedIndex.coerceIn(0, tabs.lastIndex)]
        // Multi-select only on leaf-playable rows (where bulk queue/favourite
        // actions make sense), not on Albums/Artists/Playlists.
        val selectable = tab == SearchTab.Tracks ||
            tab == SearchTab.Podcasts ||
            tab == SearchTab.Radio
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(items, key = { it.itemId + "|" + it.provider }) { item ->
                val key = item.itemId + "|" + item.provider
                MaSearchItemRow(
                    item = item,
                    selected = selectable && selection.isSelected(key),
                    onLongClick = if (selectable) {
                        { selection.toggle(key, item) }
                    } else {
                        null
                    },
                    onClick = {
                        // While a selection is active, taps extend it instead of
                        // playing/navigating.
                        if (selectable && selection.active) {
                            selection.toggle(key, item)
                        } else {
                            when (tab) {
                                SearchTab.Tracks -> onPlayTrack(item)
                                SearchTab.Albums -> onOpenItem(MaBrowseTarget.Album(item))
                                SearchTab.Artists -> onOpenItem(MaBrowseTarget.Artist(item))
                                SearchTab.Playlists -> onOpenItem(MaBrowseTarget.Playlist(item))
                                // Podcasts and Radio fall through to a play
                                // action; podcast detail lands in a later phase.
                                SearchTab.Podcasts, SearchTab.Radio -> onPlayTrack(item)
                            }
                        }
                    },
                    trailing = if (selectable) {
                        { MaTrackMenu(item = item, dispatcher = dispatcher) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

private enum class SearchTab(val label: String) {
    Tracks("Tracks"),
    Albums("Albums"),
    Artists("Artists"),
    Playlists("Playlists"),
    Podcasts("Podcasts"),
    Radio("Radio"),
}

/**
 * Tagged target for the host's navigation. Lets the search screen stay
 * agnostic of which form factor's nav stack handles the push.
 */
sealed interface MaBrowseTarget {
    val item: ServerMediaItem
    data class Album(override val item: ServerMediaItem) : MaBrowseTarget
    data class Artist(override val item: ServerMediaItem) : MaBrowseTarget
    data class Playlist(override val item: ServerMediaItem) : MaBrowseTarget
}

private fun warnNoDispatcher() {
    timber.log.Timber.tag("MaSearch")
        .w("No LocalMaPlayDispatcher in scope — install one in the form-factor root")
}
