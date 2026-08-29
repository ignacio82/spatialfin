package dev.spatialfin.beam

import android.app.DownloadManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.models.BulkDownloadSettings
import dev.jdtech.jellyfin.models.DownloadMode
import dev.jdtech.jellyfin.models.DownloadRequest
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMediaStream
import dev.jdtech.jellyfin.models.SpatialFinSourceType
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.models.isDownloading
import dev.jdtech.jellyfin.settings.presentation.enums.QualityOption
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod

/**
 * Download and playback-option dialogs, and the per-item download action row.
 *
 * Split out of BeamJellyfinScreens.kt, which had grown past 4000 lines. Same
 * package, so nothing here changed except its file.
 */
@Composable
internal fun BeamPlaybackOptionsDialog(
    item: SpatialFinItem,
    onDismiss: () -> Unit,
    onPlay: (sourceIndex: Int?, bitrate: Long?, fromBeginning: Boolean) -> Unit,
) {
    val sources = remember(item) { item.sources.filter { it.type == SpatialFinSourceType.REMOTE } }
    var selectedSourceIndex by remember { mutableStateOf(0) }
    var selectedBitrate by remember { mutableStateOf(0L) }
    var startFromBeginning by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playback Options") },
        text = {
            BeamScrollableDialogBody {
                Text(
                    "Choose the version and streaming quality before playback starts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (sources.size > 1) {
                    BeamChoiceSection(title = "Version") {
                        sources.forEachIndexed { index, source ->
                            BeamChoiceRow(
                                selected = selectedSourceIndex == index,
                                title = source.name.ifBlank { "Source ${index + 1}" },
                                subtitle = source.size.takeIf { it > 0 }?.let(::formatDownloadFileSize),
                                onClick = { selectedSourceIndex = index },
                            )
                        }
                    }
                }
                BeamChoiceSection(title = "Streaming Quality") {
                    QualityOption.entries.forEach { option ->
                        BeamChoiceRow(
                            selected = selectedBitrate == option.bps,
                            title = stringResource(option.labelRes),
                            subtitle = if (option == QualityOption.AUTO) {
                                "Let Jellyfin choose the best direct play or transcode path."
                            } else null,
                            onClick = { selectedBitrate = option.bps },
                        )
                    }
                }
                BeamChoiceSection(title = "Start Position") {
                    BeamChoiceRow(
                        selected = !startFromBeginning,
                        title = "Resume",
                        onClick = { startFromBeginning = false },
                    )
                    BeamChoiceRow(
                        selected = startFromBeginning,
                        title = "Play From Start",
                        onClick = { startFromBeginning = true },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val sourceIndex = if (sources.size > 1) selectedSourceIndex else null
                    onPlay(sourceIndex, selectedBitrate.takeIf { it > 0L }, startFromBeginning)
                },
            ) {
                Text("Play")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
internal fun BeamDownloadActions(
    item: SpatialFinItem,
    downloaderState: dev.jdtech.jellyfin.core.presentation.downloader.DownloaderState,
    onOpenOptions: () -> Unit,
    onCancelDownload: () -> Unit,
    onPauseDownload: () -> Unit,
    onResumeDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
) {
    if (!item.canDownload) return

    val isDownloaded = item.isDownloaded()
    val isDownloading = item.isDownloading() || downloaderState.isDownloading
    val isPaused = downloaderState.status == DownloadManager.STATUS_PAUSED
    val isFailed = downloaderState.status == DownloadManager.STATUS_FAILED

    when {
        isDownloaded -> {
            androidx.compose.material3.FilledTonalIconButton(onClick = onDeleteDownload) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Rounded.DownloadDone,
                    contentDescription = "Delete Download"
                )
            }
        }
        isPaused || isFailed -> {
            androidx.compose.material3.FilledTonalIconButton(onClick = onResumeDownload) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Rounded.Download,
                    contentDescription = "Resume Download"
                )
            }
            androidx.compose.material3.FilledTonalIconButton(onClick = onCancelDownload) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Rounded.Close,
                    contentDescription = "Cancel Download"
                )
            }
        }
        isDownloading -> {
            androidx.compose.material3.FilledTonalIconButton(onClick = onPauseDownload) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Rounded.Pause,
                    contentDescription = "Pause Download"
                )
            }
            androidx.compose.material3.FilledTonalIconButton(onClick = onCancelDownload) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Rounded.Close,
                    contentDescription = "Cancel Download"
                )
            }
        }
        else -> {
            androidx.compose.material3.FilledTonalIconButton(onClick = onOpenOptions) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Rounded.Download,
                    contentDescription = "Download"
                )
            }
        }
    }
}

@Composable
internal fun BeamDownloadOptionsDialog(
    item: SpatialFinItem,
    onConfirm: (DownloadRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    val sources = remember(item) { item.sources.filter { it.type == SpatialFinSourceType.REMOTE } }
    if (sources.isEmpty()) return

    var selectedSourceIndex by remember { mutableStateOf(0) }
    var selectedMode by remember { mutableStateOf(DownloadMode.ORIGINAL) }
    var selectedBitrate by remember { mutableStateOf(DEFAULT_DOWNLOAD_BITRATES.first().first) }
    var selectedAudioStreamIndex by remember { mutableStateOf<Int?>(null) }
    var selectedSubtitleStreamIndex by remember { mutableStateOf<Int?>(null) }

    val selectedSource = sources.getOrNull(selectedSourceIndex) ?: return
    val audioStreams = selectedSource.mediaStreams.filter { it.type == MediaStreamType.AUDIO && it.index != null }
    val subtitleStreams = selectedSource.mediaStreams.filter { it.type == MediaStreamType.SUBTITLE && it.index != null }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        DownloadRequest(
                            sourceId = selectedSource.id,
                            mode = selectedMode,
                            videoBitrate = if (selectedMode == DownloadMode.TRANSCODED) selectedBitrate else null,
                            audioStreamIndex = if (selectedMode == DownloadMode.TRANSCODED) selectedAudioStreamIndex else null,
                            subtitleStreamIndex = if (selectedMode == DownloadMode.TRANSCODED) selectedSubtitleStreamIndex else null,
                            subtitleDeliveryMethod =
                                if (selectedMode == DownloadMode.TRANSCODED && selectedSubtitleStreamIndex != null) {
                                    SubtitleDeliveryMethod.EMBED
                                } else {
                                    SubtitleDeliveryMethod.DROP
                                },
                        )
                    )
                },
            ) {
                Text("Start Download")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = { Text("Download Options") },
        text = {
            BeamScrollableDialogBody {
                Text(
                    "Choose the source, bitrate, and tracks for this download.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (sources.size > 1) {
                    BeamChoiceSection(title = "Video Version") {
                        sources.forEachIndexed { index, source ->
                            BeamChoiceRow(
                                selected = selectedSourceIndex == index,
                                title = source.name.ifBlank { "Source ${index + 1}" },
                                subtitle = source.size.takeIf { it > 0 }?.let(::formatDownloadFileSize),
                                onClick = { selectedSourceIndex = index },
                            )
                        }
                    }
                }
                BeamChoiceSection(title = "Download Mode") {
                    BeamChoiceRow(
                        selected = selectedMode == DownloadMode.ORIGINAL,
                        title = "Original",
                        subtitle = "Download the original file for best quality and fastest setup.",
                        onClick = { selectedMode = DownloadMode.ORIGINAL },
                    )
                    BeamChoiceRow(
                        selected = selectedMode == DownloadMode.TRANSCODED,
                        title = "Transcoded",
                        subtitle = "Smaller file with explicit audio and subtitle embedding.",
                        onClick = { selectedMode = DownloadMode.TRANSCODED },
                    )
                }
                if (selectedMode == DownloadMode.TRANSCODED) {
                    BeamChoiceSection(title = "Video Quality") {
                        DEFAULT_DOWNLOAD_BITRATES.forEach { (bitrate, label) ->
                            BeamChoiceRow(
                                selected = selectedBitrate == bitrate,
                                title = label,
                                onClick = { selectedBitrate = bitrate },
                            )
                        }
                    }
                    if (audioStreams.isNotEmpty()) {
                        BeamChoiceSection(title = "Audio Track") {
                            audioStreams.forEach { stream ->
                                BeamChoiceRow(
                                    selected = selectedAudioStreamIndex == stream.index,
                                    title = streamLabel(stream),
                                    onClick = { selectedAudioStreamIndex = stream.index },
                                )
                            }
                        }
                    }
                    BeamChoiceSection(title = "Subtitle Track") {
                        BeamChoiceRow(
                            selected = selectedSubtitleStreamIndex == null,
                            title = "None",
                            onClick = { selectedSubtitleStreamIndex = null },
                        )
                        subtitleStreams.forEach { stream ->
                            BeamChoiceRow(
                                selected = selectedSubtitleStreamIndex == stream.index,
                                title = streamLabel(stream),
                                onClick = { selectedSubtitleStreamIndex = stream.index },
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
internal fun BeamScrollableDialogBody(
    content: @Composable ColumnScope.() -> Unit,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.55f).coerceAtLeast(220.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
internal fun BeamChoiceSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    })
}

@Composable
internal fun BeamChoiceRow(
    selected: Boolean,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (selected) "●" else "○", style = MaterialTheme.typography.bodyLarge)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal val DEFAULT_DOWNLOAD_BITRATES =
    listOf(
        8_000_000 to "8 Mbps",
        5_000_000 to "5 Mbps",
        3_000_000 to "3 Mbps",
        2_000_000 to "2 Mbps",
        1_000_000 to "1 Mbps",
    )

@Composable
internal fun BeamBulkDownloadDialog(
    title: String,
    description: String,
    confirmLabel: String,
    onConfirm: (BulkDownloadSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedMode by remember { mutableStateOf(DownloadMode.ORIGINAL) }
    var selectedBitrate by remember { mutableStateOf(DEFAULT_DOWNLOAD_BITRATES.first().first) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        BulkDownloadSettings(
                            mode = selectedMode,
                            videoBitrate = if (selectedMode == DownloadMode.TRANSCODED) selectedBitrate else null,
                        )
                    )
                },
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        title = { Text(title) },
        text = {
            BeamScrollableDialogBody {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BeamChoiceSection(title = "Download Mode") {
                    BeamChoiceRow(
                        selected = selectedMode == DownloadMode.ORIGINAL,
                        title = "Original",
                        subtitle = "Download the original files for best quality.",
                        onClick = { selectedMode = DownloadMode.ORIGINAL },
                    )
                    BeamChoiceRow(
                        selected = selectedMode == DownloadMode.TRANSCODED,
                        title = "Transcoded",
                        subtitle = "Smaller files with embedded audio and subtitles.",
                        onClick = { selectedMode = DownloadMode.TRANSCODED },
                    )
                }
                if (selectedMode == DownloadMode.TRANSCODED) {
                    BeamChoiceSection(title = "Video Quality") {
                        DEFAULT_DOWNLOAD_BITRATES.forEach { (bitrate, label) ->
                            BeamChoiceRow(
                                selected = selectedBitrate == bitrate,
                                title = label,
                                onClick = { selectedBitrate = bitrate },
                            )
                        }
                    }
                }
            }
        },
    )
}

internal fun streamLabel(stream: SpatialFinMediaStream): String {
    val language = stream.language.ifBlank { "Unknown" }
    val details =
        listOfNotNull(
            stream.displayTitle?.takeIf { it.isNotBlank() },
            stream.codec.takeIf { it.isNotBlank() }?.uppercase(),
        )
    return listOf(language, *details.toTypedArray()).joinToString(" • ")
}

internal fun formatDownloadFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return "%.1f %s".format(size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
