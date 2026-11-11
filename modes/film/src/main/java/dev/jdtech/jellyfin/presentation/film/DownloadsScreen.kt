package dev.jdtech.jellyfin.presentation.film

import android.os.Build
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovies
import dev.jdtech.jellyfin.downloads.OrphanedDownloadsReport
import dev.jdtech.jellyfin.film.presentation.collection.CollectionAction
import dev.jdtech.jellyfin.film.presentation.collection.CollectionState
import dev.jdtech.jellyfin.film.presentation.downloads.DownloadsViewModel
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.presentation.film.components.ActiveDownloadRow
import dev.jdtech.jellyfin.presentation.film.components.BrowseHeaderAction
import dev.jdtech.jellyfin.presentation.film.components.CollectionGrid
import dev.jdtech.jellyfin.presentation.film.components.DeleteDownloadDialog
import dev.jdtech.jellyfin.presentation.film.components.XrBrowseHeader
import dev.jdtech.jellyfin.utils.ActiveDownloadEntry
import dev.jdtech.jellyfin.utils.DownloadVerificationReport
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.spatialfin.presentation.theme.spacings

@Composable
fun DownloadsScreen(
    onItemClick: (item: SpatialFinItem) -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()
    val storageUsedBytes by viewModel.storageUsedBytes.collectAsStateWithLifecycle()
    val isVerifying by viewModel.isVerifying.collectAsStateWithLifecycle()
    val verificationReport by viewModel.verificationReport.collectAsStateWithLifecycle()
    val orphanedReport by viewModel.orphanedReport.collectAsStateWithLifecycle()
    val isCleaningOrphans by viewModel.isCleaningOrphans.collectAsStateWithLifecycle()
    val pendingMediaStoreDeleteUris by viewModel.pendingMediaStoreDeleteUris.collectAsStateWithLifecycle()
    val autoDeleteWatched by viewModel.autoDeleteWatched.collectAsStateWithLifecycle()
    val autoDownloadNextCount by viewModel.autoDownloadNextCount.collectAsStateWithLifecycle()
    var pendingDeleteItem by remember { mutableStateOf<SpatialFinItem?>(null) }
    var showHygieneDialog by remember { mutableStateOf(false) }

    val mediaStoreDeleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) {
        viewModel.onMediaStoreDeleteCompleted()
    }

    LaunchedEffect(pendingMediaStoreDeleteUris) {
        if (pendingMediaStoreDeleteUris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val pendingIntent = MediaStore.createDeleteRequest(
                    context.contentResolver,
                    pendingMediaStoreDeleteUris,
                )
                mediaStoreDeleteLauncher.launch(
                    IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
                )
            }.onFailure {
                viewModel.onMediaStoreDeleteCompleted()
            }
        } else if (pendingMediaStoreDeleteUris.isNotEmpty()) {
            viewModel.onMediaStoreDeleteCompleted()
        }
    }

    LaunchedEffect(true) { viewModel.loadItems() }

    DownloadsScreenLayout(
        state = state,
        activeDownloads = activeDownloads,
        storageUsedBytes = storageUsedBytes,
        isVerifying = isVerifying,
        verificationReport = verificationReport,
        orphanedReport = orphanedReport,
        isCleaningOrphans = isCleaningOrphans,
        autoDeleteWatched = autoDeleteWatched,
        autoDownloadNextCount = autoDownloadNextCount,
        onAutoDeleteWatchedChange = { viewModel.setAutoDeleteWatched(it) },
        onAutoDownloadNextCountChange = { viewModel.setAutoDownloadNextCount(it) },
        onVerifyClick = { viewModel.verifyDownloadsManually() },
        onCleanOrphansClick = { viewModel.cleanOrphanedDownloads() },
        onOpenHygieneClick = { showHygieneDialog = true },
        onDismissReport = { viewModel.dismissVerificationReport() },
        onDeleteItem = { pendingDeleteItem = it },
        onAction = { action ->
            when (action) {
                is CollectionAction.OnItemClick -> onItemClick(action.item)
                is CollectionAction.OnBackClick -> Unit
            }
        },
    )

    pendingDeleteItem?.let { item ->
        DeleteDownloadDialog(
            onDelete = {
                viewModel.deleteItem(item)
                pendingDeleteItem = null
            },
            onDismiss = { pendingDeleteItem = null },
        )
    }

    if (showHygieneDialog) {
        EpisodeDownloadHygieneDialog(
            autoDeleteWatched = autoDeleteWatched,
            autoDownloadNextCount = autoDownloadNextCount,
            onAutoDeleteWatchedChange = { viewModel.setAutoDeleteWatched(it) },
            onAutoDownloadNextCountChange = { viewModel.setAutoDownloadNextCount(it) },
            onDismiss = { showHygieneDialog = false },
        )
    }
}

@Composable
private fun EpisodeDownloadHygieneDialog(
    autoDeleteWatched: Boolean,
    autoDownloadNextCount: Int,
    onAutoDeleteWatchedChange: (Boolean) -> Unit,
    onAutoDownloadNextCountChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Episode Download Hygiene",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Auto-delete watched episodes",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Remove downloaded episode files after you finish watching them",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoDeleteWatched,
                        onCheckedChange = onAutoDeleteWatchedChange,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Auto-download next unwatched episodes",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Queue upcoming episodes when a downloaded episode finishes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "Off", 1 to "Next 1", 2 to "Next 2").forEach { (count, label) ->
                            FilterChip(
                                selected = autoDownloadNextCount == count,
                                onClick = { onAutoDownloadNextCountChange(count) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CoreR.string.close))
            }
        },
    )
}

@Composable
private fun DownloadsScreenLayout(
    state: CollectionState,
    activeDownloads: List<ActiveDownloadEntry> = emptyList(),
    storageUsedBytes: Long = 0L,
    isVerifying: Boolean = false,
    verificationReport: DownloadVerificationReport? = null,
    orphanedReport: OrphanedDownloadsReport = OrphanedDownloadsReport(),
    isCleaningOrphans: Boolean = false,
    autoDeleteWatched: Boolean = false,
    autoDownloadNextCount: Int = 0,
    onAutoDeleteWatchedChange: (Boolean) -> Unit = {},
    onAutoDownloadNextCountChange: (Int) -> Unit = {},
    onVerifyClick: () -> Unit = {},
    onCleanOrphansClick: () -> Unit = {},
    onOpenHygieneClick: () -> Unit = {},
    onDismissReport: () -> Unit = {},
    onDeleteItem: ((SpatialFinItem) -> Unit)? = null,
    onAction: (CollectionAction) -> Unit,
) {
    val safePadding = rememberSafePadding()
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(
                        start = safePadding.start + MaterialTheme.spacings.default,
                        top = safePadding.top + MaterialTheme.spacings.default,
                        end = safePadding.end + MaterialTheme.spacings.default,
                    ),
        ) {
            XrBrowseHeader(
                title = stringResource(CoreR.string.title_download),
                secondaryAction = BrowseHeaderAction(
                    label = "Episode hygiene",
                    icon = CoreR.drawable.ic_settings,
                    onClick = onOpenHygieneClick,
                ),
                primaryAction = BrowseHeaderAction(
                    label = if (isVerifying) "Verifying…" else "Verify files",
                    icon = CoreR.drawable.ic_check,
                    onClick = onVerifyClick,
                ),
            )
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        FilterChip(
                            selected = autoDeleteWatched,
                            onClick = { onAutoDeleteWatchedChange(!autoDeleteWatched) },
                            label = {
                                Text(
                                    text = if (autoDeleteWatched) "Auto-delete watched: On" else "Auto-delete watched: Off",
                                )
                            },
                        )
                        Text(
                            text = "Auto-download next:",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        listOf(0 to "Off", 1 to "Next 1", 2 to "Next 2").forEach { (count, label) ->
                            FilterChip(
                                selected = autoDownloadNextCount == count,
                                onClick = { onAutoDownloadNextCountChange(count) },
                                label = { Text(label) },
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (activeDownloads.isNotEmpty()) {
                            Text(
                                text = "${activeDownloads.size} downloading",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (storageUsedBytes > 0L) {
                            Text(
                                text = Formatter.formatFileSize(context, storageUsedBytes) + " used",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (orphanedReport.totalCount > 0) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${orphanedReport.totalCount} orphaned file(s) using ${
                                    Formatter.formatFileSize(context, orphanedReport.totalBytes)
                                } on disk.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                        TextButton(
                            onClick = onCleanOrphansClick,
                            enabled = !isCleaningOrphans,
                        ) {
                            Text(
                                text = if (isCleaningOrphans) "Cleaning…" else "Clean up",
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }
            }
            verificationReport?.let { report ->
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (report.missingOrCorruptedCount > 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (report.totalChecked == 0) {
                                    "No completed downloads to verify."
                                } else if (report.missingOrCorruptedCount > 0) {
                                    "Integrity check: ${report.missingOrCorruptedCount} corrupted or missing file(s) requeued for re-download (${report.intactCount} healthy)."
                                } else {
                                    "Integrity check: All ${report.intactCount} downloaded file(s) verified healthy."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (report.missingOrCorruptedCount > 0) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        TextButton(onClick = onDismissReport) {
                            Text(
                                stringResource(CoreR.string.close),
                                color = if (report.missingOrCorruptedCount > 0) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
            if (activeDownloads.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    activeDownloads.take(3).forEach { entry ->
                        ActiveDownloadRow(entry = entry)
                    }
                    if (activeDownloads.size > 3) {
                        Text(
                            text = "+ ${activeDownloads.size - 3} more queued",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.sections.isEmpty()) {
                Text(
                    text = stringResource(CoreR.string.no_downloads),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.headlineSmall,
                )
            } else {
                CollectionGrid(
                    sections = state.sections,
                    innerPadding =
                        PaddingValues(
                            top = 12.dp,
                            bottom = safePadding.bottom,
                        ),
                    onAction = onAction,
                    onDeleteItem = onDeleteItem,
                )
            }
        }
    }
}

@PreviewScreenSizes
@Composable
private fun DownloadsScreenLayoutPreview() {
    SpatialFinTheme {
        DownloadsScreenLayout(
            state =
                CollectionState(
                    sections =
                        listOf(
                            CollectionSection(
                                id = 0,
                                name = UiText.StringResource(CoreR.string.movies_label),
                                items = dummyMovies,
                            )
                        )
                ),
            activeDownloads = emptyList(),
            storageUsedBytes = 4_500_000_000L,
            onDeleteItem = {},
            onAction = {},
        )
    }
}
