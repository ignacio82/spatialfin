package dev.jdtech.jellyfin.player.xr

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.player.core.domain.models.PlayerPerson

internal data class CastCardUiModel(
    val matchedPeople: List<PlayerPerson>,
    val selectedPersonIndex: Int = 0,
    val relatedItems: List<SpatialFinItem> = emptyList(),
    val isLoadingRelated: Boolean = false,
) {
    val selectedPerson: PlayerPerson?
        get() = matchedPeople.getOrNull(selectedPersonIndex) ?: matchedPeople.firstOrNull()
}

@Composable
internal fun SpatialCastCardOverlay(
    model: CastCardUiModel,
    onSelectPersonIndex: (Int) -> Unit,
    onPlayItem: (SpatialFinItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val person = model.selectedPerson ?: return

    Surface(
        modifier = modifier
            .width(540.dp)
            .heightIn(max = 460.dp),
        shape = RoundedCornerShape(28.dp),
        color = Color(0xEE101726),
        tonalElevation = 12.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "IDENTIFIED IN SCENE",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFF90CAF9),
                    fontWeight = FontWeight.Bold,
                )
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        text = "Dismiss",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            if (model.matchedPeople.size > 1) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    model.matchedPeople.forEachIndexed { index, candidate ->
                        val selected = index == model.selectedPersonIndex
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectPersonIndex(index) },
                            label = {
                                Text(
                                    text = candidate.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF38BDF8).copy(alpha = 0.24f),
                                selectedLabelColor = Color.White,
                                containerColor = Color.White.copy(alpha = 0.08f),
                                labelColor = Color.White.copy(alpha = 0.75f),
                            ),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PersonPhoto(imageUri = person.imageUri, sizeDp = 88)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = person.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (person.role.isNotBlank()) {
                        Text(
                            text = "as ${person.role}",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF90CAF9),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (model.isLoadingRelated) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF90CAF9),
                    )
                    Text(
                        text = "Checking your library…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            } else if (model.relatedItems.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "ALSO IN YOUR LIBRARY",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.65f),
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(end = 8.dp),
                    ) {
                        items(model.relatedItems, key = { it.id }) { item ->
                            Column(
                                modifier = Modifier
                                    .width(96.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { onPlayItem(item) }
                                    .padding(4.dp),
                                horizontalAlignment = Alignment.Start,
                            ) {
                                SearchResultPoster(item = item)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = searchResultTypeLabel(item),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.6f),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
