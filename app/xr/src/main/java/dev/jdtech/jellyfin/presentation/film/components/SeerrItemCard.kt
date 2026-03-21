package dev.jdtech.jellyfin.presentation.film.components

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
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.api.SeerrSearchResult
import dev.jdtech.jellyfin.film.R
import dev.spatialfin.presentation.theme.spacings

@Composable
fun SeerrItemCard(
    item: SeerrSearchResult,
    direction: Direction,
    onRequestClick: (SeerrSearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val width =
        when (direction) {
            Direction.HORIZONTAL -> 360
            Direction.VERTICAL -> 220
        }
    Column(
        modifier =
            modifier
                .width(width.dp)
                .clip(MaterialTheme.shapes.small)
    ) {
        ElevatedCard(shape = MaterialTheme.shapes.small) {
            Box {
                SeerrItemPoster(item = item, direction = direction)
                
                val status = item.mediaInfo?.status ?: 1
                SeerrStatusBadge(
                    status = status,
                    modifier = Modifier.align(Alignment.TopEnd).padding(MaterialTheme.spacings.small)
                )
            }
        }
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.extraSmall))
        Text(
            text = item.title ?: item.name ?: "",
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        
        val status = item.mediaInfo?.status ?: 1
        if (status == 1) { // Unknown / Not Requested
            Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))
            Button(
                onClick = { onRequestClick(item) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.search_request_action))
            }
        } else if (status == 2) { // Pending
            Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))
            Text(
                text = stringResource(R.string.search_request_pending),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun SeerrStatusBadge(status: Int, modifier: Modifier = Modifier) {
    val (text, color) = when (status) {
        2 -> "Pending" to Color(0xFFFFC107)
        3 -> "Processing" to Color(0xFF2196F3)
        4 -> "Partially Available" to Color(0xFF8BC34A)
        5 -> "Available" to Color(0xFF4CAF50)
        else -> return
    }

    SurfaceBadge(text = text, color = color, modifier = modifier)
}

@Composable
private fun SurfaceBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .clip(MaterialTheme.shapes.extraSmall)
                .background(color.copy(alpha = 0.9f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Color.Black,
        )
    }
}
