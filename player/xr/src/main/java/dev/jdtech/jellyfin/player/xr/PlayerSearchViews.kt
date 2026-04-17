package dev.jdtech.jellyfin.player.xr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.models.SpatialFinBoxSet
import dev.jdtech.jellyfin.models.SpatialFinCollection
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinFolder
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.SpatialFinShow

/**
 * Voice-search result rendering + small pure helpers for SpatialFinItem
 * (type label, poster URI resolution, trailer URL, favorite toggle,
 * playability predicate). Extracted from SpatialPlayerScreen.kt.
 */

internal fun searchResultTypeLabel(item: SpatialFinItem): String =
    when (item) {
        is SpatialFinMovie -> "Movie"
        is SpatialFinEpisode -> "Episode"
        is SpatialFinSeason -> "Season"
        is SpatialFinShow -> "Series"
        else -> "Item"
    }

@Composable
internal fun SearchResultPoster(item: SpatialFinItem) {
    val imageModel = itemPosterUri(item)
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.08f),
        modifier = Modifier.width(92.dp).height(138.dp),
    ) {
        if (imageModel != null) {
            AsyncImage(
                model = imageModel,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize().padding(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.name.take(1).ifBlank { "?" },
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        }
    }
}

internal fun itemPosterUri(item: SpatialFinItem): Any? =
    item.images.primary
        ?: item.images.showPrimary
        ?: item.images.backdrop
        ?: item.images.showBackdrop

internal fun itemTrailerUrl(item: SpatialFinItem): String? =
    when (item) {
        is SpatialFinMovie -> item.trailer
        is SpatialFinShow -> item.trailer
        else -> null
    }

internal fun SpatialFinItem.withFavorite(value: Boolean): SpatialFinItem =
    when (this) {
        is SpatialFinMovie -> copy(favorite = value)
        is SpatialFinShow -> copy(favorite = value)
        is SpatialFinEpisode -> copy(favorite = value)
        is SpatialFinSeason -> copy(favorite = value)
        is SpatialFinFolder -> copy(favorite = value)
        is SpatialFinCollection -> copy(favorite = value)
        is SpatialFinBoxSet -> copy(favorite = value)
        else -> this
    }

internal fun canPlayFromVoiceSearch(item: SpatialFinItem): Boolean {
    return item.canPlay &&
        (item is SpatialFinMovie || item is SpatialFinEpisode || item is SpatialFinSeason || item is SpatialFinShow)
}
