package dev.spatialfin.unified.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerQueueItem

/**
 * The play queue for the active player, hosted as a sub-view of
 * [MaNowPlayingScreen] so every form factor (Beam / TV / XR) gets it without
 * per-root wiring.
 *
 * Reorder uses explicit move-up / move-down actions rather than drag-and-drop:
 * drag isn't reachable by the TV d-pad, and the same overflow menu works with
 * touch, pointer, and focus navigation. Tap a row to jump playback to it.
 */
@Composable
fun MaQueuePanel(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MaQueueViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back to now playing")
                }
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(
                        text = if (state.partyActive) "Party queue" else "Queue",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (state.partyActive) {
                        Text(
                            text = "Party mode is on",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            if (state.items.isEmpty()) {
                EmptyQueue(modifier = Modifier.fillMaxSize())
                return@Column
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.items, key = { it.queueItemId }) { item ->
                    val isCurrent = item.queueItemId == state.currentItemId
                    QueueRow(
                        item = item,
                        isCurrent = isCurrent,
                        onPlay = { viewModel.jumpTo(item) },
                        onMoveEarlier = { viewModel.moveEarlier(item) },
                        onMoveLater = { viewModel.moveLater(item) },
                        onRemove = { viewModel.remove(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyQueue(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.size(48.dp))
        Icon(
            imageVector = Icons.Filled.QueueMusic,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "The queue is empty",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun QueueRow(
    item: ServerQueueItem,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isCurrent) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.background,
            )
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QueueArtwork(item = item)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.displayTitle(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                color = if (isCurrent) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = item.displaySubtitle()
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
        if (isCurrent) {
            Icon(
                imageVector = Icons.Filled.Equalizer,
                contentDescription = "Now playing",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        QueueRowMenu(
            onMoveEarlier = onMoveEarlier,
            onMoveLater = onMoveLater,
            onRemove = onRemove,
        )
    }
}

@Composable
private fun QueueRowMenu(
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Queue item actions")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Move up") },
                leadingIcon = { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null) },
                onClick = { expanded = false; onMoveEarlier() },
            )
            DropdownMenuItem(
                text = { Text("Move down") },
                leadingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                onClick = { expanded = false; onMoveLater() },
            )
            DropdownMenuItem(
                text = { Text("Remove from queue") },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = { expanded = false; onRemove() },
            )
        }
    }
}

@Composable
private fun QueueArtwork(item: ServerQueueItem) {
    val shape = RoundedCornerShape(6.dp)
    val artworkUrl = item.artworkPath()
    if (artworkUrl.isNullOrBlank()) {
        Box(
            modifier = Modifier
                .size(48.dp)
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
            modifier = Modifier.size(48.dp).clip(shape),
        )
    }
}

private fun ServerQueueItem.displayTitle(): String =
    name?.takeIf { it.isNotBlank() }
        ?: mediaItem?.name?.takeIf { it.isNotBlank() }
        ?: "Unknown track"

private fun ServerQueueItem.displaySubtitle(): String? =
    mediaItem?.artists?.firstOrNull()?.name?.takeIf { it.isNotBlank() }
        ?: mediaItem?.album?.name?.takeIf { it.isNotBlank() }

private fun ServerQueueItem.artworkPath(): String? =
    image?.path
        ?: mediaItem?.image?.path
        ?: mediaItem?.metadata?.images?.firstOrNull()?.path
