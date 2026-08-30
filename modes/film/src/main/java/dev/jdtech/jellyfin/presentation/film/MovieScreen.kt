package dev.jdtech.jellyfin.presentation.film

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.player.core.ProjectionModeDetector
import dev.jdtech.jellyfin.player.core.StereoModeDetector
import dev.jdtech.jellyfin.presentation.player.PlayRequest
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderAction
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderEvent
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderState
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderViewModel
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.core.presentation.dummy.dummyVideoMetadata
import dev.jdtech.jellyfin.film.presentation.movie.MovieAction
import dev.jdtech.jellyfin.film.presentation.movie.MovieState
import dev.jdtech.jellyfin.film.presentation.movie.MovieViewModel
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.presentation.film.components.ActorsRow
import dev.jdtech.jellyfin.film.domain.LanguagePreferences
import dev.jdtech.jellyfin.film.domain.detailHeroMetadata
import dev.jdtech.jellyfin.film.domain.languagePreferences
import dev.jdtech.jellyfin.presentation.film.components.DetailMetadataRow
import dev.jdtech.jellyfin.presentation.film.components.Direction
import dev.jdtech.jellyfin.presentation.film.components.ExtraInfoText
import dev.jdtech.jellyfin.presentation.film.components.InfoText
import dev.jdtech.jellyfin.presentation.film.components.ItemButtonsBar
import dev.jdtech.jellyfin.presentation.film.components.ItemHeader
import dev.jdtech.jellyfin.presentation.film.components.ItemPoster
import dev.jdtech.jellyfin.presentation.film.components.ItemTopBar
import dev.jdtech.jellyfin.presentation.film.components.OverviewText
import dev.jdtech.jellyfin.presentation.film.components.RatingsRow
import dev.jdtech.jellyfin.presentation.film.components.TrackSelectionChips
import dev.jdtech.jellyfin.presentation.film.components.VideoMetadataBar
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.spatialfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.LocalOfflineMode
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding
import dev.jdtech.jellyfin.utils.ObserveAsEvents
import dev.jdtech.jellyfin.models.versionChipLabel
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStreamType

@Composable
fun MovieScreen(
    movieId: UUID,
    navigateBack: () -> Unit,
    navigateHome: () -> Unit,
    navigateToPerson: (personId: UUID) -> Unit,
    onPlay: (PlayRequest) -> Unit,
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
    val languagePreferences = androidx.compose.runtime.remember(context) {
        appPreferences.languagePreferences(context)
    }

    MovieScreenLayout(
        state = state,
        downloaderState = downloaderState,
        initialMaxBitrate = initialMaxBitrate,
        languagePreferences = languagePreferences,
        onAction = { action ->
            when (action) {
                is MovieAction.Play -> {
                    val movie = state.movie
                    val sourceNames = movie?.sources?.flatMap { listOf(it.name, it.path) } ?: emptyList()
                    val sourceVideoCodecs =
                        movie?.sources?.flatMap { source ->
                            source.mediaStreams
                                .filter { it.type == MediaStreamType.VIDEO }
                                .map { it.codec }
                        } ?: emptyList()
                    val stereoMode = if (movie != null) {
                        StereoModeDetector.detect(
                            movie.name,
                            movie.video3DFormat,
                            sourceNames,
                            sourceVideoCodecs,
                        )
                    } else {
                        StereoModeDetector.StereoMode.MONO
                    }
                    // Explicit "Multitask" button → MultitaskPlayerActivity (Home Space).
                    // "Play" button → XrPlayerActivity (Full Space) when xrAutoEnterFullSpaceOnPlayback
                    //                 is true (default), otherwise MultitaskPlayerActivity.
                    val autoFullSpace = appPreferences.getValue(appPreferences.xrAutoEnterFullSpaceOnPlayback)
                    val useImmersivePlayer = !action.multitask && autoFullSpace
                    val stereoModeStr = when (stereoMode) {
                        StereoModeDetector.StereoMode.SIDE_BY_SIDE -> "sbs"
                        StereoModeDetector.StereoMode.TOP_BOTTOM -> "top_bottom"
                        StereoModeDetector.StereoMode.MULTIVIEW -> "multiview"
                        else -> "mono"
                    }
                    val projectionStr = if (movie != null) {
                        ProjectionModeDetector.asExtra(
                            ProjectionModeDetector.detect(movie.name, sourceNames),
                        )
                    } else {
                        ProjectionModeDetector.PROJECTION_FLAT
                    }
                    // FCast Split-A/V routing + immersive/multitask intent building now
                    // live in the app's playback launcher (the PlayRequest seam). Resume
                    // position (Jellyfin 100ns ticks → ms) is carried for the receiver.
                    onPlay(
                        PlayRequest.LibraryItem(
                            itemId = movie?.id ?: movieId,
                            itemKind = BaseItemKind.MOVIE.serialName,
                            item = movie,
                            startFromBeginning = action.startFromBeginning,
                            immersive = useImmersivePlayer,
                            mediaSourceIndex = action.mediaSourceIndex,
                            maxBitrate = action.maxBitrate,
                            stereoMode = stereoModeStr,
                            projection = projectionStr,
                            resumePositionMs = movie?.let {
                                if (action.startFromBeginning) 0L
                                else it.playbackPositionTicks / 10_000L
                            },
                            audioStreamIndex = action.audioStreamIndex,
                            subtitleStreamIndex = action.subtitleStreamIndex,
                            subtitlesDisabled = action.subtitlesDisabled,
                        )
                    )
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
        onPlay = onPlay,
    )
}

@Composable
private fun MovieScreenLayout(
    state: MovieState,
    downloaderState: DownloaderState,
    initialMaxBitrate: Long,
    languagePreferences: LanguagePreferences = LanguagePreferences(),
    onAction: (MovieAction) -> Unit,
    onDownloaderAction: (DownloaderAction) -> Unit,
    onPlay: (PlayRequest) -> Unit,
) {
    val safePadding = rememberSafePadding()

    val paddingStart = safePadding.start + MaterialTheme.spacings.default
    val paddingEnd = safePadding.end + MaterialTheme.spacings.default
    val paddingBottom = safePadding.bottom + MaterialTheme.spacings.default

    val scrollState = rememberScrollState()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Pre-playback track picks. Resolved through detailHeroMetadata so the chips,
    // the picker's checkmark and the Play intent all agree — see TrackSelectionChips.
    var selectedAudioStreamIndex by rememberSaveable(state.movie?.id) { mutableStateOf<Int?>(null) }
    var selectedSubtitleStreamIndex by rememberSaveable(state.movie?.id) { mutableStateOf<Int?>(null) }
    var subtitlesDisabled by rememberSaveable(state.movie?.id) { mutableStateOf(false) }
    val trackHero = state.movie?.detailHeroMetadata(
        languagePreferences = languagePreferences,
        selectedAudioStreamIndex = selectedAudioStreamIndex,
        selectedSubtitleStreamIndex = selectedSubtitleStreamIndex,
        subtitlesDisabled = subtitlesDisabled,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        state.movie?.let { movie ->
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(scrollState)) {
                ItemHeader(
                    item = movie,
                    scrollState = scrollState,
                    showLogo = true,
                    content = {
                        Row(
                            modifier =
                                Modifier.align(Alignment.BottomStart)
                                    .padding(start = paddingStart, end = paddingEnd),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                        ) {
                            ItemPoster(
                                item = movie,
                                direction = Direction.VERTICAL,
                                modifier = Modifier.width(150.dp),
                            )
                            Column(
                                modifier = Modifier.weight(1f).padding(bottom = MaterialTheme.spacings.extraSmall),
                                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                            ) {
                                Text(
                                    text = movie.name,
                                    overflow = TextOverflow.Ellipsis,
                                    maxLines = 2,
                                    style = MaterialTheme.typography.displaySmall,
                                    color = Color.White,
                                )
                                movie.originalTitle?.let { originalTitle ->
                                    if (originalTitle != movie.name) {
                                        Text(
                                            text = originalTitle,
                                            overflow = TextOverflow.Ellipsis,
                                            maxLines = 1,
                                            style = MaterialTheme.typography.titleLarge,
                                            color = Color.White.copy(alpha = 0.86f),
                                        )
                                    }
                                }
                                DetailMetadataRow(items = buildMovieHeroMetadata(movie))
                                if (state.displayRatings && movie.ratings.isNotEmpty()) {
                                    RatingsRow(ratings = movie.ratings)
                                }
                                movie.overview.takeIf { it.isNotBlank() }?.let { overview ->
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
                        item = movie,
                        downloaderState = downloaderState,
                        initialMaxBitrate = initialMaxBitrate,
                        onSyncPlayClick = {
                            val sourceNames = movie.sources.flatMap { listOf(it.name, it.path) }
                            val sourceVideoCodecs =
                                movie.sources.flatMap { source ->
                                    source.mediaStreams
                                        .filter { it.type == MediaStreamType.VIDEO }
                                        .map { it.codec }
                                }
                            val stereoMode =
                                StereoModeDetector.detect(
                                    movie.name,
                                    movie.video3DFormat,
                                    sourceNames,
                                    sourceVideoCodecs,
                                )
                            onPlay(
                                PlayRequest.LibraryItem(
                                    itemId = movie.id,
                                    itemKind = BaseItemKind.MOVIE.serialName,
                                    startFromBeginning = false,
                                    immersive = true,
                                    stereoMode =
                                        when (stereoMode) {
                                            StereoModeDetector.StereoMode.SIDE_BY_SIDE -> "sbs"
                                            StereoModeDetector.StereoMode.TOP_BOTTOM -> "top_bottom"
                                            StereoModeDetector.StereoMode.MULTIVIEW -> "multiview"
                                            else -> "mono"
                                        },
                                    projection = ProjectionModeDetector.asExtra(
                                        ProjectionModeDetector.detect(movie.name, sourceNames),
                                    ),
                                    openSyncPlayDialogOnStart = true,
                                    allowFcastRouting = false,
                                )
                            )
                        },
                        onPlayClick = { startFromBeginning, mediaSourceIndex, maxBitrate, multitask ->
                            onAction(
                                MovieAction.Play(
                                    startFromBeginning = startFromBeginning,
                                    mediaSourceIndex = mediaSourceIndex,
                                    maxBitrate = maxBitrate,
                                    multitask = multitask,
                                    audioStreamIndex = trackHero?.audioStreamIndex,
                                    subtitleStreamIndex = trackHero?.subtitleStreamIndex,
                                    subtitlesDisabled = subtitlesDisabled,
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
                        onMetadataSaved = { onAction(MovieAction.ReloadAfterMetadataEdit) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(MaterialTheme.spacings.small))
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
                    state.videoMetadata?.let { videoMetadata ->
                        VideoMetadataBar(videoMetadata)
                        Spacer(Modifier.height(MaterialTheme.spacings.small))
                    }
                    trackHero?.let { hero ->
                        TrackSelectionChips(
                            item = movie,
                            hero = hero,
                            onAudioStreamSelected = { selectedAudioStreamIndex = it },
                            onSubtitleStreamSelected = { streamIndex, disabled ->
                                selectedSubtitleStreamIndex = streamIndex
                                subtitlesDisabled = disabled
                            },
                        )
                        Spacer(Modifier.height(MaterialTheme.spacings.small))
                    }
                    if (state.displayExtraInfo && state.videoMetadata != null) {
                        ExtraInfoText(videoMetadata = state.videoMetadata)
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

/**
 * Delegates to the shared [detailHeroMetadata] so the XR hero shows the same
 * facts, in the same format, as the Beam and TV heroes — this used to be a
 * second hand-rolled list that formatted runtime as "107 min" and dropped the
 * community rating entirely. Stream chips are not included: XR renders those
 * separately through [VideoMetadataBar].
 */
private fun buildMovieHeroMetadata(movie: SpatialFinMovie): List<String> =
    movie.detailHeroMetadata(maxGenres = 2).let { hero ->
        hero.facts.map { it.label } + hero.genres
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
            onPlay = {},
        )
    }
}
