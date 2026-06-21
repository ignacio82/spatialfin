package dev.jdtech.jellyfin.player.xr

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.media3.common.Tracks
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter
import dev.jdtech.jellyfin.player.local.domain.getTrackNames
import dev.jdtech.jellyfin.player.local.R as LocalR
import dev.jdtech.jellyfin.settings.presentation.enums.QualityOption

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
    var trackGroups by remember(player, trackType) {
        mutableStateOf(player.currentTracks.groups.filter { it.type == trackType && (trackType == C.TRACK_TYPE_TEXT || it.isSupported) })
    }
    DisposableEffect(player, trackType) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                trackGroups = tracks.groups.filter { it.type == trackType && (trackType == C.TRACK_TYPE_TEXT || it.isSupported) }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
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
    val rowIcon = if (trackType == C.TRACK_TYPE_TEXT) CoreR.drawable.ic_closed_caption else CoreR.drawable.ic_speaker
    val sheetSubtitle = if (trackType == C.TRACK_TYPE_TEXT) "Subtitle track" else "Audio track"

    XrGlassSheet(
        title = title,
        subtitle = sheetSubtitle,
        onDismiss = onDismiss,
        width = 600.dp,
    ) {
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .height(400.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            XrOptionRow(
                title = stringResource(LocalR.string.none),
                icon = rowIcon,
                selected = noneSelected,
                onClick = { onTrackSelected(-1); onDismiss() },
            )
            trackNames.forEachIndexed { index, name ->
                XrOptionRow(
                    title = name,
                    icon = rowIcon,
                    selected = index == effectiveMediaSelected,
                    onClick = { onTrackSelected(index); onDismiss() },
                )
            }
            extraTrackNames.forEachIndexed { index, name ->
                XrOptionRow(
                    title = name,
                    icon = rowIcon,
                    selected = index == extraSelectedIndex,
                    onClick = { onExtraTrackSelected(index); onDismiss() },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (trackType == C.TRACK_TYPE_TEXT && onSearchSubtitles != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                TextButton(onClick = {
                    onDismiss()
                    onSearchSubtitles()
                }) {
                    Text("SEARCH SUBTITLES", style = MaterialTheme.typography.labelLarge, color = Color(0xFF4FC3F7))
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
    XrGlassSheet(
        title = stringResource(LocalR.string.select_playback_speed),
        subtitle = "Playback speed",
        onDismiss = onDismiss,
        width = 440.dp,
    ) {
        Spacer(Modifier.height(8.dp))
        Column {
            speeds.forEach { speed ->
                XrOptionRow(
                    title = if (speed == 1f) "Normal (1x)" else "${speed}x",
                    icon = CoreR.drawable.ic_gauge,
                    selected = currentSpeed == speed,
                    onClick = { onSpeedSelected(speed); onDismiss() },
                )
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
    val currentOption = QualityOption.fromBps(currentMaxBitrate)
    XrGlassSheet(
        title = "Quality",
        subtitle = "Streaming resolution & bitrate",
        onDismiss = onDismiss,
        width = 480.dp,
    ) {
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .heightIn(max = 440.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            QualityOption.entries.forEach { option ->
                XrOptionRow(
                    title = stringResource(option.labelRes),
                    icon = CoreR.drawable.ic_gauge,
                    selected = currentOption == option,
                    onClick = { onQualitySelected(option.bps); onDismiss() },
                )
            }
        }
    }
}
