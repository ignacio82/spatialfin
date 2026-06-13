package dev.spatialfin.unified.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.data.musicassistant.repository.MaPlayerSummary
import dev.jdtech.jellyfin.data.musicassistant.repository.MaSessionRepository
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverSession

/**
 * Multi-room picker for the active Music Assistant playback.
 * Fresh play commands always target this device's SendSpin wrapper; this sheet
 * only adds or removes other compatible speakers from that active playback
 * group after audio is already running.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaPlayerPickerSheet(
    session: MaSessionRepository,
    dispatcher: dev.spatialfin.unified.MaPlayDispatcher,
    onDismiss: () -> Unit,
) {
    val state by session.session.collectAsStateWithLifecycle()
    val receiverState by SendspinReceiverSession.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        MaPlayerPickerContent(
            players = state.visiblePlayers,
            selectedPlayer = state.selectedPlayer,
            localPlayerId = receiverState.musicAssistantQueuePlayerId,
            playbackActive = state.nowPlaying != null,
            onToggleGroupMember = { leaderId, memberId, grouped ->
                if (grouped) {
                    dispatcher.removeFromSyncGroup(leaderId, memberId)
                } else {
                    dispatcher.addToSyncGroup(leaderId, memberId)
                }
            },
        )
    }
}

@Composable
fun MaPlayerPickerContent(
    players: List<MaPlayerSummary>,
    selectedPlayer: MaPlayerSummary?,
    localPlayerId: String?,
    playbackActive: Boolean,
    onToggleGroupMember: (leaderId: String, memberId: String, currentlyGrouped: Boolean) -> Unit,
) {
    val localPlayer = localPlayerId
        ?.let { id -> players.firstOrNull { it.id == id } ?: selectedPlayer?.takeIf { it.id == id } }
    val activePlayer = localPlayer ?: selectedPlayer?.takeIf { playbackActive }
    val leader = activePlayer.groupLeader(players)
    val groupable = leader?.groupablePlayers(players).orEmpty()

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            text = "Speakers",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )

        if (activePlayer == null) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Text(
                    text = "Start playback on this device first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return
        }

        ListItem(
            headlineContent = { Text(activePlayer.name) },
            supportingContent = {
                Text(if (activePlayer.id == localPlayerId) "This device" else "Current playback")
            },
            leadingContent = { Icon(Icons.Filled.Speaker, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        )

        if (leader == null) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Text(
                    text = "Speaker grouping is not available for this playback.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return
        }

        HorizontalDivider()
        LazyColumn(
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            item(key = "group-header") {
                Text(
                    text = "Also play on",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            if (groupable.isEmpty()) {
                item(key = "no-groupable-speakers") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        Text(
                            text = "No compatible speakers available.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(groupable, key = { "group-" + it.id }) { player ->
                    val grouped = player.syncedToPlayerId == leader.id ||
                        player.activeGroup == leader.id ||
                        leader.groupMemberIds.contains(player.id)
                    ListItem(
                        headlineContent = { Text(player.name) },
                        supportingContent = { Text(if (grouped) "Playing too" else player.provider) },
                        leadingContent = { Icon(Icons.Filled.SpeakerGroup, contentDescription = null) },
                        trailingContent = {
                            Checkbox(
                                checked = grouped,
                                onCheckedChange = { onToggleGroupMember(leader.id, player.id, grouped) },
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                            .clickable(onClick = { onToggleGroupMember(leader.id, player.id, grouped) })
                            .maFocusHighlight(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    )
                }
            }
        }
    }
}

private fun MaPlayerSummary?.groupLeader(players: List<MaPlayerSummary>): MaPlayerSummary? {
    val active = this ?: return null
    if (active.supportsGrouping && active.syncedToPlayerId == null) return active
    return active.syncedToPlayerId
        ?.let { leaderId -> players.firstOrNull { it.id == leaderId && it.supportsGrouping } }
}

private fun MaPlayerSummary.groupablePlayers(players: List<MaPlayerSummary>): List<MaPlayerSummary> =
    players.filter { player ->
        player.id != id &&
            player.supportsGrouping &&
            (
                player.id in canGroupWith ||
                    player.provider in canGroupWith ||
                    id in player.canGroupWith ||
                    provider in player.canGroupWith
            ) &&
            (player.activeGroup == null || player.activeGroup == id) &&
            (player.syncedToPlayerId == null || player.syncedToPlayerId == id)
    }
