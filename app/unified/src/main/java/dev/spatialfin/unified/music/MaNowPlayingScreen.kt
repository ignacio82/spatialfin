package dev.spatialfin.unified.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.data.musicassistant.repository.MaSession
import dev.jdtech.jellyfin.data.musicassistant.repository.MaSessionRepository
import dev.jdtech.jellyfin.data.musicassistant.repository.PlaybackPhase
import kotlinx.coroutines.delay

/**
 * Full-screen MA Now Playing.
 *
 * Two state sources:
 *  - **[MaSessionRepository.session]** for everything that changes via MA
 *    server events: track metadata, server-side elapsed time, player picker,
 *    playback phase.
 *  - **A local 1 Hz tick** that extrapolates the scrubber between
 *    `QUEUE_TIME_UPDATED` events (those fire ~once per second per active
 *    queue; without the tick the scrubber jumps in 1-second steps and looks
 *    laggy on a 60 Hz display).
 *
 * Transport buttons are stubbed in Phase 1 — they exist for visual layout but
 * the play/pause/skip RPCs land in Phase 3 alongside queue management. The
 * scrubber is also read-only for now (seek is a Queue.seek RPC, same phase).
 *
 * Designed for parity across Beam (regular screen), TV (full-screen route with
 * d-pad focus), and XR Home Space (regular screen) / Full Space (hosted in a
 * SpatialDialog). Form-factor hosts wrap this in their own back/navigation
 * chrome.
 */
@Composable
fun MaNowPlayingScreen(
    session: MaSessionRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onPickPlayer: (() -> Unit)? = null,
    onOpenSearch: (() -> Unit)? = null,
) {
    val state by session.session.collectAsStateWithLifecycle()
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            if (onOpenSearch != null) {
                IconButton(
                    onClick = onOpenSearch,
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Icon(Icons.Filled.Search, contentDescription = "Search Music Assistant")
                }
            }
            NowPlayingBody(
                state = state,
                onPickPlayer = onPickPlayer,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun NowPlayingBody(
    state: MaSession,
    modifier: Modifier = Modifier,
    onPickPlayer: (() -> Unit)? = null,
) {
    val track = state.nowPlaying
    if (track == null) {
        Column(
            modifier = modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Nothing is playing",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Tap a track in your library to start playback.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    Column(
        modifier = modifier
            .widthIn(max = 520.dp)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 8.dp,
        ) {
            if (track.artworkUrl.isNullOrBlank()) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(96.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                AsyncImage(
                    model = track.artworkUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)),
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = track.title ?: "Unknown track",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            val artistLine = track.artist
            if (!artistLine.isNullOrBlank()) {
                Text(
                    text = artistLine,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Scrubber(state = state)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IconButton(
                onClick = { /* TODO Phase 1 stretch: PlayerCommand.PREVIOUS */ },
                enabled = state.playbackPhase != PlaybackPhase.Preparing,
            ) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
            }
            FilledIconButton(
                onClick = { /* TODO Phase 1 stretch: PlayerCommand.PLAY_PAUSE */ },
                modifier = Modifier.size(64.dp),
                enabled = state.playbackPhase != PlaybackPhase.Preparing,
            ) {
                Icon(
                    imageVector = if (state.playbackPhase == PlaybackPhase.Playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.playbackPhase == PlaybackPhase.Playing) "Pause" else "Play",
                    modifier = Modifier.size(36.dp),
                )
            }
            IconButton(
                onClick = { /* TODO Phase 1 stretch: PlayerCommand.NEXT */ },
                enabled = state.playbackPhase != PlaybackPhase.Preparing,
            ) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp))
            }
        }

        // Player picker chip. Always render it (even with no selected player)
        // so the user can choose where to play before tapping a song.
        AssistChip(
            onClick = { onPickPlayer?.invoke() },
            enabled = onPickPlayer != null,
            label = {
                Text(
                    text = state.selectedPlayer
                        ?.let { "Playing on ${it.name}" }
                        ?: "Choose a player",
                )
            },
            colors = AssistChipDefaults.assistChipColors(),
        )
    }
}

/**
 * Read-only scrubber that extrapolates between `QUEUE_TIME_UPDATED` events.
 *
 * MA emits `QUEUE_TIME_UPDATED` at roughly 1 Hz for the active queue. If we
 * bound the bar directly to [MaSession.elapsedMs] it would visibly jump in
 * 1 s steps. Instead we anchor on `elapsedAsOfEpochMs` and add the local
 * wall-clock delta. The 250 ms tick is the cheapest update interval that
 * looks smooth without burning CPU.
 *
 * When the duration is unknown we fall back to an indeterminate strip — happy
 * for streams (`PlayerState.PLAYING` with no duration: radio, podcast).
 */
@Composable
private fun Scrubber(state: MaSession) {
    val duration = state.nowPlaying?.durationMs
    val anchorElapsed = state.elapsedMs
    val anchorAt = state.elapsedAsOfEpochMs
    var now by remember(anchorAt) { mutableLongStateOf(System.currentTimeMillis()) }

    // Tick only while we have something to extrapolate. Stopped / Preparing /
    // missing-duration content skips the tick to keep idle CPU near zero.
    val shouldTick = duration != null && anchorElapsed != null && anchorAt != null &&
        state.playbackPhase == PlaybackPhase.Playing
    LaunchedEffect(shouldTick, anchorAt) {
        if (!shouldTick) return@LaunchedEffect
        while (true) {
            delay(250)
            now = System.currentTimeMillis()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (duration == null || anchorElapsed == null) {
            // Indeterminate: live stream / unknown length / preparing.
            if (state.playbackPhase == PlaybackPhase.Preparing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            } else {
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = "—", style = MaterialTheme.typography.labelSmall)
                Text(text = "—", style = MaterialTheme.typography.labelSmall)
            }
            return@Column
        }

        val effective = if (shouldTick && anchorAt != null) {
            (anchorElapsed + (now - anchorAt)).coerceAtMost(duration)
        } else {
            anchorElapsed.coerceAtMost(duration)
        }
        val progress = if (duration > 0) effective.toFloat() / duration.toFloat() else 0f
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = formatDuration(effective), style = MaterialTheme.typography.labelSmall)
            Text(text = formatDuration(duration), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

@Suppress("UnusedPrivateMember")
@Composable
private fun PreparingSpinner() {
    CircularProgressIndicator()
    Spacer(Modifier.size(8.dp))
    Text("Preparing audio…", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
}
