package dev.jdtech.jellyfin.player.xr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import dev.jdtech.jellyfin.player.xr.voice.VoiceState
import java.util.UUID

/**
 * Floating glass control clusters that orbit the cinema screen, plus the
 * SyncPlay group-management dialog.
 *
 * The player chrome follows the XR design system's "one job per orbiter"
 * model so nothing covers the picture and the user's mental map is stable:
 *   • [StageControlsOrbiter] (top)   — the screen itself: size, passthrough, lock.
 *   • [TrackOptionsOrbiter]  (left)  — what you hear/read: subtitles, audio, quality, speed.
 *   • [SessionOrbiter]       (right) — where it plays / who with: cast, SyncPlay, cast & crew, voice.
 * Transport + scrubber live in the bottom glass panel ([ControlPanelUI]).
 *
 * All are pure presentation with callbacks — the parent screen owns state.
 */

internal val OrbiterAccent = Color(0xFF4FC3F7)
private val OrbiterGlass = Color.Black.copy(alpha = 0.9f)

/** A 100×100 dp glass-orbiter icon button matching the player control sizing. */
@Composable
private fun OrbiterIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = Color.White,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(100.dp)) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.4f),
            modifier = Modifier.size(64.dp),
        )
    }
}

/**
 * Top orbiter — controls for the cinema screen itself. Kept distinct from the
 * playback transport so "screen controls up top, playback controls down below".
 * The lock button is always present (even while locked) so the screen can be
 * unlocked again; size/passthrough collapse away while locked.
 */
@Composable
internal fun StageControlsOrbiter(
    isLocked: Boolean,
    sizeLabel: String,
    smallerEnabled: Boolean,
    biggerEnabled: Boolean,
    onSmaller: () -> Unit,
    onResetSize: () -> Unit,
    onBigger: () -> Unit,
    passthroughEnabled: Boolean = false,
    onPassthroughToggle: (() -> Unit)? = null,
    onLockToggle: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(40.dp),
        color = OrbiterGlass,
        tonalElevation = 4.dp,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!isLocked) {
                OrbiterIconButton(
                    iconRes = CoreR.drawable.ic_minus_fat,
                    contentDescription = "Smaller screen",
                    enabled = smallerEnabled,
                    onClick = onSmaller,
                )
                TextButton(
                    onClick = onResetSize,
                    modifier = Modifier.heightIn(min = 100.dp).widthIn(min = 140.dp),
                ) {
                    Text(
                        text = sizeLabel,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                    )
                }
                OrbiterIconButton(
                    iconRes = CoreR.drawable.ic_plus,
                    contentDescription = "Bigger screen",
                    enabled = biggerEnabled,
                    onClick = onBigger,
                )
                if (onPassthroughToggle != null) {
                    OrbiterIconButton(
                        iconRes = if (passthroughEnabled) CoreR.drawable.ic_eye else CoreR.drawable.ic_eye_off,
                        contentDescription = if (passthroughEnabled) "Passthrough on" else "Theater (passthrough off)",
                        tint = if (passthroughEnabled) Color.White else OrbiterAccent,
                        onClick = onPassthroughToggle,
                    )
                }
            }
            if (onLockToggle != null) {
                OrbiterIconButton(
                    iconRes = if (isLocked) CoreR.drawable.ic_lock else CoreR.drawable.ic_unlock,
                    contentDescription = if (isLocked) "Unlock controls and screen" else "Lock controls and screen",
                    tint = if (isLocked) Color.Red else Color.White,
                    onClick = onLockToggle,
                )
            }
        }
    }
}

/**
 * Left orbiter — track / playback options. Subtitles, audio, quality, and
 * (when available) speed. Speed is omitted for receivers that can't retime.
 */
@Composable
internal fun TrackOptionsOrbiter(
    onSubtitleClick: () -> Unit,
    onAudioClick: () -> Unit,
    onQualityClick: () -> Unit,
    onSpeedClick: (() -> Unit)? = null,
    onProjectionClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(40.dp),
        color = OrbiterGlass,
        tonalElevation = 4.dp,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OrbiterIconButton(CoreR.drawable.ic_closed_caption, "Subtitle track", onSubtitleClick)
            OrbiterIconButton(CoreR.drawable.ic_speaker, "Audio track", onAudioClick)
            OrbiterIconButton(CoreR.drawable.ic_sparkles, "Playback quality", onQualityClick)
            if (onSpeedClick != null) {
                OrbiterIconButton(CoreR.drawable.ic_gauge, "Playback speed", onSpeedClick)
            }
            if (onProjectionClick != null) {
                OrbiterIconButton(CoreR.drawable.ic_globe, "Projection (180/360)", onProjectionClick)
            }
        }
    }
}

/**
 * Right orbiter — session / sharing. Cast (+ split audio), SyncPlay, cast &
 * crew, voice assistant. Active states tint cyan; voice tracks its own state.
 */
@Composable
internal fun SessionOrbiter(
    onCastClick: () -> Unit,
    castActive: Boolean,
    onSyncPlayClick: () -> Unit,
    syncPlayActive: Boolean,
    onCastCrewClick: () -> Unit,
    onVoiceClick: () -> Unit,
    voiceControlEnabled: Boolean,
    voiceAvailable: Boolean,
    voiceState: VoiceState,
    showSyncPlayButton: Boolean = true,
    showCastCrewButton: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(40.dp),
        color = OrbiterGlass,
        tonalElevation = 4.dp,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OrbiterIconButton(
                iconRes = CoreR.drawable.ic_cast,
                contentDescription = "Cast & audio output",
                tint = if (castActive) OrbiterAccent else Color.White,
                onClick = onCastClick,
            )
            if (showSyncPlayButton) {
                OrbiterIconButton(
                    iconRes = CoreR.drawable.ic_tv,
                    contentDescription = "SyncPlay",
                    tint = if (syncPlayActive) OrbiterAccent else Color.White,
                    onClick = onSyncPlayClick,
                )
            }
            if (showCastCrewButton) {
                OrbiterIconButton(CoreR.drawable.ic_user, "Cast & crew", onCastCrewClick)
            }
            if (voiceControlEnabled) {
                OrbiterIconButton(
                    iconRes = CoreR.drawable.ic_microphone,
                    contentDescription = "Voice command",
                    tint = if (!voiceAvailable) {
                        Color.White.copy(alpha = 0.45f)
                    } else {
                        when (voiceState) {
                            VoiceState.LISTENING -> OrbiterAccent
                            VoiceState.PROCESSING -> Color(0xFFFFA726)
                            VoiceState.ERROR -> Color(0xFFEF5350)
                            VoiceState.IDLE -> Color.White
                        }
                    },
                    onClick = onVoiceClick,
                )
            }
        }
    }
}

@Composable
internal fun SyncPlayDialogContent(
    state: PlayerViewModel.SyncPlayUiState,
    onRefresh: () -> Unit,
    onCreateGroup: () -> Unit,
    onJoinGroup: (UUID) -> Unit,
    onLeaveGroup: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = Color(0xFF101114),
        tonalElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(24.dp).width(540.dp).heightIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "SyncPlay",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            state.activeGroup?.let { group ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Active group: ${group.name}", color = Color.White)
                        Text(
                            "State: ${group.state}",
                            color = Color.White.copy(alpha = 0.8f),
                        )
                        Text(
                            "Participants: ${group.participants.joinToString().ifBlank { "Just you" }}",
                            color = Color.White.copy(alpha = 0.8f),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = onLeaveGroup) { Text("Leave Group") }
                            TextButton(onClick = onRefresh) { Text("Refresh") }
                        }
                    }
                }
            } ?: Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onCreateGroup, enabled = !state.isLoading) { Text("Create Group") }
                TextButton(onClick = onRefresh, enabled = !state.isLoading) { Text("Refresh") }
            }

            state.statusMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (state.isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
            }

            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.availableGroups.forEach { group ->
                    Surface(
                        onClick = { onJoinGroup(group.id) },
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White.copy(alpha = 0.06f),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(group.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${group.participants.size} participant(s) \u2022 ${group.state}",
                                color = Color.White.copy(alpha = 0.75f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                if (state.availableGroups.isEmpty() && !state.isLoading) {
                    Text(
                        "No active SyncPlay groups on this server.",
                        color = Color.White.copy(alpha = 0.7f),
                    )
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
