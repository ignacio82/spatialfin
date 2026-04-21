package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.film.presentation.home.HomeAction
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.spatialfin.presentation.theme.spacings

@Composable
fun HomeCarouselItem(
    item: SpatialFinItem,
    displayRatings: Boolean = true,
    onAction: (HomeAction) -> Unit,
) {
    val colorStops =
        arrayOf(
            0.0f to Color.Black.copy(alpha = 0.1f),
            0.5f to Color.Black.copy(alpha = 0.5f),
            1f to Color.Black.copy(alpha = 0.6f),
        )
    val genres =
        when (item) {
            is SpatialFinMovie -> item.genres
            is SpatialFinShow -> item.genres
            else -> emptyList()
        }
    val genresLabel = genres.joinToString()

    Box(
        modifier =
            Modifier.aspectRatio(1.77f).clip(MaterialTheme.shapes.large).clickable {
                onAction(HomeAction.OnItemClick(item))
            },
    ) {
        AsyncImage(
            model = item.images.backdrop,
            placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainer),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(brush = Brush.verticalGradient(colorStops = colorStops))
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
            modifier =
                Modifier.padding(
                        horizontal = MaterialTheme.spacings.default,
                        vertical = MaterialTheme.spacings.medium,
                    )
                    .align(Alignment.BottomStart),
        ) {
            if (displayRatings) {
                Box(
                    modifier = Modifier.heightIn(min = 24.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (item.ratings.isNotEmpty()) {
                        RatingsRow(ratings = item.ratings)
                    }
                }
            }

            Box(
                modifier = Modifier.heightIn(min = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (genresLabel.isNotBlank()) {
                    Text(
                        text = genresLabel,
                        color = Color.LightGray,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Text(
                text = item.name,
                color = Color.White,
                overflow = TextOverflow.Ellipsis,
                minLines = 2,
                maxLines = 2,
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
@Preview(showBackground = true)
private fun HomeCarouselItemPreview() {
    SpatialFinTheme { HomeCarouselItem(item = dummyMovie, onAction = {}) }
}
