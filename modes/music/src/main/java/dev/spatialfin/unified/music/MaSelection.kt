package dev.spatialfin.unified.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerMediaItem
import dev.spatialfin.unified.MaPlayDispatcher

/**
 * Long-press multi-select state for a track list. Keyed by the same
 * `itemId|provider` string the lists use for their lazy keys.
 */
@Stable
class MaSelectionState {
    private val map = mutableStateMapOf<String, ServerMediaItem>()

    val items: List<ServerMediaItem> get() = map.values.toList()
    val count: Int get() = map.size
    val active: Boolean get() = map.isNotEmpty()

    fun isSelected(key: String): Boolean = map.containsKey(key)

    fun toggle(key: String, item: ServerMediaItem) {
        if (map.containsKey(key)) map.remove(key) else map[key] = item
    }

    fun clear() = map.clear()
}

@Composable
fun rememberMaSelection(): MaSelectionState = remember { MaSelectionState() }

/**
 * Contextual action bar shown while a multi-select is active. Bulk actions
 * route through [MaPlayDispatcher] and clear the selection on completion.
 * Works on every form factor: the buttons are d-pad/pointer/touch reachable.
 */
@Composable
fun MaSelectionBar(
    selection: MaSelectionState,
    dispatcher: MaPlayDispatcher?,
    modifier: Modifier = Modifier,
) {
    if (!selection.active) return
    var showPlaylistPicker by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { selection.clear() }) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
            }
            Text(
                text = "${selection.count} selected",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                enabled = dispatcher != null,
                onClick = {
                    dispatcher?.playNext(selection.items)
                    selection.clear()
                },
            ) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Play next")
            }
            IconButton(
                enabled = dispatcher != null,
                onClick = {
                    dispatcher?.addToQueue(selection.items)
                    selection.clear()
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Add to queue")
            }
            IconButton(
                enabled = dispatcher != null,
                onClick = { showPlaylistPicker = true },
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add to playlist")
            }
            IconButton(
                enabled = dispatcher != null,
                onClick = {
                    dispatcher?.toggleFavorite(selection.items, makeFavorite = true)
                    selection.clear()
                },
            ) {
                Icon(Icons.Filled.FavoriteBorder, contentDescription = "Add to favorites")
            }
            Spacer(Modifier.width(4.dp))
        }
    }

    if (showPlaylistPicker && dispatcher != null) {
        MaAddToPlaylistDialog(
            items = selection.items,
            dispatcher = dispatcher,
            onDismiss = {
                showPlaylistPicker = false
                selection.clear()
            },
        )
    }
}
