package dev.jdtech.jellyfin.player.xr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
 * Secondary control cluster that floats beside the main playback panel
 * (audio / subtitles / speed / quality / SyncPlay / cast-crew / voice),
 * plus the SyncPlay group-management dialog.
 *
 * Both are pure presentation with callbacks — the parent screen owns state.
 */

@Composable
internal fun SecondaryControlsOrbiter(
    onAudioClick: () -> Unit,
    onSubtitleClick: () -> Unit,
    onSpeedClick: () -> Unit,
    onQualityClick: () -> Unit,
    onSyncPlayClick: () -> Unit,
    onCastCrewClick: () -> Unit,
    onVoiceClick: () -> Unit,
    voiceControlEnabled: Boolean,
    voiceAvailable: Boolean,
    voiceState: VoiceState,
    syncPlayActive: Boolean,
) {
    Surface(
        shape = RoundedCornerShape(40.dp),
        color = Color.Black.copy(alpha = 0.9f),
        tonalElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconButton(onClick = onAudioClick, modifier = Modifier.size(100.dp)) {
                Icon(
                    painterResource(CoreR.drawable.ic_speaker),
                    contentDescription = "Audio track",
                    tint = Color.White,
                    modifier = Modifier.size(64.dp),
                )
            }
            IconButton(onClick = onSubtitleClick, modifier = Modifier.size(100.dp)) {
                Icon(
                    painterResource(CoreR.drawable.ic_closed_caption),
                    contentDescription = "Subtitle track",
                    tint = Color.White,
                    modifier = Modifier.size(64.dp),
                )
            }
            IconButton(onClick = onSpeedClick, modifier = Modifier.size(100.dp)) {
                Icon(
                    painterResource(CoreR.drawable.ic_gauge),
                    contentDescription = "Playback speed",
                    tint = Color.White,
                    modifier = Modifier.size(64.dp),
                )
            }
            IconButton(onClick = onQualityClick, modifier = Modifier.size(100.dp)) {
                Icon(
                    painterResource(CoreR.drawable.ic_sparkles),
                    contentDescription = "Playback quality",
                    tint = Color.White,
                    modifier = Modifier.size(64.dp),
                )
            }
            IconButton(onClick = onSyncPlayClick, modifier = Modifier.size(100.dp)) {
                Icon(
                    painterResource(CoreR.drawable.ic_tv),
                    contentDescription = "SyncPlay",
                    tint = if (syncPlayActive) Color(0xFF4FC3F7) else Color.White,
                    modifier = Modifier.size(64.dp),
                )
            }
            IconButton(onClick = onCastCrewClick, modifier = Modifier.size(100.dp)) {
                Icon(
                    painterResource(CoreR.drawable.ic_user),
                    contentDescription = "Cast & crew",
                    tint = Color.White,
                    modifier = Modifier.size(64.dp),
                )
            }
            if (voiceControlEnabled) {
                IconButton(onClick = onVoiceClick, modifier = Modifier.size(100.dp)) {
                    Icon(
                        painterResource(CoreR.drawable.ic_microphone),
                        contentDescription = "Voice command",
                        tint =
                            if (!voiceAvailable) {
                                Color.White.copy(alpha = 0.45f)
                            } else {
                                when (voiceState) {
                                    VoiceState.LISTENING -> Color(0xFF4FC3F7)
                                    VoiceState.PROCESSING -> Color(0xFFFFA726)
                                    VoiceState.ERROR -> Color(0xFFEF5350)
                                    VoiceState.IDLE -> Color.White
                                }
                            },
                        modifier = Modifier.size(64.dp),
                    )
                }
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
