package dev.jdtech.jellyfin.player.xr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem

/**
 * "Up Next" panel shown in the last 2 minutes of an episode. Pure presentation:
 * the parent screen owns both the visibility rule and the `onPlayNext` action.
 */
@Composable
internal fun NextEpisodePanelContent(
    nextEpisode: PlayerItem,
    onPlayNext: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = Color.Black.copy(alpha = 0.92f),
        tonalElevation = 8.dp,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.padding(32.dp)) {
            if (nextEpisode.backdropImageUri != null) {
                AsyncImage(
                    model = nextEpisode.backdropImageUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(20.dp)),
                )
                Spacer(Modifier.height(16.dp))
            }

            Text(
                text = "Up Next",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))

            val episodeLabel = nextEpisode.parentIndexNumber?.let { s ->
                nextEpisode.indexNumber?.let { ep -> "S${s}E${ep} " }
            } ?: ""
            val displayTitle = if (nextEpisode.seriesName != null) {
                "${nextEpisode.seriesName} — $episodeLabel${nextEpisode.name}"
            } else {
                nextEpisode.name
            }
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(72.dp),
                ) {
                    Text("Dismiss", style = MaterialTheme.typography.titleLarge)
                }
                Button(
                    onClick = onPlayNext,
                    modifier = Modifier
                        .weight(2f)
                        .height(72.dp),
                ) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_play),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Play Next", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}
