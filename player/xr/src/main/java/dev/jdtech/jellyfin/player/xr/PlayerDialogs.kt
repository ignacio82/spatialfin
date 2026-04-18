package dev.jdtech.jellyfin.player.xr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter
import dev.jdtech.jellyfin.player.local.domain.getTrackNames
import dev.jdtech.jellyfin.player.local.R as LocalR

/**
 * Modal dialog content Composables for chapters, track selection, speed and
 * quality. Extracted from SpatialPlayerScreen.kt — each is pure presentation
 * that takes state in and emits callbacks out.
 */

@Composable
internal fun ChaptersDialogContent(
    chapters: List<PlayerChapter>,
    currentPosition: Long,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSelectChapter: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentChapterIndex = chapters.indexOfLast { it.startPosition <= currentPosition }.coerceAtLeast(0)

    Surface(
        modifier = Modifier.width(760.dp).heightIn(max = 880.dp),
        shape = RoundedCornerShape(36.dp),
        color = Color(0xFF101114),
        tonalElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Chapters", style = MaterialTheme.typography.displaySmall, color = Color.White)
            if (chapters.isEmpty()) {
                Text(
                    "No chapter markers for this item.",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White.copy(alpha = 0.75f),
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onPreviousChapter, modifier = Modifier.height(64.dp)) {
                        Text("Previous Chapter", style = MaterialTheme.typography.titleMedium)
                    }
                    Button(onClick = onNextChapter, modifier = Modifier.height(64.dp)) {
                        Text("Next Chapter", style = MaterialTheme.typography.titleMedium)
                    }
                }
                Column(
                    modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    chapters.forEachIndexed { index, chapter ->
                        Surface(
                            onClick = { onSelectChapter(index) },
                            shape = RoundedCornerShape(24.dp),
                            color =
                                if (index == currentChapterIndex) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                } else {
                                    Color.White.copy(alpha = 0.06f)
                                },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        chapter.name ?: "Chapter ${index + 1}",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = Color.White,
                                    )
                                    Text(
                                        formatTime(chapter.startPosition),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color.White.copy(alpha = 0.7f),
                                    )
                                }
                                if (index == currentChapterIndex) {
                                    Text(
                                        "Current",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color(0xFF4FC3F7),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}

@Composable
internal fun TrackSelectionDialogContent(
    title: String,
    player: Player,
    trackType: @C.TrackType Int,
    onTrackSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
    onSearchSubtitles: (() -> Unit)? = null,
    visualSubtitlesEnabled: Boolean = true,
    /** Extra out-of-band tracks displayed after the Media3 tracks (e.g. streaming ASS). */
    extraTrackNames: List<String> = emptyList(),
    /** Index within [extraTrackNames] of the currently-active extra track, or -1 if none. */
    extraSelectedIndex: Int = -1,
    /** Invoked when the user picks one of [extraTrackNames]. */
    onExtraTrackSelected: (Int) -> Unit = {},
) {
    val trackGroups = player.currentTracks.groups.filter { it.type == trackType && it.isSupported }
    val trackNames = trackGroups.getTrackNames()
    val mediaSelectedIndex = if (trackType == C.TRACK_TYPE_TEXT && !visualSubtitlesEnabled) {
        -1
    } else {
        trackGroups.indexOfFirst { it.isSelected }
    }
    // Only one selection across both lists is active at a time. If an extra track is
    // selected, suppress Media3's own highlight so the radio buttons are mutually exclusive.
    val effectiveMediaSelected = if (extraSelectedIndex >= 0) -1 else mediaSelectedIndex
    val noneSelected = effectiveMediaSelected == -1 && extraSelectedIndex == -1

    Surface(
        modifier = Modifier
            .width(600.dp)
            .heightIn(max = 560.dp),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 12.dp,
    ) {
        Column(modifier = Modifier.padding(32.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            Column(
                modifier = Modifier
                    .height(400.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onTrackSelected(-1); onDismiss() }
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = noneSelected,
                        onClick = { onTrackSelected(-1); onDismiss() },
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(LocalR.string.none), style = MaterialTheme.typography.titleLarge)
                }
                trackNames.forEachIndexed { index, name ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTrackSelected(index); onDismiss() }
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = index == effectiveMediaSelected,
                            onClick = { onTrackSelected(index); onDismiss() },
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
                extraTrackNames.forEachIndexed { index, name ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onExtraTrackSelected(index); onDismiss() }
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = index == extraSelectedIndex,
                            onClick = { onExtraTrackSelected(index); onDismiss() },
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (trackType == C.TRACK_TYPE_TEXT && onSearchSubtitles != null) {
                    TextButton(onClick = {
                        onDismiss()
                        onSearchSubtitles()
                    }) {
                        Text("SEARCH SUBTITLES", style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                TextButton(onClick = onDismiss) {
                    Text("CLOSE", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
internal fun SpeedDialogContent(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    Surface(
        modifier = Modifier.width(400.dp).heightIn(max = 560.dp),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 12.dp,
    ) {
        Column(modifier = Modifier.padding(32.dp)) {
            Text(
                stringResource(LocalR.string.select_playback_speed),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(24.dp))
            Column {
                speeds.forEach { speed ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSpeedSelected(speed); onDismiss() }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = currentSpeed == speed,
                            onClick = { onSpeedSelected(speed); onDismiss() },
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.width(16.dp))
                        Text("${speed}x", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("CLOSE", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
internal fun QualityDialogContent(
    currentMaxBitrate: Long,
    onQualitySelected: (Long) -> Unit,
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
    Surface(
        modifier = Modifier.width(400.dp).heightIn(max = 560.dp),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 12.dp,
    ) {
        Column(modifier = Modifier.padding(32.dp)) {
            Text(
                "Select Playback Quality",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(24.dp))
            Column(
                modifier = Modifier
                    .height(400.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                bitrates.forEach { (bitrate, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onQualitySelected(bitrate); onDismiss() }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = currentMaxBitrate == bitrate,
                            onClick = { onQualitySelected(bitrate); onDismiss() },
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(label, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("CLOSE", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
