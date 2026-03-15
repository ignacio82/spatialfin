package dev.jdtech.jellyfin.presentation.film.components

import android.app.DownloadManager
import android.os.Environment
import android.os.StatFs
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.window.core.layout.WindowSizeClass
import androidx.xr.compose.spatial.SpatialDialog
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderState
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
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
    onMarkAsPlayedClick: () -> Unit,
    onMarkAsFavoriteClick: () -> Unit,
    onDownloadClick: (storageIndex: Int) -> Unit,
    onDownloadCancelClick: () -> Unit,
    onDownloadDeleteClick: () -> Unit,
    onTrailerClick: (uri: String) -> Unit,
    modifier: Modifier = Modifier,
    downloaderState: DownloaderState? = null,
    canPlay: Boolean = true,
    isForce3dMode: Boolean = false,
    onForce3dClick: (() -> Unit)? = null,
    initialMaxBitrate: Long = 0L,
) {
    val context = LocalContext.current
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass

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

    var storageSelectionDialogOpen by remember { mutableStateOf(false) }
    var cancelDownloadDialogOpen by remember { mutableStateOf(false) }
    var deleteDownloadDialogOpen by remember { mutableStateOf(false) }

    var selectedStorageIndex by remember { mutableIntStateOf(0) }
    var storageLocations = remember { context.getExternalFilesDirs(null) }

    var mediaSourceSelectionDialogOpen by remember { mutableStateOf(false) }
    var selectedMediaSourceIndex by remember { mutableStateOf<Int?>(null) }

    var qualitySelectionDialogOpen by remember { mutableStateOf(false) }
    var selectedMaxBitrate by remember { mutableStateOf<Long?>(initialMaxBitrate) }

    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        ) {
            if (
                !windowSizeClass.isWidthAtLeastBreakpoint(
                    WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND
                )
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
                    PlayButton(
                        item = item,
                        onClick = { onPlayClick(false, selectedMediaSourceIndex, selectedMaxBitrate) },
                        modifier = Modifier.weight(weight = 1f, fill = true),
                        enabled = item.canPlay && canPlay,
                    )
                    if (item.playbackPositionTicks.div(600000000) > 0) {
                        FilledTonalIconButton(onClick = { onPlayClick(true, selectedMediaSourceIndex, selectedMaxBitrate) }) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_rotate_ccw),
                                contentDescription = null,
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
                if (
                    windowSizeClass.isWidthAtLeastBreakpoint(
                        WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND
                    )
                ) {
                    PlayButton(
                        item = item,
                        onClick = { onPlayClick(false, selectedMediaSourceIndex, selectedMaxBitrate) },
                        enabled = item.canPlay && canPlay,
                    )
                    if (item.playbackPositionTicks.div(600000000) > 0) {
                        FilledTonalIconButton(onClick = { onPlayClick(true, selectedMediaSourceIndex, selectedMaxBitrate) }) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_rotate_ccw),
                                contentDescription = null,
                            )
                        }
                    }
                }
                trailerUri?.let { uri ->
                    FilledTonalIconButton(onClick = { onTrailerClick(uri) }) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_film),
                            contentDescription = null,
                        )
                    }
                }
                if (item.sources.size > 1) {
                    FilledTonalIconButton(onClick = { mediaSourceSelectionDialogOpen = true }) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_database),
                            contentDescription = "Select Media Source",
                        )
                    }
                }
                FilledTonalIconButton(onClick = { qualitySelectionDialogOpen = true }) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_sparkles),
                        contentDescription = "Select Quality",
                    )
                }
                if (onForce3dClick != null) {
                    FilledTonalIconButton(
                        onClick = onForce3dClick,
                        colors = androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(containerColor = if (isForce3dMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_3d),
                            contentDescription = "Force 3D Mode",
                        )
                    }
                }
                FilledTonalIconButton(onClick = onMarkAsPlayedClick) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_check),
                        contentDescription = null,
                        tint = if (item.played) Color.Red else LocalContentColor.current,
                    )
                }
                FilledTonalIconButton(onClick = onMarkAsFavoriteClick) {
                    when (item.favorite) {
                        true -> {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_heart_filled),
                                contentDescription = null,
                                tint = Color.Red,
                            )
                        }
                        false -> {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_heart),
                                contentDescription = null,
                            )
                        }
                    }
                }
                if (downloaderState != null && !downloaderState.isDownloading) {
                    if (item.isDownloaded()) {
                        FilledTonalIconButton(onClick = { deleteDownloadDialogOpen = true }) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_trash),
                                contentDescription = null,
                            )
                        }
                    } else if (item.canDownload) {
                        FilledTonalIconButton(
                            onClick = {
                                storageLocations = context.getExternalFilesDirs(null)
                                if (storageLocations.size > 1) {
                                    storageSelectionDialogOpen = true
                                } else {
                                    selectedStorageIndex = 0
                                    onDownloadClick(selectedStorageIndex)
                                }
                            }
                        ) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_download),
                                contentDescription = null,
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
                            onRetryClick = { onDownloadClick(selectedStorageIndex) },
                        )
                        Spacer(Modifier.height(MaterialTheme.spacings.small))
                    }
                }
            }
        }
        if (storageSelectionDialogOpen) {
            val locations = remember {
                storageLocations.map { dir ->
                    val locationStringRes =
                        if (Environment.isExternalStorageRemovable(dir)) CoreR.string.external
                        else CoreR.string.internal
                    val locationString = context.getString(locationStringRes)

                    val stat = StatFs(dir.path)
                    val availableMegaBytes = stat.availableBytes.div(1000000)
                    context.getString(CoreR.string.storage_name, locationString, availableMegaBytes)
                }
            }
            StorageSelectionDialog(
                storageLocations = locations,
                onSelect = { storageIndex ->
                    selectedStorageIndex = storageIndex
                    onDownloadClick(selectedStorageIndex)
                    storageSelectionDialogOpen = false
                },
                onDismiss = { storageSelectionDialogOpen = false },
            )
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
            onMarkAsPlayedClick = {},
            onMarkAsFavoriteClick = {},
            onDownloadClick = {},
            onDownloadCancelClick = {},
            onDownloadDeleteClick = {},
            onTrailerClick = {},
        )
    }
}
