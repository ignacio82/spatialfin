package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.DownloadMode
import dev.jdtech.jellyfin.models.DownloadRequest
import dev.jdtech.jellyfin.models.SpatialFinMediaStream
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.presentation.components.BaseDialog
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod

@Composable
fun DownloadOptionsDialog(
    sources: List<SpatialFinSource>,
    initialRequest: DownloadRequest,
    onConfirm: (DownloadRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedSourceIndex by remember {
        mutableIntStateOf(sources.indexOfFirst { it.id == initialRequest.sourceId }.coerceAtLeast(0))
    }
    var selectedMode by remember { mutableStateOf(initialRequest.mode) }
    var selectedBitrate by remember { mutableStateOf(initialRequest.videoBitrate ?: DEFAULT_BITRATES.first().first) }
    var selectedAudioStreamIndex by remember { mutableStateOf(initialRequest.audioStreamIndex) }
    var selectedSubtitleStreamIndex by remember { mutableStateOf(initialRequest.subtitleStreamIndex) }

    val selectedSource = sources.getOrNull(selectedSourceIndex) ?: return
    val audioStreams = selectedSource.mediaStreams.filter { it.type == MediaStreamType.AUDIO && it.index != null }
    val subtitleStreams = selectedSource.mediaStreams.filter { it.type == MediaStreamType.SUBTITLE && it.index != null }

    BaseDialog(
        title = stringResource(CoreR.string.download_options_title),
        onDismiss = onDismiss,
        negativeButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.cancel)) }
        },
        positiveButton = {
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
                }
            ) { Text(stringResource(CoreR.string.download_button_description)) }
        },
    ) {
        Column(
            modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(CoreR.string.download_options_location_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (sources.size > 1) {
                OptionSection(title = stringResource(CoreR.string.select_video_version_title)) {
                    sources.forEachIndexed { index, source ->
                        OptionRow(
                            selected = selectedSourceIndex == index,
                            title = source.name,
                            subtitle = source.size.takeIf { it > 0 }?.let(::formatDownloadFileSize),
                            onClick = { selectedSourceIndex = index },
                        )
                    }
                }
            }
            OptionSection(title = stringResource(CoreR.string.download_mode_title)) {
                OptionRow(
                    selected = selectedMode == DownloadMode.ORIGINAL,
                    title = stringResource(CoreR.string.download_mode_original),
                    subtitle = stringResource(CoreR.string.download_mode_original_hint),
                    onClick = { selectedMode = DownloadMode.ORIGINAL },
                )
                OptionRow(
                    selected = selectedMode == DownloadMode.TRANSCODED,
                    title = stringResource(CoreR.string.download_mode_transcoded),
                    subtitle = stringResource(CoreR.string.download_mode_transcoded_hint),
                    onClick = { selectedMode = DownloadMode.TRANSCODED },
                )
            }
            if (selectedMode == DownloadMode.TRANSCODED) {
                OptionSection(title = stringResource(CoreR.string.download_quality_title)) {
                    DEFAULT_BITRATES.forEach { (bitrate, label) ->
                        OptionRow(
                            selected = selectedBitrate == bitrate,
                            title = label,
                            onClick = { selectedBitrate = bitrate },
                        )
                    }
                }
                if (audioStreams.isNotEmpty()) {
                    OptionSection(title = stringResource(CoreR.string.select_audio_track)) {
                        audioStreams.forEach { stream ->
                            OptionRow(
                                selected = selectedAudioStreamIndex == stream.index,
                                title = streamLabel(stream),
                                onClick = { selectedAudioStreamIndex = stream.index },
                            )
                        }
                    }
                }
                OptionSection(title = stringResource(CoreR.string.select_subtitle_track)) {
                    OptionRow(
                        selected = selectedSubtitleStreamIndex == null,
                        title = stringResource(CoreR.string.none),
                        onClick = { selectedSubtitleStreamIndex = null },
                    )
                    subtitleStreams.forEach { stream ->
                        OptionRow(
                            selected = selectedSubtitleStreamIndex == stream.index,
                            title = streamLabel(stream),
                            onClick = { selectedSubtitleStreamIndex = stream.index },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        content()
    }
}

@Composable
private fun OptionRow(
    selected: Boolean,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
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

private fun streamLabel(stream: SpatialFinMediaStream): String {
    val language = stream.language.ifBlank { "Unknown" }
    val details =
        listOfNotNull(
            stream.displayTitle?.takeIf { it.isNotBlank() },
            stream.codec.takeIf { it.isNotBlank() }?.uppercase(),
        )
    return listOf(language, *details.toTypedArray()).joinToString(" • ")
}

private val DEFAULT_BITRATES =
    listOf(
        8_000_000 to "8 Mbps",
        5_000_000 to "5 Mbps",
        3_000_000 to "3 Mbps",
        2_000_000 to "2 Mbps",
        1_000_000 to "1 Mbps",
    )

private fun formatDownloadFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return "%.1f %s".format(size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
