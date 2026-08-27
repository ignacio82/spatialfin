package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.R as FilmR
import dev.jdtech.jellyfin.film.presentation.home.HomeAction
import dev.jdtech.jellyfin.models.SpatialFinCollection
import dev.jdtech.jellyfin.models.SpatialFinImages
import dev.jdtech.jellyfin.models.HomeItem
import dev.jdtech.jellyfin.models.deduplicateMovieVersions
import dev.jdtech.jellyfin.presentation.components.HomeRowArrangeSlot
import dev.jdtech.jellyfin.presentation.components.HomeRowArrangeState
import dev.jdtech.jellyfin.presentation.components.homeRowArrangeHandle
import dev.spatialfin.presentation.theme.spacings

@Composable
fun HomeView(
    view: HomeItem.ViewItem,
    displayRatings: Boolean = true,
    itemsPadding: PaddingValues,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
    arrangeState: HomeRowArrangeState? = null,
) {
    val visibleItems = view.view.items.deduplicateMovieVersions()
    Column(modifier = modifier) {
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp).padding(itemsPadding)) {
            // Shelf title — design's accent tick + title.
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
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
                    text = stringResource(FilmR.string.latest_library, view.view.name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (arrangeState?.isArranging == true) {
                HomeRowArrangeSlot(arrangeState, modifier = Modifier.align(Alignment.CenterEnd))
            } else {
                IconButton(
                    onClick = {
                        onAction(
                            HomeAction.OnLibraryClick(
                                SpatialFinCollection(
                                    id = view.view.id,
                                    name = view.view.name,
                                    images = SpatialFinImages(),
                                    type = view.view.type,
                                )
                            )
                        )
                    },
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_arrow_right),
                        contentDescription = null,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.extraSmall))
        LazyRow(
            contentPadding = itemsPadding,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
        ) {
            items(visibleItems, key = { it.id }) { item ->
                ItemCard(
                    item = item,
                    direction = Direction.VERTICAL,
                    displayRatings = displayRatings,
                    onClick = { onAction(HomeAction.OnItemClick(item)) },
                )
            }
        }
    }
}
