package dev.spatialfin.unified.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerMediaItem

/**
 * One row in a Music Assistant result list. Used by [MaSearchScreen] and
 * (in Phase 2 detail screens) by the album / artist / playlist track lists.
 * Lifted to a top-level composable rather than a private helper because the
 * detail screens reuse it verbatim — and any future polish (long-press
 * context menu, drag-to-reorder, multi-select check) needs to land in one
 * place.
 */
@Composable
fun MaSearchItemRow(
    item: ServerMediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(item = item)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = item.subtitleLine()
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
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
private fun Artwork(item: ServerMediaItem) {
    val shape = RoundedCornerShape(6.dp)
    val artworkUrl = item.preferredArtworkUrl()
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

internal fun ServerMediaItem.subtitleLine(): String? {
    val artistName = artists?.firstOrNull()?.name?.takeIf { it.isNotBlank() }
    val albumName = album?.name?.takeIf { it.isNotBlank() }
    val year = year?.toString()
    return when (mediaType.lowercase()) {
        "track" -> listOfNotNull(artistName, albumName).joinToString(" • ").ifBlank { null }
        "album" -> listOfNotNull(artistName, year).joinToString(" • ").ifBlank { null }
        "artist" -> null
        "playlist" -> "Playlist"
        "podcast" -> publisher ?: "Podcast"
        "radio" -> "Radio"
        else -> artistName
    }
}

internal fun ServerMediaItem.preferredArtworkUrl(): String? =
    image?.path ?: metadata?.images?.firstOrNull()?.path
