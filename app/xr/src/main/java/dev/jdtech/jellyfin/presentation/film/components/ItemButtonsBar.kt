package dev.jdtech.jellyfin.presentation.film.components

import android.app.DownloadManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.presentation.components.BaseDialog
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.spatialfin.presentation.theme.spacings

@Composable
fun ItemButtonsBar(
    item: SpatialFinItem,
    onPlayClick: (startFromBeginning: Boolean, mediaSourceIndex: Int?, maxBitrate: Long?) -> Unit,
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
                    onClick = { onPlayClick(false, selectedMediaSourceIndex, selectedMaxBitrate) },
                    enabled = item.canPlay && canPlay,
                )
                if (item.playbackPositionTicks.div(600000000) > 0) {
                    XrActionButton(
                        label = "Restart",
                        icon = CoreR.drawable.ic_rotate_ccw,
                        onClick = { onPlayClick(true, selectedMediaSourceIndex, selectedMaxBitrate) },
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
    val bitrates = listOf(
        0L to "Auto",
        120_000_000L to "120 Mbps",
        80_000_000L to "80 Mbps",
        60_000_000L to "60 Mbps",
        40_000_000L to "40 Mbps",
        30_000_000L to "30 Mbps",
        20_000_000L to "20 Mbps",
        15_000_000L to "15 Mbps",
        10_000_000L to "10 Mbps",
        8_000_000L to "8 Mbps",
        6_000_000L to "6 Mbps",
        5_000_000L to "5 Mbps",
        4_000_000L to "4 Mbps",
        3_000_000L to "3 Mbps",
        2_000_000L to "2 Mbps",
        1_500_000L to "1.5 Mbps",
        1_000_000L to "1 Mbps",
        720_000L to "720 Kbps",
        480_000L to "480 Kbps",
    )
    BaseDialog(
        title = "Select Quality",
        onDismiss = onDismiss
    ) {
        Column(
            modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())
        ) {
            bitrates.forEach { (bitrate, label) ->
                Row(
                    modifier = Modifier.clickable { onSelect(bitrate) }.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedBitrate == bitrate,
                        onClick = { onSelect(bitrate) }
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(label, style = MaterialTheme.typography.titleMedium)
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
            onPlayClick = { _, _, _ -> },
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
            onPlayClick = { _, _, _ -> },
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
