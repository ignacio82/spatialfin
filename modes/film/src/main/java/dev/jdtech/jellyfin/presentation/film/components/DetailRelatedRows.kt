package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.domain.initialScrollIndexFor
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.models.isDownloading
import dev.spatialfin.presentation.theme.spacings

/** Accent-bar section title shared by the rows under a detail hero. */
@Composable
fun DetailSectionHeader(title: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** "More from Season 2" — uses the server's season name so "Specials" reads right. */
@Composable
fun moreFromSeasonTitle(episode: SpatialFinEpisode): String =
    stringResource(
        CoreR.string.more_from_season,
        episode.seasonName?.takeIf { it.isNotBlank() }
            ?: stringResource(CoreR.string.season_number, episode.parentIndexNumber),
    )

/**
 * The episode's season as a row of stills, opened on the current episode and
 * marking it — the quick way to hop to a sibling without backing out to the
 * season screen.
 */
@Composable
fun SeasonEpisodesRow(
    current: SpatialFinEpisode,
    episodes: List<SpatialFinEpisode>,
    onEpisodeClick: (SpatialFinEpisode) -> Unit,
    contentPadding: PaddingValues,
) {
    DetailSectionHeader(
        title = moreFromSeasonTitle(current),
        modifier = Modifier.padding(contentPadding),
    )
    Spacer(Modifier.height(MaterialTheme.spacings.small))
    val listState =
        rememberLazyListState(initialFirstVisibleItemIndex = episodes.initialScrollIndexFor(current.id))
    LazyRow(
        state = listState,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
    ) {
        items(items = episodes, key = { it.id }) { episode ->
            SeasonEpisodeCard(
                episode = episode,
                isCurrent = episode.id == current.id,
                onClick = { onEpisodeClick(episode) },
            )
        }
    }
}

@Composable
private fun SeasonEpisodeCard(
    episode: SpatialFinEpisode,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val shape = MaterialTheme.shapes.small
    Column(
        modifier = Modifier
            .width(280.dp)
            .clip(shape)
            .clickable(enabled = !isCurrent, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .clip(shape)
                .then(
                    if (isCurrent) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                    else Modifier
                ),
        ) {
            ItemPoster(item = episode, direction = Direction.HORIZONTAL)
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(MaterialTheme.spacings.small),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
            ) {
                when {
                    episode.isDownloaded() -> DownloadedBadge()
                    episode.isDownloading() -> DownloadingBadge()
                }
                if (episode.played) PlayedBadge()
            }
            if (isCurrent) {
                NowViewingTag(
                    modifier = Modifier.align(Alignment.TopStart).padding(MaterialTheme.spacings.small),
                )
            }
        }
        Spacer(Modifier.height(MaterialTheme.spacings.extraSmall))
        Text(
            text = episode.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = episodeSubtitle(episode),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun episodeSubtitle(episode: SpatialFinEpisode): String {
    val number = stringResource(CoreR.string.episode_number, episode.indexNumber)
    val minutes = (episode.runtimeTicks / 600_000_000L).toInt()
    return if (minutes > 0) {
        "$number · " + stringResource(CoreR.string.runtime_minutes, minutes)
    } else {
        number
    }
}

@Composable
private fun NowViewingTag(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(99.dp),
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

/** "More like this" — server-side similar movies or series as a poster row. */
@Composable
fun SimilarItemsRow(
    items: List<SpatialFinItem>,
    onItemClick: (SpatialFinItem) -> Unit,
    contentPadding: PaddingValues,
    displayRatings: Boolean = true,
) {
    DetailSectionHeader(
        title = stringResource(CoreR.string.more_like_this),
        modifier = Modifier.padding(contentPadding),
    )
    Spacer(Modifier.height(MaterialTheme.spacings.small))
    LazyRow(
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
    ) {
        items(items = items, key = { it.id }) { item ->
            ItemCard(
                item = item,
                direction = Direction.VERTICAL,
                displayRatings = displayRatings,
                onClick = onItemClick,
            )
        }
    }
}
