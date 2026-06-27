package dev.spatialfin.unified.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerMediaItem
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.spatialfin.unified.MaPlayDispatcher

/**
 * Long-press action sheet for a Music Assistant card on the Home rows. Home MA
 * cards are [SpatialFinItem]s whose `originalTitle` holds the MA uri — enough
 * for Play / Play next / Add to queue / Add to playlist (all uri-based).
 *
 * Favourite is intentionally absent: it needs the library item_id + media_type,
 * which the home-card projection doesn't carry. Use the search/detail screens
 * (full [ServerMediaItem]) for favouriting.
 */
@Composable
fun MaCardActionsMenu(
    item: SpatialFinItem,
    dispatcher: MaPlayDispatcher?,
    onDismiss: () -> Unit,
) {
    val uri = item.originalTitle
    if (dispatcher == null || uri.isNullOrBlank()) {
        onDismiss()
        return
    }
    val serverItem = remember(item.id) {
        ServerMediaItem(itemId = "", provider = "", name = item.name, uri = uri, mediaType = "track")
    }
    var showPlaylistPicker by remember { mutableStateOf(false) }

    if (showPlaylistPicker) {
        MaAddToPlaylistDialog(
            items = listOf(serverItem),
            dispatcher = dispatcher,
            onDismiss = onDismiss,
        )
        return
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                ActionRow("Play now", Icons.Filled.PlayArrow) {
                    dispatcher.play(serverItem); onDismiss()
                }
                ActionRow("Play next", Icons.Filled.SkipNext) {
                    dispatcher.playNext(serverItem); onDismiss()
                }
                ActionRow("Add to queue", Icons.AutoMirrored.Filled.QueueMusic) {
                    dispatcher.addToQueue(serverItem); onDismiss()
                }
                ActionRow("Add to playlist", Icons.Filled.Add) {
                    showPlaylistPicker = true
                }
            }
        }
    }
}

@Composable
private fun ActionRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
