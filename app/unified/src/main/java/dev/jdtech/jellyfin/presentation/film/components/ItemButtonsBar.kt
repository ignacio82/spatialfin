package dev.jdtech.jellyfin.presentation.film.components

import android.app.DownloadManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.xr.compose.spatial.SpatialDialog
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderState
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.models.DownloadMode
import dev.jdtech.jellyfin.models.DownloadRequest
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.presentation.components.BaseDialog
import dev.jdtech.jellyfin.settings.presentation.enums.QualityOption
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.spatialfin.presentation.theme.spacings

@Composable
fun ItemButtonsBar(
    item: SpatialFinItem,
    onPlayClick: (startFromBeginning: Boolean, mediaSourceIndex: Int?, maxBitrate: Long?, multitask: Boolean) -> Unit,
    onSyncPlayClick: (() -> Unit)?,
    onMarkAsPlayedClick: () -> Unit,
    onMarkAsFavoriteClick: () -> Unit,
    onDownloadClick: (request: DownloadRequest) -> Unit,
    onDownloadCancelClick: () -> Unit,
    onDownloadDeleteClick: () -> Unit,
    onTrailerClick: (uri: String) -> Unit,
    modifier: Modifier = Modifier,
    downloaderState: DownloaderState? = null,
    canPlay: Boolean = true,
    initialMaxBitrate: Long = 0L,
    /**
     * Called after the user saves a new IMDb ID from the overflow dialog.
     * Repo-side, the save kicks a metadata refresh on Jellyfin; the callback
     * lets the parent ViewModel schedule its own delayed reload to surface
     * the freshly-fetched title / overview / images once the server finishes
     * re-pulling. Default is a no-op — screens that don't care (previews,
     * tests) don't have to thread anything.
     */
    onMetadataSaved: () -> Unit = {},
) {
    val context = LocalContext.current

    val trailerUri =
        when (item) {
            is SpatialFinMovie -> {
                item.trailer
            }
            is SpatialFinShow -> {
                item.trailer
            }
            else -> null
        }

    var downloadOptionsDialogOpen by remember { mutableStateOf(false) }
    var cancelDownloadDialogOpen by remember { mutableStateOf(false) }
    var deleteDownloadDialogOpen by remember { mutableStateOf(false) }
    var lastDownloadRequest by remember(item.id) {
        mutableStateOf(
            DownloadRequest(
                sourceId = item.sources.firstOrNull()?.id.orEmpty(),
                mode = DownloadMode.ORIGINAL,
            )
        )
    }

    var mediaSourceSelectionDialogOpen by remember { mutableStateOf(false) }
    var selectedMediaSourceIndex by remember { mutableStateOf<Int?>(null) }

    var qualitySelectionDialogOpen by remember { mutableStateOf(false) }
    var selectedMaxBitrate by remember { mutableStateOf<Long?>(initialMaxBitrate) }

    var overflowMenuOpen by remember { mutableStateOf(false) }
    var editExternalIdsDialogOpen by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
            ) {
                PlayButton(
                    item = item,
                    onClick = { onPlayClick(false, selectedMediaSourceIndex, selectedMaxBitrate, false) },
                    enabled = item.canPlay && canPlay,
                )
                if (item.canPlay && canPlay) {
                    XrActionButton(
                        label = "Multitask",
                        icon = CoreR.drawable.ic_picture_in_picture,
                        onClick = { onPlayClick(false, selectedMediaSourceIndex, selectedMaxBitrate, true) },
                    )
                }
                if (item.playbackPositionTicks.div(600000000) > 0) {
                    XrActionButton(
                        label = "Restart",
                        icon = CoreR.drawable.ic_rotate_ccw,
                        onClick = { onPlayClick(true, selectedMediaSourceIndex, selectedMaxBitrate, false) },
                    )
                }
                onSyncPlayClick?.let { syncClick ->
                    XrActionButton(label = "SyncPlay", icon = CoreR.drawable.ic_tv, onClick = syncClick)
                }
                trailerUri?.let { uri ->
                    XrActionButton(label = "Trailer", icon = CoreR.drawable.ic_film, onClick = { onTrailerClick(uri) })
                }
                if (item.sources.size > 1) {
                    XrActionButton(
                        label = "Version",
                        icon = CoreR.drawable.ic_database,
                        onClick = { mediaSourceSelectionDialogOpen = true },
                    )
                }
                XrActionButton(
                    label = "Quality",
                    icon = CoreR.drawable.ic_sparkles,
                    onClick = { qualitySelectionDialogOpen = true },
                )
                XrActionButton(
                    label = if (item.played) "Watched" else "Mark Watched",
                    icon = CoreR.drawable.ic_check,
                    onClick = onMarkAsPlayedClick,
                    emphasized = item.played,
                    iconTint = if (item.played) Color.Red else LocalContentColor.current,
                )
                XrActionButton(
                    label = if (item.favorite) "Favorite" else "Add Favorite",
                    icon = if (item.favorite) CoreR.drawable.ic_heart_filled else CoreR.drawable.ic_heart,
                    onClick = onMarkAsFavoriteClick,
                    emphasized = item.favorite,
                    iconTint = if (item.favorite) Color.Red else LocalContentColor.current,
                )
                if (downloaderState != null && !downloaderState.isDownloading) {
                    if (item.isDownloaded()) {
                        XrActionButton(
                            label = "Delete Download",
                            icon = CoreR.drawable.ic_trash,
                            onClick = { deleteDownloadDialogOpen = true },
                        )
                    } else if (item.canDownload) {
                        XrActionButton(
                            label = "Download",
                            icon = CoreR.drawable.ic_download,
                            onClick = { downloadOptionsDialogOpen = true },
                        )
                    }
                }
                // 3-dots overflow: keeps "fix the metadata" out of the bar's
                // default real estate but one tap away. Surfaced for anything
                // Jellyfin actually stores providerIds on — movies, shows, and
                // individual episodes. Seasons/collections aren't meaningfully
                // writable (the series owns the IDs for a season's episodes,
                // and collections aren't real Jellyfin items with externals).
                if (item is SpatialFinMovie || item is SpatialFinShow || item is SpatialFinEpisode) {
                    // The 3-dots overflow uses a dedicated tonal button so it
                    // visually belongs with the other actions in the FlowRow
                    // rather than squeezing in as a floating IconButton. The
                    // anchor Box scopes the DropdownMenu to the button's
                    // position — without it, the menu anchors to (0,0) on the
                    // enclosing layout.
                    Box {
                        FilledTonalButton(
                            onClick = { overflowMenuOpen = true },
                            modifier = Modifier.height(64.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreHoriz,
                                contentDescription = "More actions",
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = overflowMenuOpen,
                            onDismissRequest = { overflowMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Edit external IDs") },
                                onClick = {
                                    overflowMenuOpen = false
                                    editExternalIdsDialogOpen = true
                                },
                            )
                        }
                    }
                }
            }
            if (downloaderState != null) {
                AnimatedVisibility(downloaderState.isDownloading) {
                    Column {
                        DownloaderCard(
                            state = downloaderState,
                            onCancelClick = { cancelDownloadDialogOpen = true },
                            onRetryClick = { onDownloadClick(lastDownloadRequest) },
                        )
                        Spacer(Modifier.height(MaterialTheme.spacings.small))
                    }
                }
            }
        }
        if (downloadOptionsDialogOpen) {
            SpatialDialog(onDismissRequest = { downloadOptionsDialogOpen = false }) {
                DownloadOptionsDialog(
                    sources = item.sources,
                    initialRequest =
                        lastDownloadRequest.copy(
                            sourceId =
                                lastDownloadRequest.sourceId.ifBlank {
                                    item.sources.firstOrNull()?.id.orEmpty()
                                }
                        ),
                    onConfirm = { request ->
                        lastDownloadRequest = request
                        onDownloadClick(request)
                        downloadOptionsDialogOpen = false
                    },
                    onDismiss = { downloadOptionsDialogOpen = false },
                )
            }
        }
        if (cancelDownloadDialogOpen) {
            CancelDownloadDialog(
                onCancel = {
                    onDownloadCancelClick()
                    cancelDownloadDialogOpen = false
                },
                onDismiss = { cancelDownloadDialogOpen = false },
            )
        }
        if (deleteDownloadDialogOpen) {
            DeleteDownloadDialog(
                onDelete = {
                    onDownloadDeleteClick()
                    deleteDownloadDialogOpen = false
                },
                onDismiss = { deleteDownloadDialogOpen = false },
            )
        }
        if (mediaSourceSelectionDialogOpen) {
            SpatialDialog(onDismissRequest = { mediaSourceSelectionDialogOpen = false }) {
                MediaSourceSelectionDialog(
                    sources = item.sources,
                    selectedIndex = selectedMediaSourceIndex ?: 0,
                    onSelect = { index ->
                        selectedMediaSourceIndex = index
                        mediaSourceSelectionDialogOpen = false
                    },
                    onDismiss = { mediaSourceSelectionDialogOpen = false }
                )
            }
        }
        if (qualitySelectionDialogOpen) {
            SpatialDialog(onDismissRequest = { qualitySelectionDialogOpen = false }) {
                QualitySelectionDialog(
                    selectedBitrate = selectedMaxBitrate ?: 0L,
                    onSelect = { bitrate ->
                        selectedMaxBitrate = bitrate
                        qualitySelectionDialogOpen = false
                    },
                    onDismiss = { qualitySelectionDialogOpen = false }
                )
            }
        }
        if (editExternalIdsDialogOpen) {
            val initialYear = when (item) {
                is SpatialFinMovie -> item.productionYear
                is SpatialFinShow -> item.productionYear
                else -> null
            }
            // Episodes get composed as "Show — S1E2: Title" so OMDb has a
            // fighting chance of matching the episode page rather than the
            // series.  Yearless episodes stay as just the episode title.
            val initialTitle = when (item) {
                is SpatialFinEpisode ->
                    buildString {
                        if (item.seriesName.isNotBlank()) append(item.seriesName).append(" ")
                        append("S").append(item.parentIndexNumber).append("E").append(item.indexNumber)
                        if (item.name.isNotBlank()) append(" ").append(item.name)
                    }
                else -> item.name
            }
            SpatialDialog(onDismissRequest = { editExternalIdsDialogOpen = false }) {
                EditExternalIdsDialog(
                    itemId = item.id,
                    initialTitle = initialTitle,
                    initialYear = initialYear,
                    onDismiss = { editExternalIdsDialogOpen = false },
                    onSaved = onMetadataSaved,
                )
            }
        }
    }
}

@Composable
private fun XrActionButton(
    label: String,
    icon: Int,
    onClick: () -> Unit,
    emphasized: Boolean = false,
    iconTint: Color = LocalContentColor.current,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.height(64.dp),
        colors =
            ButtonDefaults.filledTonalButtonColors(
                containerColor =
                    if (emphasized) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.secondaryContainer,
            ),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = iconTint,
        )
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun MediaSourceSelectionDialog(
    sources: List<SpatialFinSource>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    BaseDialog(
        title = "Select Media Source",
        onDismiss = onDismiss
    ) {
        Column(
            modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())
        ) {
            sources.forEachIndexed { index, source ->
                Row(
                    modifier = Modifier.clickable { onSelect(index) }.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) }
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(source.name, style = MaterialTheme.typography.titleMedium)
                        if (source.size > 0) {
                            Text(
                                formatFileSize(source.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QualitySelectionDialog(
    selectedBitrate: Long,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentOption = QualityOption.fromBps(selectedBitrate)
    BaseDialog(
        title = "Select Quality",
        onDismiss = onDismiss
    ) {
        Column(
            modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())
        ) {
            QualityOption.entries.forEach { option ->
                Row(
                    modifier = Modifier.clickable { onSelect(option.bps) }.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = currentOption == option,
                        onClick = { onSelect(option.bps) }
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(androidx.compose.ui.res.stringResource(option.labelRes), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

private fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return "%.1f %s".format(size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

@Preview(showBackground = true)
@Composable
private fun ItemButtonsBarPreview() {
    SpatialFinTheme {
        ItemButtonsBar(
            item = dummyEpisode,
            onPlayClick = { _, _, _, _ -> },
            onSyncPlayClick = null,
            onMarkAsPlayedClick = {},
            onMarkAsFavoriteClick = {},
            onDownloadClick = {},
            onDownloadCancelClick = {},
            onDownloadDeleteClick = {},
            onTrailerClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ItemButtonsBarDownloadingPreview() {
    SpatialFinTheme {
        ItemButtonsBar(
            item = dummyEpisode,
            downloaderState =
                DownloaderState(status = DownloadManager.STATUS_RUNNING, progress = 0.3f),
            onPlayClick = { _, _, _, _ -> },
            onSyncPlayClick = null,
            onMarkAsPlayedClick = {},
            onMarkAsFavoriteClick = {},
            onDownloadClick = {},
            onDownloadCancelClick = {},
            onDownloadDeleteClick = {},
            onTrailerClick = {},
        )
    }
}
