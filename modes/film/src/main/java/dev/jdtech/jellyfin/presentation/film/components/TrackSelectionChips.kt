package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.domain.DetailHeroMetadata
import dev.jdtech.jellyfin.models.MediaStreamLanguage
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMediaStream
import dev.spatialfin.presentation.theme.spacings
import java.util.Locale
import org.jellyfin.sdk.model.api.MediaStreamType

/**
 * Tappable "what will play" chips for the audio and subtitle track, plus the
 * two pickers behind them.
 *
 * The Beam detail screen has carried these since the audio-language work; this
 * is the same affordance for the XR movie/episode screens, which previously
 * showed only the non-interactive codec badges of [VideoMetadataBar] and gave
 * no way to choose a track before pressing Play.
 *
 * [hero] must come from `detailHeroMetadata` with the viewer's language
 * preferences applied — the chip is a promise about what Play will do, so it
 * has to be the same resolution the player will reach.
 */
@Composable
fun TrackSelectionChips(
    item: SpatialFinItem,
    hero: DetailHeroMetadata,
    onAudioStreamSelected: (Int?) -> Unit,
    onSubtitleStreamSelected: (streamIndex: Int?, disabled: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAudioPicker by rememberSaveable { mutableStateOf(false) }
    var showSubtitlePicker by rememberSaveable { mutableStateOf(false) }

    val streams = remember(item) { item.sources.firstOrNull()?.mediaStreams.orEmpty() }
    val audioStreams = remember(streams) { streams.filter { it.type == MediaStreamType.AUDIO } }
    val subtitleStreams = remember(streams) { streams.filter { it.type == MediaStreamType.SUBTITLE } }

    // Nothing to choose between is not worth a chip.
    if (audioStreams.isEmpty() && subtitleStreams.isEmpty()) return

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
    ) {
        if (audioStreams.isNotEmpty()) {
            TrackChip(
                label = hero.audio ?: "Audio",
                iconRes = CoreR.drawable.ic_speaker,
                enabled = audioStreams.size > 1,
                onClick = { showAudioPicker = true },
            )
        }
        if (subtitleStreams.isNotEmpty()) {
            TrackChip(
                label = hero.subtitle ?: "Off",
                iconRes = CoreR.drawable.ic_closed_caption,
                enabled = true,
                onClick = { showSubtitlePicker = true },
            )
        }
    }

    if (showAudioPicker) {
        TrackPickerDialog(
            title = "Audio track",
            onDismiss = { showAudioPicker = false },
        ) {
            audioStreams.forEach { stream ->
                TrackPickerRow(
                    label = audioStreamLabel(stream),
                    detail = audioStreamDetail(stream),
                    selected = stream.index == hero.audioStreamIndex,
                    onClick = {
                        onAudioStreamSelected(stream.index)
                        showAudioPicker = false
                    },
                )
            }
        }
    }

    if (showSubtitlePicker) {
        TrackPickerDialog(
            title = "Subtitle track",
            onDismiss = { showSubtitlePicker = false },
        ) {
            TrackPickerRow(
                label = "Off",
                detail = null,
                selected = hero.subtitleStreamIndex == null,
                onClick = {
                    onSubtitleStreamSelected(null, true)
                    showSubtitlePicker = false
                },
            )
            subtitleStreams.forEach { stream ->
                TrackPickerRow(
                    label = subtitleStreamLabel(stream),
                    detail = stream.codec.takeIf { it.isNotBlank() }?.uppercase(Locale.US),
                    selected = stream.index == hero.subtitleStreamIndex,
                    onClick = {
                        onSubtitleStreamSelected(stream.index, false)
                        showSubtitlePicker = false
                    },
                )
            }
        }
    }
}

@Composable
private fun TrackChip(
    label: String,
    iconRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.width(AssistChipDefaults.IconSize),
            )
        },
    )
}

@Composable
private fun TrackPickerDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(0.9f).heightIn(max = 520.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 8.dp,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(MaterialTheme.spacings.medium))
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                ) {
                    content()
                }
                Spacer(Modifier.height(MaterialTheme.spacings.small))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun TrackPickerRow(
    label: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(MaterialTheme.spacings.small))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!detail.isNullOrBlank() && detail != label) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Jellyfin's own `displayTitle` is already human-readable and survives a missing language tag. */
private fun audioStreamLabel(stream: SpatialFinMediaStream): String {
    stream.displayTitle?.takeIf { it.isNotBlank() }?.let { return it }
    val language = MediaStreamLanguage.displayCode(stream) ?: "Unknown"
    return listOfNotNull(language, stream.title.takeIf { it.isNotBlank() }).joinToString(" - ")
}

private fun audioStreamDetail(stream: SpatialFinMediaStream): String? =
    listOfNotNull(
        stream.codec.takeIf { it.isNotBlank() }?.uppercase(Locale.US),
        stream.channelLayout?.takeIf { it.isNotBlank() },
    ).joinToString(" · ").takeIf { it.isNotBlank() }

private fun subtitleStreamLabel(stream: SpatialFinMediaStream): String {
    stream.displayTitle?.takeIf { it.isNotBlank() }?.let { return it }
    val language = MediaStreamLanguage.displayCode(stream) ?: "Unknown"
    return listOfNotNull(language, stream.title.takeIf { it.isNotBlank() }).joinToString(" - ")
}
