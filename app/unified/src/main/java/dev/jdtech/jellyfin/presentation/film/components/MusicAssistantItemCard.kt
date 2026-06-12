package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerMediaItem
import dev.spatialfin.presentation.theme.spacings
import dev.spatialfin.unified.music.MaHdBadge
import dev.spatialfin.unified.music.isHighDefinitionAudio
import dev.spatialfin.unified.music.providerDisplayName

@Composable
fun MusicAssistantItemCard(
    item: ServerMediaItem,
    direction: Direction,
    onClick: (ServerMediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val width =
        when (direction) {
            Direction.HORIZONTAL -> 360
            Direction.VERTICAL -> 220
        }
        
    val metadata = buildMusicAssistantMetadata(item)

    Column(
        modifier =
            modifier
                .width(width.dp)
                .clip(MaterialTheme.shapes.small),
    ) {
        ElevatedCard(shape = MaterialTheme.shapes.small, onClick = { onClick(item) }) {
            Box(
                modifier = Modifier.fillMaxWidth().height((width * 1.5).toInt().dp),
                contentAlignment = Alignment.Center
            ) {
                // Placeholder since we don't have image rendering set up for MA yet
                Text(
                    text = item.mediaType.uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.isHighDefinitionAudio()) {
                    MaHdBadge(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.extraSmall))
        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.extraSmall)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                modifier = Modifier.heightIn(min = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (metadata.isNotBlank()) {
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Box(
                modifier = Modifier.heightIn(min = 40.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                FilledTonalButton(
                    onClick = { onClick(item) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Play")
                }
            }

            Spacer(Modifier.height(6.dp))
        }
    }
}

private fun buildMusicAssistantMetadata(item: ServerMediaItem): String =
    buildList {
        add(item.mediaType.replaceFirstChar(Char::titlecase))
        val artists = item.artists
        if (!artists.isNullOrEmpty()) {
            add(artists.joinToString { it.name })
        }
        val album = item.album
        if (album != null) {
            add(album.name)
        }
        // Trailing source label (YouTube Music / Local / Audible …).
        item.providerDisplayName()?.let { add(it) }
    }.joinToString(" • ")
