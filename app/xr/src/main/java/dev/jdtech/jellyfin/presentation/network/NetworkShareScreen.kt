package dev.jdtech.jellyfin.presentation.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.NetworkVideoItem
import dev.jdtech.jellyfin.presentation.film.components.Direction
import dev.jdtech.jellyfin.presentation.film.components.ItemCard
import dev.jdtech.jellyfin.presentation.film.components.XrBrowseHeader
import dev.jdtech.jellyfin.presentation.utils.GridCellsAdaptiveWithMinColumns
import dev.spatialfin.presentation.theme.spacings

@Composable
fun NetworkShareScreen(
    shareId: String,
    navigateBack: () -> Unit,
    onItemClick: (NetworkVideoItem) -> Unit,
    viewModel: NetworkShareViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(shareId) { viewModel.load(shareId) }

    NetworkShareScreenLayout(
        state = state,
        onBack = navigateBack,
        onItemClick = onItemClick,
        onScan = { viewModel.scanShare(shareId) },
        onRemove = {
            viewModel.removeShare(shareId)
            navigateBack()
        },
    )
}

@Composable
private fun NetworkShareScreenLayout(
    state: NetworkShareState,
    onBack: () -> Unit,
    onItemClick: (NetworkVideoItem) -> Unit,
    onScan: () -> Unit,
    onRemove: () -> Unit,
) {
    val contentPadding = PaddingValues(24.dp)
    var showRemoveDialog by remember { mutableStateOf(false) }

    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text(stringResource(CoreR.string.network_remove_share)) },
            text = { Text(stringResource(CoreR.string.network_remove_share_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveDialog = false
                    onRemove()
                }) {
                    Text(stringResource(CoreR.string.network_remove_share))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
                    Text(stringResource(CoreR.string.cancel))
                }
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(top = 108.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                val allItems = buildList {
                    addAll(state.movies)
                    state.tvShows.values.forEach { addAll(it) }
                    addAll(state.uncategorized)
                }

                if (allItems.isEmpty() && !state.isScanning) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(top = 108.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            modifier = Modifier.padding(32.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "No videos found",
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(
                                text = "Scan this share to discover video files.",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Button(onClick = onScan) {
                                    Text(stringResource(CoreR.string.network_scan_share))
                                }
                                FilledTonalButton(onClick = { showRemoveDialog = true }) {
                                    Text(stringResource(CoreR.string.network_remove_share))
                                }
                            }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCellsAdaptiveWithMinColumns(minSize = 220.dp, minColumns = 2),
                        modifier = Modifier.fillMaxSize().padding(top = 108.dp),
                        contentPadding = contentPadding,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.large),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.large),
                    ) {
                        // Action row
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FilledTonalButton(
                                    onClick = onScan,
                                    enabled = !state.isScanning,
                                ) {
                                    if (state.isScanning) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.padding(end = 8.dp),
                                        )
                                    }
                                    Text(stringResource(CoreR.string.network_scan_share))
                                }
                                FilledTonalButton(onClick = { showRemoveDialog = true }) {
                                    Text(stringResource(CoreR.string.network_remove_share))
                                }
                            }
                        }

                        // Movies section
                        if (state.movies.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    text = stringResource(CoreR.string.network_movies),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            }
                            items(state.movies, key = { it.networkVideoId }) { item ->
                                ItemCard(
                                    item = item,
                                    direction = Direction.VERTICAL,
                                    onClick = { onItemClick(item) },
                                )
                            }
                        }

                        // TV Shows section
                        state.tvShows.forEach { (seriesKey, episodes) ->
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    text = episodes.firstOrNull()?.name
                                        ?: stringResource(CoreR.string.network_tv_shows),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            }
                            items(episodes, key = { it.networkVideoId }) { item ->
                                ItemCard(
                                    item = item,
                                    direction = Direction.VERTICAL,
                                    onClick = { onItemClick(item) },
                                )
                            }
                        }

                        // Uncategorized section
                        if (state.uncategorized.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    text = stringResource(CoreR.string.network_uncategorized),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            }
                            items(state.uncategorized, key = { it.networkVideoId }) { item ->
                                ItemCard(
                                    item = item,
                                    direction = Direction.VERTICAL,
                                    onClick = { onItemClick(item) },
                                )
                            }
                        }
                    }
                }
            }
        }
        XrBrowseHeader(
            title = state.share?.displayName ?: state.share?.shareName ?: stringResource(CoreR.string.title_network),
            onBackClick = onBack,
            modifier = Modifier.padding(contentPadding),
        )
    }
}
