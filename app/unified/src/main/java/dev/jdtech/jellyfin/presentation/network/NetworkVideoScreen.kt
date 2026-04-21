package dev.jdtech.jellyfin.presentation.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.NetworkVideoItem
import dev.jdtech.jellyfin.models.SpatialFinItemPerson
import dev.jdtech.jellyfin.models.SpatialFinItemPersonImage
import dev.jdtech.jellyfin.presentation.film.components.DetailMetadataRow
import dev.jdtech.jellyfin.presentation.film.components.Direction
import dev.jdtech.jellyfin.presentation.film.components.InfoText
import dev.jdtech.jellyfin.presentation.film.components.ItemHeader
import dev.jdtech.jellyfin.presentation.film.components.ItemPoster
import dev.jdtech.jellyfin.presentation.film.components.OverviewText
import dev.jdtech.jellyfin.presentation.film.components.RatingsRow
import dev.jdtech.jellyfin.presentation.film.components.XrBrowseHeader
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding
import dev.jdtech.jellyfin.player.xr.StereoModeDetector
import dev.jdtech.jellyfin.player.xr.XrPlayerActivity
import dev.spatialfin.presentation.theme.spacings
import java.util.UUID
import org.jellyfin.sdk.model.api.PersonKind

@Composable
fun NetworkVideoScreen(
    videoId: String,
    navigateBack: () -> Unit,
    viewModel: NetworkVideoViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(videoId) { viewModel.load(videoId) }

    NetworkVideoScreenLayout(
        state = state,
        onBack = navigateBack,
        onPlay = { startFromBeginning, multitask ->
            val item = state.item
            val stereoMode = if (item != null) {
                StereoModeDetector.detect(item.name, null, listOf(item.fileName))
            } else {
                StereoModeDetector.StereoMode.MONO
            }
            val stereoModeStr = when (stereoMode) {
                StereoModeDetector.StereoMode.SIDE_BY_SIDE -> "sbs"
                StereoModeDetector.StereoMode.TOP_BOTTOM -> "top_bottom"
                StereoModeDetector.StereoMode.MULTIVIEW -> "multiview"
                else -> "mono"
            }
            if (multitask) {
                context.startActivity(
                    dev.jdtech.jellyfin.player.xr.MultitaskPlayerActivity.createIntentForNetworkMedia(
                        context = context,
                        networkVideoId = videoId,
                        startFromBeginning = startFromBeginning,
                    )
                )
            } else {
                context.startActivity(
                    XrPlayerActivity.createIntentForNetworkMedia(
                        context = context,
                        networkVideoId = videoId,
                        startFromBeginning = startFromBeginning,
                        stereoMode = stereoModeStr,
                    )
                )
            }
        },
        onTogglePlayed = { played -> viewModel.markPlayed(videoId, played) },
    )
}

@Composable
private fun NetworkVideoScreenLayout(
    state: NetworkVideoState,
    onBack: () -> Unit,
    onPlay: (Boolean, Boolean) -> Unit,
    onTogglePlayed: (Boolean) -> Unit,
) {
    val safePadding = rememberSafePadding()
    val scrollState = rememberScrollState()
    val paddingStart = safePadding.start + MaterialTheme.spacings.default
    val paddingEnd = safePadding.end + MaterialTheme.spacings.default

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            state.item == null -> {
                Text(
                    stringResource(CoreR.string.network_video_missing),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            else -> {
                val item = state.item
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(scrollState),
                ) {
                    ItemHeader(
                        item = item,
                        scrollState = scrollState,
                        content = {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(start = paddingStart, end = paddingEnd),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                            ) {
                                ItemPoster(
                                    item = item,
                                    direction = Direction.VERTICAL,
                                    modifier = Modifier.width(150.dp),
                                )
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(bottom = MaterialTheme.spacings.extraSmall),
                                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                                ) {
                                    Text(
                                        text = item.name,
                                        overflow = TextOverflow.Ellipsis,
                                        maxLines = 2,
                                        style = MaterialTheme.typography.displaySmall,
                                        color = Color.White,
                                    )
                                    DetailMetadataRow(items = buildNetworkVideoMetadata(item))
                                    if (item.ratings.isNotEmpty()) {
                                        RatingsRow(ratings = item.ratings)
                                    }
                                    item.overview.takeIf { it.isNotBlank() }?.let { overview ->
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
                    Column(
                        modifier = Modifier.padding(start = paddingStart, end = paddingEnd),
                    ) {
                        Spacer(Modifier.height(MaterialTheme.spacings.small))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilledTonalButton(onClick = { onPlay(false, false) }) {
                                Text(stringResource(CoreR.string.play))
                            }
                            FilledTonalButton(onClick = { onPlay(false, true) }) {
                                Text("Multitask")
                            }
                            if (item.playbackPositionTicks > 0) {
                                FilledTonalButton(onClick = { onPlay(true, false) }) {
                                    Text(stringResource(CoreR.string.local_restart))
                                }
                            }
                            Button(onClick = { onTogglePlayed(!item.played) }) {
                                Text(
                                    if (item.played) stringResource(CoreR.string.unmark_as_played)
                                    else stringResource(CoreR.string.mark_as_played),
                                )
                            }
                        }
                        Spacer(Modifier.height(MaterialTheme.spacings.medium))
                        if (item.overview.isNotBlank()) {
                            OverviewText(text = item.overview, maxCollapsedLines = 5)
                            Spacer(Modifier.height(MaterialTheme.spacings.medium))
                        }
                        if (item.genres.isNotEmpty() || item.director != null || item.writers.isNotEmpty()) {
                            InfoText(
                                genres = item.genres,
                                director = item.director?.let { name ->
                                    SpatialFinItemPerson(
                                        id = UUID.nameUUIDFromBytes("director:$name".toByteArray()),
                                        name = name,
                                        type = PersonKind.DIRECTOR,
                                        role = "",
                                        image = SpatialFinItemPersonImage(uri = null, blurHash = null),
                                    )
                                },
                                writers = item.writers.map { name ->
                                    SpatialFinItemPerson(
                                        id = UUID.nameUUIDFromBytes("writer:$name".toByteArray()),
                                        name = name,
                                        type = PersonKind.WRITER,
                                        role = "",
                                        image = SpatialFinItemPersonImage(uri = null, blurHash = null),
                                    )
                                },
                            )
                            Spacer(Modifier.height(MaterialTheme.spacings.medium))
                        }
                        Text(
                            text = item.fileName,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(safePadding.bottom + MaterialTheme.spacings.large))
                    }
                }
            }
        }

        // Navigation header overlaid on top
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = paddingStart,
                    top = safePadding.top + MaterialTheme.spacings.default,
                    end = paddingEnd,
                ),
        ) {
            XrBrowseHeader(
                title = if (state.item != null) "" else stringResource(CoreR.string.title_network),
                onBackClick = onBack,
            )
        }
    }
}

private fun buildNetworkVideoMetadata(item: NetworkVideoItem): List<String> =
    buildList {
        item.releaseYear?.let { add(it.toString()) }
        if (item.seasonNumber != null && item.episodeNumber != null) {
            add("S${item.seasonNumber} E${item.episodeNumber}")
        }
        if (item.runtimeTicks > 0) {
            add("${item.runtimeTicks / 600_000_000L} min")
        }
        item.genres.take(2).forEach { add(it) }
    }
