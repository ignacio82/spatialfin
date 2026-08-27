package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovies
import dev.jdtech.jellyfin.film.presentation.home.HomeAction
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.deduplicateMovieVersions
import dev.jdtech.jellyfin.presentation.components.HomeRowArrangeSlot
import dev.jdtech.jellyfin.presentation.components.HomeRowArrangeState
import dev.jdtech.jellyfin.presentation.components.homeRowArrangeHandle
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.spatialfin.presentation.theme.spacings
import kotlinx.coroutines.delay

private val dynamicPageSize =
    object : PageSize {
        override fun Density.calculateMainAxisPageSize(availableSpace: Int, pageSpacing: Int): Int {
            val nPages =
                when {
                    availableSpace.toDp() >= 840.dp -> 3
                    availableSpace.toDp() >= 600.dp -> 2
                    else -> 1
                }

            return (availableSpace - (nPages - 1) * pageSpacing) / nPages
        }
    }

/**
 * @param title when set, draws the same accent-tick shelf header the other home
 *   rows use. It doubles as the long-press handle for [arrangeState], so the
 *   suggestions row is rearrangeable like every other row on home.
 */
@Composable
fun HomeCarousel(
    items: List<SpatialFinItem>,
    displayRatings: Boolean = true,
    itemsPadding: PaddingValues,
    onAction: (HomeAction) -> Unit,
    title: String? = null,
    arrangeState: HomeRowArrangeState? = null,
) {
    val visibleItems = items.deduplicateMovieVersions()
    val pagerState = rememberPagerState(pageCount = { visibleItems.size })
    val pagerIsDragged by pagerState.interactionSource.collectIsDraggedAsState()

    val autoScrollDelay = 5000L

    if (!pagerIsDragged) {
        LaunchedEffect(pagerState) {
            while (true) {
                delay(autoScrollDelay)
                val nextPage =
                    if (pagerState.canScrollForward) {
                        pagerState.currentPage + 1
                    } else {
                        0
                    }
                pagerState.animateScrollToPage(nextPage)
            }
        }
    }

    Column {
        if (title != null) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp).padding(itemsPadding),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(8.dp))
                        .homeRowArrangeHandle(enabled = arrangeState != null) {
                            arrangeState?.onStartArranging?.invoke()
                        },
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
                HomeRowArrangeSlot(arrangeState)
            }
        }
        HorizontalPager(
            state = pagerState,
            contentPadding = itemsPadding,
            pageSize = dynamicPageSize,
            pageSpacing = MaterialTheme.spacings.medium,
        ) { page ->
            val item = visibleItems[page]
            HomeCarouselItem(item = item, displayRatings = displayRatings, onAction = onAction)
        }
    }
}

@Composable
@Preview(showBackground = true)
private fun HomeCarouselPreview() {
    SpatialFinTheme {
        HomeCarousel(
            items = dummyMovies,
            itemsPadding = PaddingValues(horizontal = 0.dp),
            onAction = {},
        )
    }
}
