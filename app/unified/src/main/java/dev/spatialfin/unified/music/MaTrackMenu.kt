package dev.spatialfin.unified.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerMediaItem
import dev.spatialfin.unified.MaPlayDispatcher
import kotlinx.coroutines.launch

/**
 * Per-track overflow menu: Play now / Play next / Add to queue / Add to
 * playlist / favourite toggle. Drop it into the `trailing` slot of
 * [MaSearchItemRow] (search + detail track lists). All actions route through
 * [MaPlayDispatcher], so it works identically on Beam, TV, and XR.
 *
 * Reorder/remove for *queue* items lives in [MaQueuePanel] instead — those act
 * on `queue_item_id`s, not library media items.
 */
@Composable
fun MaTrackMenu(
    item: ServerMediaItem,
    dispatcher: MaPlayDispatcher?,
    modifier: Modifier = Modifier,
) {
    if (dispatcher == null) return
    var expanded by remember { mutableStateOf(false) }
    var favorite by remember(item.itemId) { mutableStateOf(item.favorite == true) }
    var showPlaylistPicker by remember { mutableStateOf(false) }

    Box(modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Track actions")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Play now") },
                leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                onClick = { expanded = false; dispatcher.play(item) },
            )
            DropdownMenuItem(
                text = { Text("Play next") },
                leadingIcon = { Icon(Icons.Filled.SkipNext, contentDescription = null) },
                onClick = { expanded = false; dispatcher.playNext(item) },
            )
            DropdownMenuItem(
                text = { Text("Add to queue") },
                leadingIcon = { Icon(Icons.Filled.QueueMusic, contentDescription = null) },
                onClick = { expanded = false; dispatcher.addToQueue(item) },
            )
            DropdownMenuItem(
                text = { Text("Add to playlist") },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = { expanded = false; showPlaylistPicker = true },
            )
            DropdownMenuItem(
                text = { Text(if (favorite) "Remove from favorites" else "Add to favorites") },
                leadingIcon = {
                    Icon(
                        if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = null,
                    )
                },
                onClick = {
                    expanded = false
                    val target = !favorite
                    favorite = target // optimistic flip; corrected by callback on failure
                    dispatcher.toggleFavorite(item, target) { favorite = it }
                },
            )
        }
    }

    if (showPlaylistPicker) {
        MaAddToPlaylistDialog(
            items = listOf(item),
            dispatcher = dispatcher,
            onDismiss = { showPlaylistPicker = false },
        )
    }
}

/**
 * Playlist picker shared by the single-track menu and the multi-select bar.
 * [items] is the batch to append (a singleton for the per-row menu).
 */
@Composable
internal fun MaAddToPlaylistDialog(
    items: List<ServerMediaItem>,
    dispatcher: MaPlayDispatcher,
    onDismiss: () -> Unit,
) {
    var playlists by remember { mutableStateOf<List<ServerMediaItem>?>(null) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { playlists = dispatcher.editablePlaylists() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(400.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Add to playlist",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))

                val loaded = playlists
                when {
                    loaded == null -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                    }
                    else -> {
                        if (loaded.isEmpty()) {
                            Text(
                                text = "No editable playlists yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                                items(loaded, key = { it.itemId }) { playlist ->
                                    Text(
                                        text = playlist.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !busy) {
                                                busy = true
                                                dispatcher.addToPlaylist(playlist.itemId, items) {
                                                    onDismiss()
                                                }
                                            }
                                            .padding(vertical = 12.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (creating) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Playlist name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        TextButton(
                            enabled = newName.isNotBlank() && !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    val created = dispatcher.createPlaylist(newName.trim())
                                    if (created != null) {
                                        dispatcher.addToPlaylist(created.itemId, items) { onDismiss() }
                                    } else {
                                        busy = false
                                    }
                                }
                            },
                        ) { Text("Create & add") }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { creating = true }) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("New playlist")
                        }
                        TextButton(onClick = onDismiss) { Text("Close") }
                    }
                }
            }
        }
    }
}
