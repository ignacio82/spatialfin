package dev.jdtech.jellyfin.presentation.network

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.NetworkShareDto
import dev.jdtech.jellyfin.models.NetworkVideoItem
import dev.jdtech.jellyfin.presentation.film.components.BrowseHeaderAction
import dev.jdtech.jellyfin.presentation.film.components.Direction
import dev.jdtech.jellyfin.presentation.film.components.ItemCard
import dev.jdtech.jellyfin.presentation.film.components.XrBrowseHeader
import dev.spatialfin.presentation.theme.spacings

@Composable
fun NetworkScreen(
    onShareClick: (NetworkShareDto) -> Unit,
    onAddShareClick: () -> Unit,
    onItemClick: (NetworkVideoItem) -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: NetworkViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadShares() }

    NetworkScreenLayout(
        state = state,
        onShareClick = onShareClick,
        onAddShareClick = onAddShareClick,
        onItemClick = onItemClick,
        onSettingsClick = onSettingsClick,
        onRetry = { viewModel.loadShares() },
    )
}

@Composable
private fun NetworkScreenLayout(
    state: NetworkState,
    onShareClick: (NetworkShareDto) -> Unit,
    onAddShareClick: () -> Unit,
    onItemClick: (NetworkVideoItem) -> Unit,
    onSettingsClick: () -> Unit,
    onRetry: () -> Unit,
) {
    val contentPadding = PaddingValues(24.dp)

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
            state.shares.isEmpty() -> {
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
                            text = stringResource(CoreR.string.network_empty_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = stringResource(CoreR.string.network_empty_body),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(onClick = onAddShareClick) {
                            Text(stringResource(CoreR.string.network_add_share))
                        }
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(top = 108.dp),
                    contentPadding = contentPadding,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.large),
                ) {
                    // Continue watching section
                    if (state.resumeItems.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(CoreR.string.network_continue_watching),
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                        item {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.large),
                            ) {
                                items(state.resumeItems, key = { it.networkVideoId }) { item ->
                                    ItemCard(
                                        item = item,
                                        direction = Direction.VERTICAL,
                                        onClick = { onItemClick(item) },
                                    )
                                }
                            }
                        }
                    }

                    // Shares list
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Shares",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Button(onClick = onAddShareClick) {
                                Text(stringResource(CoreR.string.network_add_share))
                            }
                        }
                    }
                    items(state.shares, key = { it.id }) { share ->
                        ShareCard(
                            share = share,
                            onClick = { onShareClick(share) },
                        )
                    }
                }
            }
        }
        XrBrowseHeader(
            title = stringResource(CoreR.string.title_network),
            primaryAction = BrowseHeaderAction(
                label = "Settings",
                icon = CoreR.drawable.ic_settings,
                onClick = onSettingsClick,
            ),
            modifier = Modifier.padding(contentPadding),
        )
    }
}

@Composable
private fun ShareCard(
    share: NetworkShareDto,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = share.displayName ?: share.shareName,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "${share.protocol.uppercase()}://${share.host}/${share.shareName}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
