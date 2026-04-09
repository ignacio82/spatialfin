package dev.jdtech.jellyfin.presentation.film

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.player.xr.XrPlayerActivity
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.show.ShowAction
import dev.jdtech.jellyfin.film.presentation.show.ShowState
import dev.jdtech.jellyfin.film.presentation.show.ShowViewModel
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.presentation.film.components.ActorsRow
import dev.jdtech.jellyfin.presentation.film.components.DetailMetadataRow
import dev.jdtech.jellyfin.presentation.film.components.Direction
import dev.jdtech.jellyfin.presentation.film.components.InfoText
import dev.jdtech.jellyfin.presentation.film.components.ItemButtonsBar
import dev.jdtech.jellyfin.presentation.film.components.ItemCard
import dev.jdtech.jellyfin.presentation.film.components.ItemHeader
import dev.jdtech.jellyfin.presentation.film.components.ItemPoster
import dev.jdtech.jellyfin.presentation.film.components.ItemTopBar
import dev.jdtech.jellyfin.presentation.film.components.OverviewText
import dev.jdtech.jellyfin.presentation.film.components.RatingsRow
import dev.spatialfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding
import dev.jdtech.jellyfin.utils.getShowDateString
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind

@Composable
fun ShowScreen(
    showId: UUID,
    navigateBack: () -> Unit,
    navigateHome: () -> Unit,
    navigateToItem: (item: SpatialFinItem) -> Unit,
    navigateToPerson: (personId: UUID) -> Unit,
    viewModel: ShowViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadShow(showId = showId) }

    ShowScreenLayout(
        state = state,
        onAction = { action ->
            when (action) {
                is ShowAction.Play -> {
                    val targetActivity = if (action.multitask) {
                        dev.jdtech.jellyfin.player.xr.MultitaskPlayerActivity::class.java
                    } else {
                        XrPlayerActivity::class.java
                    }
                    val intent = Intent(context, targetActivity)
                    intent.putExtra("itemId", showId.toString())
                    intent.putExtra("itemKind", BaseItemKind.SERIES.serialName)
                    if (!action.multitask) {
                        intent.putExtra("stereoMode", "mono")
                    }
                    context.startActivity(intent)
                }
                is ShowAction.PlayTrailer -> {
                    try {
                        uriHandler.openUri(action.trailer)
                    } catch (e: IllegalArgumentException) {
                        Toast.makeText(context, e.localizedMessage, Toast.LENGTH_SHORT).show()
                    }
                }
                is ShowAction.OnBackClick -> navigateBack()
                is ShowAction.OnHomeClick -> navigateHome()
                is ShowAction.NavigateToItem -> navigateToItem(action.item)
                is ShowAction.NavigateToPerson -> navigateToPerson(action.personId)
                else -> Unit
            }
            viewModel.onAction(action)
        },
    )
}

@Composable
private fun ShowScreenLayout(state: ShowState, onAction: (ShowAction) -> Unit) {
    val safePadding = rememberSafePadding()

    val paddingStart = safePadding.start + MaterialTheme.spacings.default
    val paddingEnd = safePadding.end + MaterialTheme.spacings.default
    val paddingBottom = safePadding.bottom + MaterialTheme.spacings.default

    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {
        state.show?.let { show ->
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(scrollState)) {
                ItemHeader(
                    item = show,
                    scrollState = scrollState,
                    content = {
                        Row(
                            modifier =
                                Modifier.align(Alignment.BottomStart)
                                    .padding(start = paddingStart, end = paddingEnd),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                        ) {
                            ItemPoster(
                                item = show,
                                direction = Direction.VERTICAL,
                                modifier = Modifier.width(144.dp).clip(MaterialTheme.shapes.medium),
                            )
                            Column(
                                modifier = Modifier.weight(1f).padding(bottom = MaterialTheme.spacings.extraSmall),
                                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                            ) {
                                Text(
                                    text = show.name,
                                    overflow = TextOverflow.Ellipsis,
                                    maxLines = 2,
                                    style = MaterialTheme.typography.displaySmall,
                                    color = Color.White,
                                )
                                show.originalTitle?.let { originalTitle ->
                                    if (originalTitle != show.name) {
                                        Text(
                                            text = originalTitle,
                                            overflow = TextOverflow.Ellipsis,
                                            maxLines = 1,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = Color.White.copy(alpha = 0.86f),
                                        )
                                    }
                                }
                                DetailMetadataRow(items = buildShowHeroMetadata(show, state.seasons))
                                if (state.displayRatings && show.ratings.isNotEmpty()) {
                                    RatingsRow(ratings = show.ratings)
                                }
                                show.overview.takeIf { it.isNotBlank() }?.let { overview ->
                                    Text(
                                        text = overview,
                                        overflow = TextOverflow.Ellipsis,
                                        maxLines = 3,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = Color.White.copy(alpha = 0.9f),
                                    )
                                }
                            }
                        }
                    },
                )
                Column(modifier = Modifier.padding(start = paddingStart, end = paddingEnd)) {
                    Spacer(Modifier.height(MaterialTheme.spacings.small))
                    ItemButtonsBar(
                        item = show,
                        onPlayClick = { startFromBeginning, _, _, multitask ->
                            onAction(ShowAction.Play(startFromBeginning = startFromBeginning, multitask = multitask))
                        },
                        onSyncPlayClick = null,
                        onMarkAsPlayedClick = {
                            when (show.played) {
                                true -> onAction(ShowAction.UnmarkAsPlayed)
                                false -> onAction(ShowAction.MarkAsPlayed)
                            }
                        },
                        onMarkAsFavoriteClick = {
                            when (show.favorite) {
                                true -> onAction(ShowAction.UnmarkAsFavorite)
                                false -> onAction(ShowAction.MarkAsFavorite)
                            }
                        },
                        onTrailerClick = { uri -> onAction(ShowAction.PlayTrailer(uri)) },
                        onDownloadClick = {},
                        onDownloadCancelClick = {},
                        onDownloadDeleteClick = {},
                        modifier = Modifier.fillMaxWidth(),
                        canPlay = state.seasons.isNotEmpty(),
                    )
                    Spacer(Modifier.height(MaterialTheme.spacings.small))
                }

                if (state.seasons.isNotEmpty()) {
                    Column(modifier = Modifier.padding(start = paddingStart, end = paddingEnd)) {
                        Text(
                            text = stringResource(CoreR.string.seasons),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(MaterialTheme.spacings.small))
                    }
                    LazyRow(
                        contentPadding = PaddingValues(start = paddingStart, end = paddingEnd),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
                    ) {
                        items(items = state.seasons, key = { item -> item.id }) { season ->
                            ItemCard(
                                item = season,
                                direction = Direction.VERTICAL,
                                displayRatings = state.displayRatings,
                                onClick = { onAction(ShowAction.NavigateToItem(season)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(MaterialTheme.spacings.medium))
                }

                Column(modifier = Modifier.padding(start = paddingStart, end = paddingEnd)) {
                    state.nextUp?.let { nextUp ->
                        Text(
                            text = stringResource(CoreR.string.next_up),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(MaterialTheme.spacings.small))
                        Column(
                            modifier =
                                Modifier.widthIn(max = 420.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable { onAction(ShowAction.NavigateToItem(nextUp)) }
                        ) {
                            ItemPoster(
                                item = nextUp,
                                direction = Direction.HORIZONTAL,
                                modifier = Modifier.clip(MaterialTheme.shapes.medium),
                            )
                            Spacer(Modifier.height(MaterialTheme.spacings.extraSmall))
                            Text(
                                text =
                                    stringResource(
                                        id = CoreR.string.episode_name_extended,
                                        nextUp.parentIndexNumber,
                                        nextUp.indexNumber,
                                        nextUp.name,
                                    ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(Modifier.height(MaterialTheme.spacings.medium))
                    }
                    OverviewText(text = show.overview, maxCollapsedLines = 3)
                    Spacer(Modifier.height(MaterialTheme.spacings.medium))
                    InfoText(
                        genres = show.genres,
                        director = state.director,
                        writers = state.writers,
                    )
                    Spacer(Modifier.height(MaterialTheme.spacings.medium))
                }

                if (state.actors.isNotEmpty()) {
                    ActorsRow(
                        actors = state.actors,
                        onActorClick = { personId ->
                            onAction(ShowAction.NavigateToPerson(personId))
                        },
                        contentPadding = PaddingValues(start = paddingStart, end = paddingEnd),
                    )
                }
                Spacer(Modifier.height(paddingBottom))
            }
        } ?: run { CircularProgressIndicator(modifier = Modifier.align(Alignment.Center)) }

        ItemTopBar(
            hasBackButton = true,
            hasHomeButton = true,
            onBackClick = { onAction(ShowAction.OnBackClick) },
            onHomeClick = { onAction(ShowAction.OnHomeClick) },
        )
    }
}

private fun buildShowHeroMetadata(
    show: SpatialFinShow,
    seasons: List<dev.jdtech.jellyfin.models.SpatialFinSeason>,
): List<String> =
    buildList {
        getShowDateString(show).takeIf { it.isNotBlank() }?.let(::add)
        if (show.runtimeTicks > 0L) {
            add("${show.runtimeTicks.div(600000000)} min")
        }
        if (seasons.isNotEmpty()) {
            add("${seasons.size} seasons")
        }
        show.officialRating?.takeIf { it.isNotBlank() }?.let(::add)
        show.communityRating?.let { add("${"%.1f".format(it)}/10") }
        show.unplayedItemCount?.takeIf { it > 0 }?.let { add("$it unwatched") }
    }
