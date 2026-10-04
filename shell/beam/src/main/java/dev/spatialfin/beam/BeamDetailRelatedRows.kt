package dev.spatialfin.beam

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.components.FloatingProgressBar
import dev.jdtech.jellyfin.film.domain.initialScrollIndexFor
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.presentation.film.components.moreFromSeasonTitle

/**
 * Rows under the Beam detail hero that lead somewhere else: the episode's
 * season and "More like this". Loaded through the shared
 * `loadDetailRelatedRows`, so Beam offers the same rows as XR and TV.
 */
@Composable
internal fun BeamDetailSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
internal fun BeamSeasonEpisodesRow(
    current: SpatialFinEpisode,
    episodes: List<SpatialFinEpisode>,
    onOpenEpisode: (SpatialFinEpisode) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        BeamDetailSectionLabel(moreFromSeasonTitle(current))
        LazyRow(
            state = rememberLazyListState(
                initialFirstVisibleItemIndex = episodes.initialScrollIndexFor(current.id),
            ),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(episodes, key = { it.id }) { episode ->
                BeamSeasonEpisodeCard(
                    episode = episode,
                    isCurrent = episode.id == current.id,
                    onClick = { onOpenEpisode(episode) },
                )
            }
        }
    }
}

@Composable
private fun BeamSeasonEpisodeCard(
    episode: SpatialFinEpisode,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier.width(196.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(
                    if (isCurrent) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                    else Modifier
                )
                .clickable(enabled = !isCurrent, onClick = onClick),
        ) {
            val still = episode.images.primary ?: episode.images.showBackdrop ?: episode.images.backdrop
            if (still != null) {
                AsyncImage(
                    model = still,
                    contentDescription = episode.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            buildPlaybackFraction(episode)?.let { progress ->
                FloatingProgressBar(
                    progress = progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .align(Alignment.BottomCenter),
                    progressColor = MaterialTheme.colorScheme.primary,
                )
            }
            if (episode.played) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            if (isCurrent) {
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Text(
                        text = stringResource(CoreR.string.now_viewing),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
        Text(
            text = episode.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = listOfNotNull(
                stringResource(CoreR.string.episode_number, episode.indexNumber),
                formatRuntime(episode.runtimeTicks),
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
internal fun BeamSimilarRow(
    items: List<SpatialFinItem>,
    onItemClick: (SpatialFinItem) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        BeamDetailSectionLabel(stringResource(CoreR.string.more_like_this))
        BeamPosterCarousel(
            items = items,
            onItemClick = onItemClick,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}
