package dev.jdtech.jellyfin.presentation.film

import android.content.Intent
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.player.xr.StereoModeDetector
import dev.jdtech.jellyfin.player.xr.XrPlayerActivity
import dev.jdtech.jellyfin.core.presentation.dummy.dummySeason
import dev.jdtech.jellyfin.film.presentation.season.SeasonAction
import dev.jdtech.jellyfin.film.presentation.season.SeasonState
import dev.jdtech.jellyfin.film.presentation.season.SeasonViewModel
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.presentation.components.XrConfirmDialog
import dev.jdtech.jellyfin.presentation.film.components.Direction
import dev.jdtech.jellyfin.presentation.film.components.DownloadBackgroundConsentDialog
import dev.jdtech.jellyfin.presentation.film.components.EpisodeCard
import dev.jdtech.jellyfin.presentation.film.components.ItemButtonsBar
import dev.jdtech.jellyfin.presentation.film.components.ItemHeader
import dev.jdtech.jellyfin.presentation.film.components.ItemPoster
import dev.jdtech.jellyfin.presentation.film.components.ItemTopBar
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.spatialfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind

@Composable
fun SeasonScreen(
    seasonId: UUID,
    navigateBack: () -> Unit,
    navigateHome: () -> Unit,
    navigateToItem: (item: SpatialFinItem) -> Unit,
    navigateToSeries: (seriesId: UUID) -> Unit,
    viewModel: SeasonViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadSeason(seasonId = seasonId) }

    SeasonScreenLayout(
        state = state,
        onAction = { action ->
            when (action) {
                is SeasonAction.Play -> {
                    val targetActivity = if (action.multitask) {
                        dev.jdtech.jellyfin.player.xr.MultitaskPlayerActivity::class.java
                    } else {
                        XrPlayerActivity::class.java
                    }
                    val intent = Intent(context, targetActivity)
                    intent.putExtra("itemId", seasonId.toString())
                    intent.putExtra("itemKind", BaseItemKind.SEASON.serialName)
                    if (!action.multitask) {
                        intent.putExtra("stereoMode", "mono")
                    }
                    context.startActivity(intent)
                }
                is SeasonAction.OnBackClick -> navigateBack()
                is SeasonAction.OnHomeClick -> navigateHome()
                is SeasonAction.NavigateToItem -> navigateToItem(action.item)
                is SeasonAction.NavigateToSeries -> navigateToSeries(action.seriesId)
                else -> Unit
            }
            viewModel.onAction(action)
        },
    )
}

@Composable
private fun SeasonScreenLayout(state: SeasonState, onAction: (SeasonAction) -> Unit) {
    val safePadding = rememberSafePadding()

    val paddingStart = safePadding.start + MaterialTheme.spacings.default
    val paddingEnd = safePadding.end + MaterialTheme.spacings.default
    val paddingBottom = safePadding.bottom + MaterialTheme.spacings.default

    val lazyListState = rememberLazyListState()

    var selectedIds by remember(state.season?.id) { mutableStateOf<Set<UUID>>(emptySet()) }
    var overflowMenuOpen by remember { mutableStateOf(false) }
    var pendingDownload by remember {
        mutableStateOf<List<SpatialFinEpisode>?>(null)
    }
    val selectionMode = selectedIds.isNotEmpty()
    val downloadableEpisodes = state.episodes.filterNot { it.isDownloaded() }
    val unwatchedDownloadable =
        downloadableEpisodes.filterNot { it.played }
    val selectedDownloadable =
        downloadableEpisodes.filter { selectedIds.contains(it.id) }

    Box(modifier = Modifier.fillMaxSize()) {
        state.season?.let { season ->
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                state = lazyListState,
                contentPadding = PaddingValues(bottom = paddingBottom),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
            ) {
                item {
                    ItemHeader(
                        item = season,
                        lazyListState = lazyListState,
                        content = {
                            Row(
                                modifier =
                                    Modifier.align(Alignment.BottomStart)
                                        .padding(start = paddingStart, end = paddingEnd),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                ItemPoster(
                                    item = season,
                                    direction = Direction.VERTICAL,
                                    modifier =
                                        Modifier.width(120.dp).clip(MaterialTheme.shapes.small),
                                )
                                Spacer(Modifier.width(MaterialTheme.spacings.medium))
                                Column(modifier = Modifier) {
                                    Text(
                                        text = season.seriesName,
                                        overflow = TextOverflow.Ellipsis,
                                        maxLines = 1,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text(
                                        text = season.name,
                                        overflow = TextOverflow.Ellipsis,
                                        maxLines = 3,
                                        style = MaterialTheme.typography.headlineMedium,
                                    )
                                }
                            }
                        },
                    )
                    Spacer(Modifier.height(MaterialTheme.spacings.default.div(2)))
                    ItemButtonsBar(
                        item = season,
                        onPlayClick = { startFromBeginning, _, _, multitask ->
                            onAction(SeasonAction.Play(startFromBeginning = startFromBeginning, multitask = multitask))
                        },
                        onSyncPlayClick = null,
                        onMarkAsPlayedClick = {
                            when (season.played) {
                                true -> onAction(SeasonAction.UnmarkAsPlayed)
                                false -> onAction(SeasonAction.MarkAsPlayed)
                            }
                        },
                        onMarkAsFavoriteClick = {
                            when (season.favorite) {
                                true -> onAction(SeasonAction.UnmarkAsFavorite)
                                false -> onAction(SeasonAction.MarkAsFavorite)
                            }
                        },
                        onTrailerClick = {},
                        onDownloadClick = {},
                        onDownloadCancelClick = {},
                        onDownloadDeleteClick = {},
                        modifier =
                            Modifier.padding(start = paddingStart, end = paddingEnd).fillMaxWidth(),
                        canPlay = state.episodes.isNotEmpty(),
                    )
                }
                items(items = state.episodes, key = { episode -> episode.id }) { episode ->
                    val toggleSelection: () -> Unit = {
                        if (!episode.isDownloaded()) {
                            selectedIds =
                                if (selectedIds.contains(episode.id)) {
                                    selectedIds - episode.id
                                } else {
                                    selectedIds + episode.id
                                }
                        }
                    }
                    EpisodeCard(
                        episode = episode,
                        onClick = {
                            if (selectionMode) {
                                toggleSelection()
                            } else {
                                onAction(SeasonAction.NavigateToItem(episode))
                            }
                        },
                        onLongClick = toggleSelection,
                        selectionMode = selectionMode,
                        selected = selectedIds.contains(episode.id),
                        modifier = Modifier.padding(start = paddingStart, end = paddingEnd),
                    )
                }
            }
        } ?: run { CircularProgressIndicator(modifier = Modifier.align(Alignment.Center)) }

        if (selectionMode) {
            SeasonSelectionTopBar(
                count = selectedDownloadable.size,
                isQueuing = state.isQueuingBulkDownload,
                onCancel = { selectedIds = emptySet() },
                onDownload = {
                    if (selectedDownloadable.isNotEmpty()) {
                        pendingDownload = selectedDownloadable
                    }
                },
            )
        } else {
            ItemTopBar(
                hasBackButton = true,
                hasHomeButton = true,
                onBackClick = { onAction(SeasonAction.OnBackClick) },
                onHomeClick = { onAction(SeasonAction.OnHomeClick) },
            ) {
                Spacer(modifier = Modifier.width(4.dp))
                state.season?.let { season ->
                    Button(
                        onClick = { onAction(SeasonAction.NavigateToSeries(season.seriesId)) },
                        modifier = Modifier.alpha(0.7f),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = Color.Black,
                                contentColor = Color.White,
                            ),
                    ) {
                        Text(text = season.seriesName, overflow = TextOverflow.Ellipsis, maxLines = 1)
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                if (downloadableEpisodes.isNotEmpty() || unwatchedDownloadable.isNotEmpty()) {
                    Box {
                        IconButton(onClick = { overflowMenuOpen = true }) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = "More",
                            )
                        }
                        DropdownMenu(
                            expanded = overflowMenuOpen,
                            onDismissRequest = { overflowMenuOpen = false },
                        ) {
                            if (unwatchedDownloadable.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "Download all unwatched (${unwatchedDownloadable.size})",
                                        )
                                    },
                                    onClick = {
                                        overflowMenuOpen = false
                                        pendingDownload = unwatchedDownloadable
                                    },
                                )
                            }
                            if (downloadableEpisodes.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "Download season (${downloadableEpisodes.size})",
                                        )
                                    },
                                    onClick = {
                                        overflowMenuOpen = false
                                        pendingDownload = downloadableEpisodes
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDownload?.let { episodes ->
        XrConfirmDialog(
            title = "Download episodes",
            message = "${episodes.size} episodes will be queued for download.",
            confirmLabel = "Download",
            dismissLabel = stringResource(CoreR.string.cancel),
            onConfirm = {
                onAction(SeasonAction.DownloadEpisodes(episodes))
                pendingDownload = null
                selectedIds = emptySet()
            },
            onDismiss = { pendingDownload = null },
        )
    }

    DownloadBackgroundConsentDialog(
        show = state.isQueuingBulkDownload || state.bulkDownloadResult != null,
        onDismiss = {},
    )
}

@Composable
private fun SeasonSelectionTopBar(
    count: Int,
    isQueuing: Boolean,
    onCancel: () -> Unit,
    onDownload: () -> Unit,
) {
    val safePadding = rememberSafePadding()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = safePadding.start + MaterialTheme.spacings.default,
                    end = safePadding.end + MaterialTheme.spacings.default,
                    top = safePadding.top + MaterialTheme.spacings.small,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(CoreR.string.cancel),
            )
        }
        Spacer(Modifier.width(MaterialTheme.spacings.small))
        Text(
            text = "$count selected",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onDownload,
            enabled = count > 0 && !isQueuing,
        ) {
            Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = null,
            )
            Spacer(Modifier.width(MaterialTheme.spacings.small))
            Text(text = if (isQueuing) "Queuing…" else "Download $count")
        }
    }
}

@PreviewScreenSizes
@Composable
private fun SeasonScreenLayoutPreview() {
    SpatialFinTheme { SeasonScreenLayout(state = SeasonState(season = dummySeason), onAction = {}) }
}
