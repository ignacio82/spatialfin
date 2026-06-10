package dev.spatialfin.unified.music

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.data.musicassistant.repository.MaSession
import dev.jdtech.jellyfin.data.musicassistant.repository.MaSessionRepository
import dev.jdtech.jellyfin.data.musicassistant.repository.PlaybackPhase

/**
 * Always-visible MA playback bar. Hides itself when there's no track *and*
 * no pending optimistic play, so it doesn't take vertical space on a fresh
 * install. Three states matter:
 *
 *  - **Preparing** — user just tapped play, MA hasn't echoed yet. Bar shows
 *    the optimistic title + an indeterminate progress strip ("Preparing
 *    audio…"). This is the entire point of [MaSessionRepository]'s
 *    optimistic hint — without it the user sees nothing for 5-15 s on cold
 *    play.
 *  - **Playing / Paused** — bar shows live title, scrubber, play/pause/skip.
 *  - **Idle / Stopped (track present)** — same chrome, no scrubber animation.
 *
 * Pure UI. Consumes [MaSessionRepository.session] and exposes only
 * tap-to-expand. Transport actions land in the Now Playing screen once that's
 * wired up; this surface intentionally stays minimal so it's the same on
 * Beam, XR, and TV.
 */
@Composable
fun MaMiniPlayer(
    session: MaSessionRepository,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by session.session.collectAsStateWithLifecycle()
    val visible = state.nowPlaying != null || state.pendingPlayUri != null

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        MaMiniPlayerContent(
            state = state,
            onExpand = onExpand,
            modifier = modifier,
        )
    }
}

@Composable
private fun MaMiniPlayerContent(
    state: MaSession,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = state.nowPlaying ?: return
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onExpand),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 8.dp,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column {
            // First-cast progress strip. Surfaces the "MA is still loading the
            // queue" window the user otherwise has no signal for.
            if (state.playbackPhase == PlaybackPhase.Preparing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiniArtwork(artworkUrl = track.artworkUrl)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title ?: "Unknown track",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val selectedPlayerName = state.selectedPlayer?.name
                    val subtitle = when {
                        state.playbackPhase == PlaybackPhase.Preparing -> "Preparing audio…"
                        !track.artist.isNullOrBlank() -> track.artist
                        selectedPlayerName != null -> "On $selectedPlayerName"
                        else -> null
                    }
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // Transport buttons. Disabled during Preparing because MA
                // can't react until the queue is loaded.
                val dispatcher = dev.spatialfin.unified.LocalMaPlayDispatcher.current
                val transportEnabled = dispatcher != null && state.playbackPhase != PlaybackPhase.Preparing
                IconButton(
                    onClick = { dispatcher?.playPause() },
                    enabled = transportEnabled,
                ) {
                    Icon(
                        imageVector = if (state.playbackPhase == PlaybackPhase.Playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.playbackPhase == PlaybackPhase.Playing) "Pause" else "Play",
                    )
                }
                IconButton(
                    onClick = { dispatcher?.next() },
                    enabled = transportEnabled,
                ) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                }
                // Stop — dismisses the bar entirely (distinct from Pause).
                IconButton(
                    onClick = { dispatcher?.stop() },
                    enabled = dispatcher != null,
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop")
                }
            }
        }
    }
}

@Composable
private fun MiniArtwork(artworkUrl: String?) {
    val shape = RoundedCornerShape(8.dp)
    if (artworkUrl.isNullOrBlank()) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    } else {
        AsyncImage(
            model = artworkUrl,
            contentDescription = null,
            modifier = Modifier.size(44.dp).clip(shape),
        )
    }
}

/** Padding helper for callers that need to reserve space for the mini-player. */
val MaMiniPlayerVerticalPadding: PaddingValues = PaddingValues(vertical = 8.dp)
