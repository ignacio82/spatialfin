package dev.spatialfin.unified.music

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.data.musicassistant.repository.MaPlayerSummary
import dev.jdtech.jellyfin.data.musicassistant.repository.MaSessionRepository

/**
 * Picker for "where should the next track play?". Mirrors the official MA
 * mobile app's player selector — every visible non-protocol player is listed,
 * the currently-selected one is checked, and tap commits.
 *
 * The "Auto (this device)" row at the top maps to clearing the override —
 * after that, [SendspinReceiverService.resolvePlayMediaTarget] auto-detects
 * the Universal-Player wrapper for this device's SendSpin endpoint. That's
 * the right default: the user just tapped a song on the Pixel and expects
 * audio from the Pixel.
 *
 * Selection is persisted per MA server id (via the dispatcher's
 * setPreferredPlayer wiring) so it survives process death and reconnects.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaPlayerPickerSheet(
    session: MaSessionRepository,
    dispatcher: dev.spatialfin.unified.MaPlayDispatcher,
    serverId: String?,
    onDismiss: () -> Unit,
) {
    val state by session.session.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        PickerContent(
            players = state.visiblePlayers,
            selectedPlayer = state.selectedPlayer,
            onPick = { id ->
                dispatcher.setPreferredPlayer(serverId, id)
                onDismiss()
            },
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
private fun PickerContent(
    players: List<MaPlayerSummary>,
    selectedPlayer: MaPlayerSummary?,
    onPick: (String?) -> Unit,
    onToggleGroupMember: (leaderId: String, memberId: String, currentlyGrouped: Boolean) -> Unit,
) {
    val selectedPlayerId = selectedPlayer?.id
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            text = "Play on",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )

        // "Auto (this device)" — clears the override. Shown checked when the
        // user hasn't picked anything explicitly (selectedPlayerId == null).
        ListItem(
            headlineContent = { Text("Auto (this device)") },
            supportingContent = { Text("Auto-detected SendSpin wrapper") },
            leadingContent = { Icon(Icons.Filled.Speaker, contentDescription = null) },
            trailingContent = if (selectedPlayerId == null) {
                { Icon(Icons.Filled.Check, contentDescription = "Selected") }
            } else null,
            modifier = Modifier.fillMaxWidth().clickable(onClick = { onPick(null) }),
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        )

        if (players.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Text(
                    text = "No other Music Assistant players available.",
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
            items(players, key = { it.id }) { player ->
                ListItem(
                    headlineContent = { Text(player.name) },
                    supportingContent = {
                        val status = if (player.isPlaying) "Now playing" else player.provider
                        Text(status)
                    },
                    leadingContent = { Icon(Icons.Filled.Speaker, contentDescription = null) },
                    trailingContent = if (player.id == selectedPlayerId) {
                        { Icon(Icons.Filled.Check, contentDescription = "Selected") }
                    } else null,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = { onPick(player.id) }),
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }

            // Multi-room: pick which other players join the current one in a
            // synced group (the official app's "group players" UX). Mirrors the
            // MA frontend's logic exactly — both players must advertise the
            // `set_members` feature, candidates must share a sync protocol
            // (can_group_with matches by player id OR provider, in either
            // direction), and a candidate already captured by a different group
            // is excluded. The toggle works mid-playback; control can't be
            // stranded because the session follows whatever's actually playing.
            val leader = selectedPlayer?.takeIf { it.supportsGrouping && it.syncedToPlayerId == null }
            if (leader != null) {
                val groupable = players.filter { p ->
                    p.id != leader.id &&
                        p.supportsGrouping &&
                        (
                            leader.canGroupWith.contains(p.id) ||
                                leader.canGroupWith.contains(p.provider) ||
                                p.canGroupWith.contains(leader.id) ||
                                p.canGroupWith.contains(leader.provider)
                        ) &&
                        (p.activeGroup == null || p.activeGroup == leader.id) &&
                        (p.syncedToPlayerId == null || p.syncedToPlayerId == leader.id)
                }
                if (groupable.isNotEmpty()) {
                    item(key = "group-header") {
                        HorizontalDivider()
                        Text(
                            text = "Also play on (in sync)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(groupable, key = { "group-" + it.id }) { player ->
                        // `leader` is always the group leader here (it isn't synced
                        // to anything), so a candidate is grouped iff it's synced
                        // under the leader. Toggling adds/removes it from the leader.
                        val grouped = player.syncedToPlayerId == leader.id ||
                            leader.groupMemberIds.contains(player.id)
                        ListItem(
                            headlineContent = { Text(player.name) },
                            supportingContent = { Text(if (grouped) "In sync" else "Tap to add") },
                            leadingContent = { Icon(Icons.Filled.SpeakerGroup, contentDescription = null) },
                            trailingContent = {
                                Checkbox(
                                    checked = grouped,
                                    onCheckedChange = { onToggleGroupMember(leader.id, player.id, grouped) },
                                )
                            },
                            modifier = Modifier.fillMaxWidth().clickable(
                                onClick = { onToggleGroupMember(leader.id, player.id, grouped) },
                            ),
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        )
                    }
                }
            }
        }
    }
}

