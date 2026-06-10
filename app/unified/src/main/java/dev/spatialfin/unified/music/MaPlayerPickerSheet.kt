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
            // synced group (the official app's "group players" UX). MA reports
            // each player's `canGroupWith`, so we only list compatible targets;
            // the toggle works mid-playback. Control can't be stranded by this —
            // the session follows whatever player is actually playing, even if
            // grouping flips its hide_in_ui flag.
            if (selectedPlayer != null) {
                val groupable = players.filter { p ->
                    p.id != selectedPlayer.id &&
                        (selectedPlayer.canGroupWith.contains(p.id) || p.canGroupWith.contains(selectedPlayer.id))
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
                        // The pair is grouped if the selected player leads `player`
                        // OR follows it. Removal must target the actual leader, so
                        // resolve who-leads-whom rather than assuming the selected
                        // player is the leader.
                        val selectedLeads = player.syncedToPlayerId == selectedPlayer.id ||
                            selectedPlayer.groupMemberIds.contains(player.id)
                        val selectedFollows = selectedPlayer.syncedToPlayerId == player.id ||
                            player.groupMemberIds.contains(selectedPlayer.id)
                        val grouped = selectedLeads || selectedFollows
                        // Add → selected player becomes the leader. Remove → target
                        // whichever of the two is the current leader.
                        val leaderId = if (selectedFollows) player.id else selectedPlayer.id
                        val memberId = if (selectedFollows) selectedPlayer.id else player.id
                        ListItem(
                            headlineContent = { Text(player.name) },
                            supportingContent = { Text(if (grouped) "In sync" else "Tap to add") },
                            leadingContent = { Icon(Icons.Filled.SpeakerGroup, contentDescription = null) },
                            trailingContent = {
                                Checkbox(
                                    checked = grouped,
                                    onCheckedChange = { onToggleGroupMember(leaderId, memberId, grouped) },
                                )
                            },
                            modifier = Modifier.fillMaxWidth().clickable(
                                onClick = { onToggleGroupMember(leaderId, memberId, grouped) },
                            ),
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        )
                    }
                }
            }
        }
    }
}

