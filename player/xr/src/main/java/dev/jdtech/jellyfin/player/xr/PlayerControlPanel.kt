package dev.jdtech.jellyfin.player.xr

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.player.core.splitav.SplitAvTheaterBridge
import dev.jdtech.jellyfin.player.core.splitav.TheaterClientEvent
import dev.jdtech.jellyfin.player.core.splitav.TheaterSessionSnapshot
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel

/**
 * Bottom playback cluster — the glass scrubber-and-transport panel that floats
 * below the cinema screen. Screen controls (size / passthrough / lock) live in
 * [StageControlsOrbiter] (top), track options in [TrackOptionsOrbiter] (left),
 * and cast / SyncPlay / voice in [SessionOrbiter] (right), so this surface
 * stays focused on title + seek + play/pause (cinema principle: screen first).
 *
 * Extracted from SpatialPlayerScreen.kt.
 */
@Composable
internal fun ControlPanelUI(
    player: Player,
    uiState: PlayerViewModel.UiState,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    isLocked: Boolean,
    spatialAudioAvailable: Boolean,
    onControlInputActiveChange: (Boolean) -> Unit,
    onChaptersClick: () -> Unit,
    onBackClick: () -> Unit,
    resetAutoHide: () -> Unit,
    showChaptersButton: Boolean = true,
    theaterSnapshot: TheaterSessionSnapshot? = null,
    onFoldBackToLocal: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(48.dp),
        color = Color.Black.copy(alpha = 0.9f),
        tonalElevation = 4.dp,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(onControlInputActiveChange) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    onControlInputActiveChange(true)
                    try {
                        waitForUpOrCancellation(pass = PointerEventPass.Initial)
                    } finally {
                        onControlInputActiveChange(false)
                    }
                }
            },
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
                    uiState.currentPlaybackInfoLabel?.let { label ->
                        Spacer(Modifier.height(8.dp))
                        dev.jdtech.jellyfin.core.presentation.components.MetadataPill {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.9f),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            val theaterBanner = theaterSnapshot?.banner
            if (theaterBanner != null && !theaterSnapshot.active && !isLocked) {
                Spacer(Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xFFB71C1C).copy(alpha = 0.85f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = theaterBanner.message,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                    )
                }
            }

            if (theaterSnapshot != null && theaterSnapshot.active && !isLocked) {
                Spacer(Modifier.height(16.dp))
                val decodeLabel = theaterSnapshot.telemetry.activeDecodeChain
                    ?: theaterSnapshot.serverName
                    ?: "Theater Audio"
                val nextUpmix = if (theaterSnapshot.upmixMode == "dts_height_upmix") {
                    "native"
                } else {
                    "dts_height_upmix"
                }
                val upmixLabel = if (theaterSnapshot.upmixMode == "dts_height_upmix") {
                    "Upmix: Height"
                } else {
                    "Upmix: Native"
                }
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color.White.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = decodeLabel,
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color(0xFF4FC3F7),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                SplitAvTheaterBridge.emitClientEvent(
                                    TheaterClientEvent.SetEqBypass(!theaterSnapshot.eqBypass),
                                )
                                resetAutoHide()
                            },
                        ) {
                            Text(
                                text = if (theaterSnapshot.eqBypass) "EQ: Bypass" else "EQ: On",
                                style = MaterialTheme.typography.headlineSmall,
                                color = if (theaterSnapshot.eqBypass) Color(0xFFFFB74D) else Color.White,
                            )
                        }
                        TextButton(
                            onClick = {
                                SplitAvTheaterBridge.emitClientEvent(
                                    TheaterClientEvent.SetUpmixMode(nextUpmix),
                                )
                                resetAutoHide()
                            },
                        ) {
                            Text(
                                text = upmixLabel,
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.White,
                            )
                        }
                        TextButton(
                            onClick = {
                                SplitAvTheaterBridge.emitClientEvent(
                                    TheaterClientEvent.SetMuted(!theaterSnapshot.muted),
                                )
                                resetAutoHide()
                            },
                        ) {
                            Text(
                                text = if (theaterSnapshot.muted) {
                                    "Muted"
                                } else {
                                    "Vol ${(theaterSnapshot.masterVolume * 100).toInt()}%"
                                },
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.White,
                            )
                        }
                        TextButton(
                            onClick = {
                                SplitAvTheaterBridge.emitClientEvent(
                                    TheaterClientEvent.SetLcrScreenLockPref(!theaterSnapshot.lcrScreenLockEnabled),
                                )
                                resetAutoHide()
                            },
                        ) {
                            Text(
                                text = if (theaterSnapshot.lcrScreenLockEnabled) {
                                    "Screen Locked to Room LCR"
                                } else {
                                    "Free Screen Placement"
                                },
                                style = MaterialTheme.typography.headlineSmall,
                                color = if (theaterSnapshot.lcrScreenLockEnabled) {
                                    Color(0xFF4FC3F7)
                                } else {
                                    Color(0xFFFFE082)
                                },
                            )
                        }
                        if (onFoldBackToLocal != null) {
                            TextButton(
                                onClick = {
                                    onFoldBackToLocal()
                                    resetAutoHide()
                                },
                            ) {
                                Text(
                                    text = "Headset Audio",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = Color(0xFF81C784),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            ProgressSection(
                uiState = uiState,
                player = player,
                currentPosition = currentPosition,
                duration = duration,
                resetAutoHide = resetAutoHide,
                isLocked = isLocked,
            )

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
                        if (isPlaying) {
                            timber.log.Timber.tag("SplitAvPauseTrace").w("pause source=PlayerControlPanel button")
                            player.pause()
                        } else {
                            if (player.playbackState == androidx.media3.common.Player.STATE_IDLE &&
                                player.mediaItemCount > 0
                            ) {
                                player.prepare()
                            }
                            player.play()
                        }
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
                    if (showChaptersButton) {
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
                    }
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
