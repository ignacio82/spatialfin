package dev.jdtech.jellyfin.player.xr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel

/**
 * Main playback control panel — the primary Surface the user looks at while
 * playing. Audio/subtitle/speed/quality live in [SecondaryControlsOrbiter]
 * so this surface stays focused on play/pause + seek + scale handles.
 *
 * Extracted from SpatialPlayerScreen.kt.
 */
@Composable
internal fun ControlPanelUI(
    viewModel: PlayerViewModel,
    player: Player,
    uiState: PlayerViewModel.UiState,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    isLocked: Boolean,
    spatialAudioAvailable: Boolean,
    onLockToggle: () -> Unit,
    onMoveCloser: () -> Unit,
    onMoveFurther: () -> Unit,
    onChaptersClick: () -> Unit,
    onBackClick: () -> Unit,
    resetAutoHide: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(48.dp),
        color = Color.Black.copy(alpha = 0.9f),
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.padding(60.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = onBackClick, modifier = Modifier.size(100.dp)) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_arrow_left),
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(64.dp),
                    )
                }
                Spacer(Modifier.width(32.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = uiState.currentItemTitle,
                        style = MaterialTheme.typography.displayMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when {
                            isLocked -> "Controls & Screen Locked"
                            spatialAudioAvailable -> "Spatial Playback \u2022 Spatial Audio"
                            else -> "Spatial Playback"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (spatialAudioAvailable && !isLocked)
                            Color(0xFF4FC3F7).copy(alpha = 0.8f)
                        else
                            Color.White.copy(alpha = 0.6f),
                    )
                }
                if (!isLocked) {
                    IconButton(
                        onClick = { onMoveCloser(); resetAutoHide() },
                        modifier = Modifier.size(100.dp),
                    ) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_plus),
                            contentDescription = "Bigger",
                            tint = Color.White,
                            modifier = Modifier.size(64.dp),
                        )
                    }
                    IconButton(
                        onClick = { onMoveFurther(); resetAutoHide() },
                        modifier = Modifier.size(100.dp),
                    ) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_minus_fat),
                            contentDescription = "Smaller",
                            tint = Color.White,
                            modifier = Modifier.size(64.dp),
                        )
                    }
                }
                IconButton(
                    onClick = { onLockToggle(); resetAutoHide() },
                    modifier = Modifier.size(100.dp),
                ) {
                    Icon(
                        painter = painterResource(
                            if (isLocked) CoreR.drawable.ic_lock else CoreR.drawable.ic_unlock,
                        ),
                        contentDescription =
                            if (isLocked) "Unlock controls and screen" else "Lock controls and screen",
                        tint = if (isLocked) Color.Red else Color.White,
                        modifier = Modifier.size(64.dp),
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            if (!isLocked) {
                ProgressSection(
                    uiState = uiState,
                    player = player,
                    currentPosition = currentPosition,
                    duration = duration,
                    resetAutoHide = resetAutoHide,
                )
            }

            Spacer(Modifier.height(48.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isLocked) {
                    IconButton(
                        onClick = { player.seekBack(); resetAutoHide() },
                        modifier = Modifier.size(140.dp),
                    ) {
                        Icon(
                            painterResource(CoreR.drawable.ic_rewind),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(80.dp),
                        )
                    }
                    Spacer(Modifier.width(64.dp))
                }

                FilledIconButton(
                    onClick = {
                        if (isPlaying) player.pause() else player.play()
                        resetAutoHide()
                    },
                    modifier = Modifier.size(160.dp),
                ) {
                    Icon(
                        painter = painterResource(
                            if (isPlaying) CoreR.drawable.ic_pause else CoreR.drawable.ic_play,
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(96.dp),
                    )
                }

                if (!isLocked) {
                    Spacer(Modifier.width(64.dp))
                    TextButton(
                        onClick = { onChaptersClick() },
                        modifier = Modifier.height(112.dp),
                    ) {
                        Text(
                            "Chapters",
                            style = MaterialTheme.typography.displaySmall,
                            color = Color.White,
                        )
                    }
                    Spacer(Modifier.width(32.dp))
                    IconButton(
                        onClick = { player.seekForward(); resetAutoHide() },
                        modifier = Modifier.size(140.dp),
                    ) {
                        Icon(
                            painterResource(CoreR.drawable.ic_fast_forward),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(80.dp),
                        )
                    }
                }
            }
        }
    }
}
