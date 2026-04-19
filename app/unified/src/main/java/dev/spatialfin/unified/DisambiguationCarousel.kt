package dev.spatialfin.unified

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.models.SpatialFinItem

/**
 * Horizontal carousel that replaces the "pick from the dialog" disambiguation
 * flow when the assistant returns multiple possibilities. Anchored above the
 * main panel and kept intentionally small so it can float in gaze-forward space
 * without covering content.
 *
 * The carousel is voice-first: users can still say "play the second one" —
 * clicking a card just mirrors that selection.
 */
@Composable
fun DisambiguationCarousel(
    query: String,
    items: List<SpatialFinItem>,
    onSelect: (Int, SpatialFinItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = items.size > 1,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color.Black.copy(alpha = 0.82f),
            tonalElevation = 10.dp,
            modifier = Modifier.widthIn(max = 920.dp),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Did you mean one of these?",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Say \u201cplay the second one\u201d or tap a card — query: $query",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Dismiss", color = Color.White)
                    }
                }
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    itemsIndexed(items.take(6)) { index, item ->
                        DisambiguationCard(
                            index = index,
                            item = item,
                            onClick = { onSelect(index, item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DisambiguationCard(
    index: Int,
    item: SpatialFinItem,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.White.copy(alpha = 0.08f),
        modifier = Modifier
            .width(220.dp)
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0xFF4FC3F7).copy(alpha = 0.25f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "${index + 1}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                )
            }
            val overview = item.overview.take(140)
            if (overview.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = overview,
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                )
            }
        }
    }
}
