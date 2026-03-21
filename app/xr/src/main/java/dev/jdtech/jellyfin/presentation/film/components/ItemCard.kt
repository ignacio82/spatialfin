package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.isDownloaded
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.spatialfin.presentation.theme.spacings

@Composable
fun ItemCard(
    item: SpatialFinItem,
    direction: Direction,
    onClick: (SpatialFinItem) -> Unit,
    onDeleteClick: ((SpatialFinItem) -> Unit)? = null,
    displayRatings: Boolean = true,
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
                .clickable(onClick = { onClick(item) })
    ) {
        ElevatedCard(shape = MaterialTheme.shapes.small) {
            Box {
                ItemPoster(item = item, direction = direction)
                Row(
                    modifier =
                        Modifier.align(Alignment.TopEnd).padding(MaterialTheme.spacings.small),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                ) {
                    if (item.isDownloaded()) DownloadedBadge()
                    if (item.played) PlayedBadge()
                    item.unplayedItemCount?.takeIf { it > 0 }?.let { ItemCountBadge(it) }
                }
                onDeleteClick?.let { delete ->
                    FilledTonalIconButton(
                        onClick = { delete(item) },
                        modifier =
                            Modifier.align(Alignment.TopStart)
                                .padding(MaterialTheme.spacings.small),
                    ) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(R.drawable.ic_trash),
                            contentDescription = null,
                        )
                    }
                }
                if (direction == Direction.HORIZONTAL) {
                    ProgressBar(
                        item = item,
                        width = width,
                        modifier =
                            Modifier.align(Alignment.BottomStart)
                                .padding(MaterialTheme.spacings.small),
                    )
                }
            }
        }
        if (displayRatings && item.ratings.isNotEmpty()) {
            Spacer(modifier = Modifier.height(MaterialTheme.spacings.extraSmall))
            RatingsRow(ratings = item.ratings.take(2))
        }
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.extraSmall))
        Text(
            text = if (item is SpatialFinEpisode) item.seriesName else item.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = if (item is SpatialFinEpisode) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (item is SpatialFinEpisode) {
            Text(
                text =
                    stringResource(
                        id = R.string.episode_name_extended,
                        item.parentIndexNumber,
                        item.indexNumber,
                        item.name,
                    ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun ItemCardPreviewMovie() {
    SpatialFinTheme { ItemCard(item = dummyMovie, direction = Direction.HORIZONTAL, onClick = {}) }
}

@Preview(showBackground = true)
@Composable
private fun ItemCardPreviewMovieVertical() {
    SpatialFinTheme { ItemCard(item = dummyMovie, direction = Direction.VERTICAL, onClick = {}) }
}

@Preview(showBackground = true)
@Composable
private fun ItemCardPreviewEpisode() {
    SpatialFinTheme { ItemCard(item = dummyEpisode, direction = Direction.HORIZONTAL, onClick = {}) }
}

@Preview(showBackground = true)
@Composable
private fun ItemCardPreviewEpisodeVertical() {
    SpatialFinTheme { ItemCard(item = dummyEpisode, direction = Direction.VERTICAL, onClick = {}) }
}
