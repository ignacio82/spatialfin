package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.api.SeerrSearchResult

@Composable
fun SeerrItemPoster(item: SeerrSearchResult, direction: Direction, modifier: Modifier = Modifier) {
    val imagePath = if (direction == Direction.HORIZONTAL) {
        item.backdropPath ?: item.posterPath
    } else {
        item.posterPath
    }
    
    val imageUrl = imagePath?.let { "https://image.tmdb.org/t/p/w500$it" }

    AsyncImage(
        model = imageUrl,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier =
            modifier
                .aspectRatio(if (direction == Direction.HORIZONTAL) 1.77f else 0.66f)
                .background(MaterialTheme.colorScheme.surfaceContainer),
    )
}
