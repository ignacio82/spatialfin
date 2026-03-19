package dev.jdtech.jellyfin.presentation.film

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.core.graphics.toColorInt
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.player.xr.StereoModeDetector
import dev.jdtech.jellyfin.player.xr.XrPlayerActivity
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderAction
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderEvent
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderState
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderViewModel
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.core.presentation.dummy.dummyVideoMetadata
import dev.jdtech.jellyfin.film.presentation.movie.MovieAction
import dev.jdtech.jellyfin.film.presentation.movie.MovieState
import dev.jdtech.jellyfin.film.presentation.movie.MovieViewModel
import dev.jdtech.jellyfin.presentation.film.components.ActorsRow
import dev.jdtech.jellyfin.presentation.film.components.ExtraInfoText
import dev.jdtech.jellyfin.presentation.film.components.InfoText
import dev.jdtech.jellyfin.presentation.film.components.ItemButtonsBar
import dev.jdtech.jellyfin.presentation.film.components.ItemHeader
import dev.jdtech.jellyfin.presentation.film.components.ItemTopBar
import dev.jdtech.jellyfin.presentation.film.components.OverviewText
import dev.jdtech.jellyfin.presentation.film.components.VideoMetadataBar
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.spatialfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.LocalOfflineMode
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding
import dev.jdtech.jellyfin.utils.ObserveAsEvents
import dev.jdtech.jellyfin.models.versionChipLabel
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind

@Composable
fun MovieScreen(
    movieId: UUID,
    navigateBack: () -> Unit,
    navigateHome: () -> Unit,
    navigateToPerson: (personId: UUID) -> Unit,
    viewModel: MovieViewModel = hiltViewModel(),
    downloaderViewModel: DownloaderViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val isOfflineMode = LocalOfflineMode.current

    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloaderState by downloaderViewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadMovie(movieId = movieId) }

    LaunchedEffect(state.movie) { state.movie?.let { movie -> downloaderViewModel.update(movie) } }

    ObserveAsEvents(downloaderViewModel.events) { event ->
        when (event) {
            is DownloaderEvent.Successful -> {
                viewModel.loadMovie(movieId = movieId)
            }
            is DownloaderEvent.Deleted -> {
                if (isOfflineMode) {
                    navigateBack()
                } else {
                    viewModel.loadMovie(movieId = movieId)
                }
            }
        }
    }

    val appPreferences = viewModel.appPreferences
    val initialMaxBitrate = androidx.compose.runtime.remember { appPreferences.getValue(appPreferences.playerMaxBitrate) }

    MovieScreenLayout(
        state = state,
        downloaderState = downloaderState,
        initialMaxBitrate = initialMaxBitrate,
        onAction = { action ->
            when (action) {
                is MovieAction.Play -> {
                    val movie = state.movie
                    val sourceNames = movie?.sources?.flatMap { listOf(it.name, it.path) } ?: emptyList()
                    val stereoMode = if (movie != null) {
                        StereoModeDetector.detect(movie.name, movie.video3DFormat, sourceNames)
                    } else {
                        StereoModeDetector.StereoMode.MONO
                    }
                    val targetActivity = XrPlayerActivity::class.java
                    val intent = Intent(context, targetActivity)
                    intent.putExtra("itemId", (movie?.id ?: movieId).toString())
                    intent.putExtra("itemKind", BaseItemKind.MOVIE.serialName)
                    intent.putExtra("startFromBeginning", action.startFromBeginning)
                    action.mediaSourceIndex?.let { intent.putExtra("mediaSourceIndex", it) }
                    action.maxBitrate?.let { intent.putExtra("maxBitrate", it) }
                    if (true) {
                        val stereoModeStr = when (stereoMode) {
                            StereoModeDetector.StereoMode.SIDE_BY_SIDE -> "sbs"
                            StereoModeDetector.StereoMode.TOP_BOTTOM -> "top_bottom"
                            StereoModeDetector.StereoMode.MULTIVIEW -> "multiview"
                            else -> "mono"
                        }
                        intent.putExtra("stereoMode", stereoModeStr)
                    }
                    context.startActivity(intent)
                }
                is MovieAction.PlayTrailer -> {
                    try {
                        uriHandler.openUri(action.trailer)
                    } catch (e: IllegalArgumentException) {
                        Toast.makeText(context, e.localizedMessage, Toast.LENGTH_SHORT).show()
                    }
                }
                is MovieAction.OnBackClick -> navigateBack()
                is MovieAction.OnHomeClick -> navigateHome()
                is MovieAction.NavigateToPerson -> navigateToPerson(action.personId)
                is MovieAction.SelectVersion -> viewModel.loadMovie(action.movieId)
                else -> Unit
            }
            viewModel.onAction(action)
        },
        onDownloaderAction = { action -> downloaderViewModel.onAction(action) },
    )
}

@Composable
private fun MovieScreenLayout(
    state: MovieState,
    downloaderState: DownloaderState,
    initialMaxBitrate: Long,
    onAction: (MovieAction) -> Unit,
    onDownloaderAction: (DownloaderAction) -> Unit,
) {
    val safePadding = rememberSafePadding()

    val paddingStart = safePadding.start + MaterialTheme.spacings.default
    val paddingEnd = safePadding.end + MaterialTheme.spacings.default
    val paddingBottom = safePadding.bottom + MaterialTheme.spacings.default

    val scrollState = rememberScrollState()
    val context = androidx.compose.ui.platform.LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        state.movie?.let { movie ->
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(scrollState)) {
                ItemHeader(
                    item = movie,
                    scrollState = scrollState,
                    content = {
                        Column(
                            modifier =
                                Modifier.align(Alignment.BottomStart)
                                    .padding(start = paddingStart, end = paddingEnd)
                        ) {
                            Text(
                                text = movie.name,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 3,
                                style = MaterialTheme.typography.displaySmall,
                            )
                            movie.originalTitle?.let { originalTitle ->
                                if (originalTitle != movie.name) {
                                    Text(
                                        text = originalTitle,
                                        overflow = TextOverflow.Ellipsis,
                                        maxLines = 1,
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                }
                            }
                        }
                    },
                )
                Column(modifier = Modifier.padding(start = paddingStart, end = paddingEnd)) {
                    Spacer(Modifier.height(MaterialTheme.spacings.small))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        movie.premiereDate?.let { premiereDate ->
                            Text(
                                text = premiereDate.year.toString(),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Text(
                            text =
                                stringResource(
                                    CoreR.string.runtime_minutes,
                                    movie.runtimeTicks.div(600000000),
                                ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        movie.officialRating?.let { officialRating ->
                            Text(text = officialRating, style = MaterialTheme.typography.titleMedium)
                        }
                        movie.communityRating?.let { communityRating ->
                            Row(verticalAlignment = Alignment.Bottom) {
                                Icon(
                                    painter = painterResource(CoreR.drawable.ic_star),
                                    contentDescription = null,
                                    tint = Color("#F2C94C".toColorInt()),
                                )
                                Spacer(Modifier.width(MaterialTheme.spacings.extraSmall))
                                Text(
                                    text = "%.1f".format(communityRating),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(MaterialTheme.spacings.small))
                    state.videoMetadata?.let { videoMetadata ->
                        VideoMetadataBar(videoMetadata)
                        Spacer(Modifier.height(MaterialTheme.spacings.small))
                    }

                    if (state.availableVersions.size > 1) {
                        Text(
                            text = "Version",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(MaterialTheme.spacings.extraSmall))
                        LazyRow(
                            horizontalArrangement =
                                Arrangement.spacedBy(MaterialTheme.spacings.small),
                        ) {
                            items(state.availableVersions, key = { it.id }) { version ->
                                FilterChip(
                                    selected = version.id == movie.id,
                                    onClick = {
                                        if (version.id != movie.id) {
                                            onAction(MovieAction.SelectVersion(version.id))
                                        }
                                    },
                                    label = { Text(version.versionChipLabel()) },
                                )
                            }
                        }
                        Spacer(Modifier.height(MaterialTheme.spacings.small))
                    }

                    ItemButtonsBar(
                        item = movie,
                        downloaderState = downloaderState,
                        initialMaxBitrate = initialMaxBitrate,
                        onSyncPlayClick = {
                            val sourceNames = movie.sources.flatMap { listOf(it.name, it.path) }
                            val stereoMode = StereoModeDetector.detect(movie.name, movie.video3DFormat, sourceNames)
                            val intent = XrPlayerActivity.createIntent(
                                context = context,
                                itemId = movie.id,
                                itemKind = BaseItemKind.MOVIE.serialName,
                                startFromBeginning = false,
                                stereoMode =
                                    when (stereoMode) {
                                        StereoModeDetector.StereoMode.SIDE_BY_SIDE -> "sbs"
                                        StereoModeDetector.StereoMode.TOP_BOTTOM -> "top_bottom"
                                        StereoModeDetector.StereoMode.MULTIVIEW -> "multiview"
                                        else -> "mono"
                                    },
                                openSyncPlayDialogOnStart = true,
                            )
                            context.startActivity(intent)
                        },
                        onPlayClick = { startFromBeginning, mediaSourceIndex, maxBitrate ->
                            onAction(
                                MovieAction.Play(
                                    startFromBeginning = startFromBeginning,
                                    mediaSourceIndex = mediaSourceIndex,
                                    maxBitrate = maxBitrate
                                )
                            )
                        },
                        onMarkAsPlayedClick = {
                            when (movie.played) {
                                true -> onAction(MovieAction.UnmarkAsPlayed)
                                false -> onAction(MovieAction.MarkAsPlayed)
                            }
                        },
                        onMarkAsFavoriteClick = {
                            when (movie.favorite) {
                                true -> onAction(MovieAction.UnmarkAsFavorite)
                                false -> onAction(MovieAction.MarkAsFavorite)
                            }
                        },
                        onTrailerClick = { uri -> onAction(MovieAction.PlayTrailer(uri)) },
                        onDownloadClick = { request ->
                            onDownloaderAction(DownloaderAction.Download(movie, request))
                        },
                        onDownloadCancelClick = {
                            onDownloaderAction(DownloaderAction.CancelDownload(movie))
                        },
                        onDownloadDeleteClick = {
                            onDownloaderAction(DownloaderAction.DeleteDownload(movie))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(MaterialTheme.spacings.small))
                    if (state.displayExtraInfo && state.videoMetadata != null) {
                        ExtraInfoText(videoMetadata = state.videoMetadata!!)
                        Spacer(Modifier.height(MaterialTheme.spacings.medium))
                    }
                    OverviewText(text = movie.overview, maxCollapsedLines = 5)
                    Spacer(Modifier.height(MaterialTheme.spacings.medium))
                    InfoText(
                        genres = movie.genres,
                        director = state.director,
                        writers = state.writers,
                    )
                    Spacer(Modifier.height(MaterialTheme.spacings.medium))
                }
                if (state.actors.isNotEmpty()) {
                    ActorsRow(
                        actors = state.actors,
                        onActorClick = { personId ->
                            onAction(MovieAction.NavigateToPerson(personId))
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
            onBackClick = { onAction(MovieAction.OnBackClick) },
            onHomeClick = { onAction(MovieAction.OnHomeClick) },
        )
    }
}

@PreviewScreenSizes
@Composable
private fun EpisodeScreenLayoutPreview() {
    SpatialFinTheme {
        MovieScreenLayout(
            state = MovieState(movie = dummyMovie, videoMetadata = dummyVideoMetadata),
            downloaderState = DownloaderState(),
            initialMaxBitrate = 0L,
            onAction = {},
            onDownloaderAction = {},
        )
    }
}
