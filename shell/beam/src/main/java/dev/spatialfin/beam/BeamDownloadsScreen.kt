package dev.spatialfin.beam

import android.app.DownloadManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.film.presentation.downloads.DownloadSortOrder
import dev.jdtech.jellyfin.film.presentation.downloads.DownloadsViewModel
import dev.jdtech.jellyfin.models.SpatialFinBoxSet
import dev.jdtech.jellyfin.models.SpatialFinCollection
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinFolder
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.utils.ActiveDownloadEntry
import java.util.UUID

/**
 * The Downloads tab and its active / server / downloaded card variants.
 *
 * Split out of BeamJellyfinScreens.kt, which had grown past 4000 lines. Same
 * package, so nothing here changed except its file.
 */
@Composable
fun BeamDownloadsScreen(
    contentPadding: PaddingValues,
    onOpenShow: (UUID) -> Unit,
    onOpenSeason: (UUID) -> Unit,
    onOpenItem: (UUID) -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()
    val storageUsedBytes by viewModel.storageUsedBytes.collectAsStateWithLifecycle()
    val continueWatchingItems by viewModel.continueWatchingItems.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val fcastSession = dev.spatialfin.fcast.session.LocalFCastSession.current
    val scope = rememberCoroutineScope()
    var pendingDeleteItem by remember { mutableStateOf<SpatialFinItem?>(null) }

    LaunchedEffect(Unit) {
        viewModel.loadItems()
    }

    pendingDeleteItem?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDeleteItem = null },
            title = { Text("Delete Download") },
            text = { Text("Remove ${item.name} from downloaded media?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteItem(item)
                        pendingDeleteItem = null
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteItem = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    BeamScaffoldBody(contentPadding = contentPadding) {
        item {
            val storageLabel = if (storageUsedBytes > 0L) {
                android.text.format.Formatter.formatFileSize(context, storageUsedBytes)
            } else null
            BeamScreenHeader(
                title = "Downloads",
                body = storageLabel
                    ?.let { "Movies and shows saved for offline playback. Using $it." }
                    ?: "Movies and shows saved for offline playback.",
            )
        }
        if (activeDownloads.isNotEmpty()) {
            item {
                Text(
                    text = "In Progress",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(activeDownloads, key = { it.taskId }) { entry ->
                BeamActiveDownloadCard(
                    entry = entry,
                    onPause = { viewModel.pauseDownload(entry.itemId) },
                    onResume = { viewModel.resumeDownload(entry.itemId) },
                    onCancel = { viewModel.cancelActiveDownload(entry.itemId) },
                )
            }
        }
        if (continueWatchingItems.isNotEmpty()) {
            item {
                Text(
                    text = "Continue Watching",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(continueWatchingItems, key = { it.id }) { item ->
                BeamDownloadedItemCard(
                    item = item,
                    onPlay = { launchServerItem(context, fcastSession, scope,item) },
                    onOpen = {
                        when (item) {
                            is SpatialFinShow -> onOpenShow(item.id)
                            is SpatialFinSeason -> onOpenSeason(item.id)
                            else -> onOpenItem(item.id)
                        }
                    },
                    onDelete = { pendingDeleteItem = item },
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = viewModel::setSearchQuery,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search downloads...") },
                    singleLine = true,
                )
                TextButton(
                    onClick = {
                        viewModel.setSortOrder(
                            if (sortOrder == DownloadSortOrder.NAME) DownloadSortOrder.DATE_ADDED
                            else DownloadSortOrder.NAME
                        )
                    },
                ) {
                    Text(if (sortOrder == DownloadSortOrder.NAME) "A–Z" else "Recent")
                }
            }
        }
        when {
            state.isLoading -> item { LoadingCard("Loading downloads...") }
            state.error != null -> item {
                ErrorCard(
                    title = "Failed to load downloads.",
                    body = state.error?.localizedMessage ?: "Unknown error",
                    onRetry = viewModel::loadItems,
                )
            }
            state.sections.isEmpty() && activeDownloads.isEmpty() ->
                item { BeamEmptyCard("No downloaded media found.") }
            else -> {
                state.sections.forEach { section ->
                    item {
                        Text(
                            text = section.name.asString(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    items(section.items, key = { it.id }) { item ->
                        BeamDownloadedItemCard(
                            item = item,
                            onPlay = { launchServerItem(context, fcastSession, scope,item) },
                            onOpen = {
                                when (item) {
                                    is SpatialFinShow -> onOpenShow(item.id)
                                    is SpatialFinSeason -> onOpenSeason(item.id)
                                    else -> onOpenItem(item.id)
                                }
                            },
                            onDelete = { pendingDeleteItem = item },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun BeamActiveDownloadCard(
    entry: ActiveDownloadEntry,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val statusLabel = when (entry.status) {
        android.app.DownloadManager.STATUS_RUNNING -> {
            val total = entry.totalBytes
            val sizeStr = if (total != null && total > 0) {
                "${android.text.format.Formatter.formatFileSize(context, entry.bytesDownloaded)} / ${android.text.format.Formatter.formatFileSize(context, total)}"
            } else {
                "${entry.progress}%"
            }
            val speed = entry.downloadSpeedBytesPerSec
            if (speed != null && speed > 0) {
                "$sizeStr · ${android.text.format.Formatter.formatFileSize(context, speed)}/s"
            } else sizeStr
        }
        android.app.DownloadManager.STATUS_PAUSED -> "Paused"
        android.app.DownloadManager.STATUS_FAILED -> "Failed"
        else -> "Pending"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = entry.itemName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.status == android.app.DownloadManager.STATUS_RUNNING || entry.status == android.app.DownloadManager.STATUS_PAUSED) {
                LinearProgressIndicator(
                    progress = { entry.progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = if (entry.status == android.app.DownloadManager.STATUS_PAUSED)
                        MaterialTheme.colorScheme.outline
                    else
                        MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            entry.errorMessage?.takeIf { it.isNotBlank() && it != "Paused" }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (entry.status) {
                    android.app.DownloadManager.STATUS_RUNNING -> {
                        FilledTonalButton(onClick = onPause) { Text("Pause") }
                        FilledTonalButton(onClick = onCancel) { Text("Cancel") }
                    }
                    android.app.DownloadManager.STATUS_PAUSED,
                    android.app.DownloadManager.STATUS_FAILED -> {
                        FilledTonalButton(onClick = onResume) { Text("Resume") }
                        FilledTonalButton(onClick = onCancel) { Text("Cancel") }
                    }
                    else -> {
                        FilledTonalButton(onClick = onCancel) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BeamServerItemCard(
    item: SpatialFinItem,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
) {
    BeamMediaFeatureCard(
        item = item,
        actions = {
            when (item) {
                is SpatialFinMovie,
                is SpatialFinEpisode,
                is SpatialFinShow,
                is SpatialFinSeason,
                is SpatialFinBoxSet -> {
                    BeamPrimaryActionButton(label = "Play", onClick = onPlay)
                }
                else -> Unit
            }
            BeamSecondaryActionButton(
                label =
                    when (item) {
                        is SpatialFinCollection,
                        is SpatialFinFolder -> "Open"
                        else -> "Details"
                    },
                onClick = onOpen,
            )
        },
    )
}

@Composable
internal fun BeamDownloadedItemCard(
    item: SpatialFinItem,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    BeamMediaFeatureCard(
        item = item,
        actions = {
            BeamPrimaryActionButton(label = "Play", onClick = onPlay)
            BeamSecondaryActionButton(label = "Details", onClick = onOpen)
            BeamSecondaryActionButton(label = "Delete", onClick = onDelete)
        },
    )
}
