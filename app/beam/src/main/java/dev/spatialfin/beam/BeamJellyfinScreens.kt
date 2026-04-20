package dev.spatialfin.beam

import android.content.Context
import dev.jdtech.jellyfin.player.beam.LocalBeamWidth
import dev.jdtech.jellyfin.player.beam.isCompact
import android.app.DownloadManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.api.SeerrApi
import dev.jdtech.jellyfin.api.SeerrMediaInfo
import dev.jdtech.jellyfin.api.SeerrSearchResult
import dev.jdtech.jellyfin.core.presentation.components.FloatingProgressBar
import dev.jdtech.jellyfin.core.presentation.downloader.BulkDownloadState
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderAction
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderEvent
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderViewModel
import dev.jdtech.jellyfin.film.presentation.home.HomeViewModel
import dev.jdtech.jellyfin.film.presentation.downloads.DownloadsViewModel
import dev.jdtech.jellyfin.film.presentation.downloads.DownloadSortOrder
import dev.jdtech.jellyfin.models.BulkDownloadSettings
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.presentation.film.components.RatingsRow
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.DownloadMode
import dev.jdtech.jellyfin.models.DownloadRequest
import dev.jdtech.jellyfin.utils.ActiveDownloadEntry
import dev.jdtech.jellyfin.utils.BulkDownloadResult
import dev.jdtech.jellyfin.utils.Downloader
import dev.jdtech.jellyfin.models.HomeItem
import dev.jdtech.jellyfin.models.SpatialFinBoxSet
import dev.jdtech.jellyfin.models.SpatialFinCollection
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinFolder
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMediaStream
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.SpatialFinSourceType
import dev.jdtech.jellyfin.models.deduplicateMovieVersions
import dev.jdtech.jellyfin.models.movieVersionGroupKey
import dev.jdtech.jellyfin.models.versionChipLabel
import dev.jdtech.jellyfin.models.versionOptionsFrom
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.models.isDownloading
import dev.jdtech.jellyfin.player.beam.BeamPlayerActivity
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.utils.getShowDateString
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import coil3.compose.AsyncImage

data class BeamLibraryState(
    val title: String = "",
    val items: List<SpatialFinItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: Throwable? = null,
)

@HiltViewModel
class BeamLibraryViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(BeamLibraryState())
    val state = _state.asStateFlow()

    fun load(parentId: UUID, title: String, type: CollectionType) {
        viewModelScope.launch {
            _state.emit(_state.value.copy(isLoading = true, error = null, title = title))
            val includeTypes =
                when (type) {
                    CollectionType.Movies -> listOf(BaseItemKind.FOLDER, BaseItemKind.MOVIE)
                    CollectionType.TvShows -> listOf(BaseItemKind.FOLDER, BaseItemKind.SERIES)
                    CollectionType.BoxSets -> listOf(BaseItemKind.FOLDER, BaseItemKind.BOX_SET)
                    CollectionType.Mixed,
                    CollectionType.Folders -> listOf(BaseItemKind.FOLDER, BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE, BaseItemKind.SEASON, BaseItemKind.BOX_SET)
                    else -> null
                }
            val recursive = false

            runCatching {
                repository.getItems(
                    parentId = parentId,
                    includeTypes = includeTypes,
                    recursive = recursive,
                    limit = 100,
                )
            }.onSuccess { items ->
                _state.emit(BeamLibraryState(title = title, items = items, isLoading = false))
            }.onFailure { error ->
                _state.emit(BeamLibraryState(title = title, isLoading = false, error = error))
            }
        }
    }
}

data class BeamItemDetailState(
    val item: SpatialFinItem? = null,
    val availableVersions: List<SpatialFinMovie> = emptyList(),
    val isLoading: Boolean = false,
    val error: Throwable? = null,
)

@HiltViewModel
class BeamItemDetailViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(BeamItemDetailState())
    val state = _state.asStateFlow()

    fun load(itemId: UUID) {
        viewModelScope.launch {
            _state.emit(BeamItemDetailState(isLoading = true))
            runCatching { repository.getItem(itemId) }
                .onSuccess { item ->
                    val versions = if (item is SpatialFinMovie) loadAvailableVersions(item) else emptyList()
                    _state.emit(BeamItemDetailState(item = item, availableVersions = versions, isLoading = false))
                }
                .onFailure { error ->
                    _state.emit(BeamItemDetailState(isLoading = false, error = error))
                }
        }
    }

    fun toggleFavorite() {
        val current = _state.value.item ?: return
        viewModelScope.launch {
            runCatching {
                if (current.favorite) repository.unmarkAsFavorite(current.id)
                else repository.markAsFavorite(current.id)
            }
            load(current.id)
        }
    }

    fun togglePlayed() {
        val current = _state.value.item ?: return
        viewModelScope.launch {
            runCatching {
                if (current.played) repository.markAsUnplayed(current.id)
                else repository.markAsPlayed(current.id)
            }
            load(current.id)
        }
    }

    private suspend fun loadAvailableVersions(movie: SpatialFinMovie): List<SpatialFinMovie> {
        val targetGroupKey = movie.movieVersionGroupKey() ?: return listOf(movie)
        return runCatching {
            val candidates = repository
                .getSearchItems(movie.name)
                .filterIsInstance<SpatialFinMovie>()
                .filter { it.movieVersionGroupKey() == targetGroupKey }
                .mapNotNull { candidate ->
                    runCatching { repository.getMovie(candidate.id) }.getOrElse { candidate }
                }
            movie.versionOptionsFrom(candidates)
        }.getOrDefault(listOf(movie))
    }
}

data class BeamShowState(
    val show: SpatialFinShow? = null,
    val seasons: List<SpatialFinSeason> = emptyList(),
    val nextUp: SpatialFinEpisode? = null,
    val isLoading: Boolean = false,
    val error: Throwable? = null,
    val bulkDownload: BulkDownloadState = BulkDownloadState(),
)

@HiltViewModel
class BeamShowViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val downloader: Downloader,
) : ViewModel() {
    private val _state = MutableStateFlow(BeamShowState())
    val state = _state.asStateFlow()

    fun load(showId: UUID) {
        viewModelScope.launch {
            _state.emit(BeamShowState(isLoading = true))
            runCatching {
                Triple(
                    repository.getShow(showId),
                    repository.getSeasons(showId),
                    repository.getNextUp(showId).firstOrNull(),
                )
            }.onSuccess { (show, seasons, nextUp) ->
                _state.emit(
                    BeamShowState(
                        show = show,
                        seasons = seasons,
                        nextUp = nextUp,
                        isLoading = false,
                    )
                )
            }.onFailure { error ->
                _state.emit(BeamShowState(isLoading = false, error = error))
            }
        }
    }

    fun toggleFavorite() {
        val current = _state.value.show ?: return
        viewModelScope.launch {
            runCatching {
                if (current.favorite) repository.unmarkAsFavorite(current.id)
                else repository.markAsFavorite(current.id)
            }
            load(current.id)
        }
    }

    fun togglePlayed() {
        val current = _state.value.show ?: return
        viewModelScope.launch {
            runCatching {
                if (current.played) repository.markAsUnplayed(current.id)
                else repository.markAsPlayed(current.id)
            }
            load(current.id)
        }
    }

    fun downloadShow(showId: UUID, settings: BulkDownloadSettings) {
        viewModelScope.launch {
            _state.update { it.copy(bulkDownload = BulkDownloadState(isQueuing = true)) }
            runCatching {
                val seasons = repository.getSeasons(showId)
                val episodes = seasons.flatMap { season ->
                    repository.getEpisodes(seriesId = season.seriesId, seasonId = season.id, limit = 200)
                }
                downloader.downloadItems(episodes, settings)
            }.onSuccess { result ->
                _state.update { it.copy(bulkDownload = BulkDownloadState(isQueuing = false, result = result)) }
            }.onFailure {
                _state.update { it.copy(bulkDownload = BulkDownloadState(isQueuing = false, result = BulkDownloadResult(0, 0, 1))) }
            }
        }
    }
}

data class BeamSeasonState(
    val season: SpatialFinSeason? = null,
    val episodes: List<SpatialFinEpisode> = emptyList(),
    val isLoading: Boolean = false,
    val error: Throwable? = null,
    val bulkDownload: BulkDownloadState = BulkDownloadState(),
)

@HiltViewModel
class BeamSeasonViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val downloader: Downloader,
) : ViewModel() {
    private val _state = MutableStateFlow(BeamSeasonState())
    val state = _state.asStateFlow()

    fun load(seasonId: UUID) {
        viewModelScope.launch {
            _state.emit(BeamSeasonState(isLoading = true))
            runCatching {
                val season = repository.getSeason(seasonId)
                season to repository.getEpisodes(seriesId = season.seriesId, seasonId = seasonId, limit = 200)
            }.onSuccess { (season, episodes) ->
                _state.emit(BeamSeasonState(season = season, episodes = episodes, isLoading = false))
            }.onFailure { error ->
                _state.emit(BeamSeasonState(isLoading = false, error = error))
            }
        }
    }

    fun downloadEpisodes(episodes: List<SpatialFinEpisode>, settings: BulkDownloadSettings) {
        viewModelScope.launch {
            _state.update { it.copy(bulkDownload = BulkDownloadState(isQueuing = true)) }
            val result = downloader.downloadItems(episodes, settings)
            _state.update { it.copy(bulkDownload = BulkDownloadState(isQueuing = false, result = result)) }
        }
    }
}

data class BeamSearchState(
    val query: String = "",
    val items: List<SpatialFinItem> = emptyList(),
    val seerrItems: List<SeerrSearchResult> = emptyList(),
    val seerrError: String? = null,
    val isLoading: Boolean = false,
    val error: Throwable? = null,
    val hasSearched: Boolean = false,
)

@HiltViewModel
class BeamSearchViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val seerrApi: SeerrApi,
) : ViewModel() {
    private val _state = MutableStateFlow(BeamSearchState())
    val state = _state.asStateFlow()

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    fun search() {
        val query = state.value.query.trim()
        viewModelScope.launch {
            if (query.isBlank()) {
                _state.emit(BeamSearchState())
                return@launch
            }
            _state.emit(
                _state.value.copy(
                    isLoading = true,
                    error = null,
                    seerrError = null,
                    hasSearched = true,
                )
            )
            try {
                val jellyfinSearch = async { repository.getSearchItems(query) }
                val seerrSearch = async { seerrApi.searchDetailed(query) }
                val items = jellyfinSearch.await()
                val seerrOutcome = seerrSearch.await()
                val seerrItems =
                    seerrOutcome.response?.results?.filter {
                        it.mediaId != null && it.mediaType != "person" && it.mediaType != "collection"
                    } ?: emptyList()
                _state.emit(
                    _state.value.copy(
                        items = items,
                        seerrItems = seerrItems,
                        seerrError = seerrOutcome.errorMessage,
                        isLoading = false,
                        error = null,
                        hasSearched = true,
                    )
                )
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                _state.emit(
                    _state.value.copy(
                        items = emptyList(),
                        seerrItems = emptyList(),
                        seerrError = null,
                        isLoading = false,
                        error = error,
                        hasSearched = true,
                    )
                )
            }
        }
    }

    fun requestSeerrItem(item: SeerrSearchResult, is4k: Boolean) {
        val mediaId = item.mediaId ?: return
        viewModelScope.launch {
            val success = seerrApi.createRequest(item.mediaType, mediaId, is4k, item.tvdbId)
            if (success) {
                _state.emit(
                    _state.value.copy(
                        seerrItems =
                            _state.value.seerrItems.map { existing ->
                                if (existing.mediaId == mediaId && existing.mediaType == item.mediaType) {
                                    existing.copy(
                                        mediaInfo = existing.mediaInfo?.copy(status = 2)
                                            ?: SeerrMediaInfo(status = 2)
                                    )
                                } else {
                                    existing
                                }
                            }
                    )
                )
            }
        }
    }
}

@Composable
fun BeamHomeScreen(
    contentPadding: PaddingValues,
    onOpenLibrary: (UUID, String, CollectionType) -> Unit,
    onOpenShow: (UUID) -> Unit,
    onOpenSeason: (UUID) -> Unit,
    onOpenItem: (UUID) -> Unit,
) {
    val viewModel: HomeViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.loadData()
    }

    val setBackground = LocalBeamBackground.current

    val featuredItem =
        state.suggestionsSection?.items?.firstOrNull()
            ?: state.resumeSection?.homeSection?.items?.firstOrNull()
            ?: state.nextUpSection?.homeSection?.items?.firstOrNull()

    LaunchedEffect(featuredItem?.id) {
        if (featuredItem != null) {
            setBackground(featuredItem.images.backdrop ?: featuredItem.images.primary)
        }
    }

    BeamScaffoldBody(contentPadding = contentPadding) {
        when {
            state.isLoading -> item { LoadingCard("Loading your media...") }
            state.error != null -> item {
                ErrorCard(
                    title = "Couldn't reach your server",
                    body = state.error?.localizedMessage ?: "Unknown error",
                    onRetry = { viewModel.loadData() },
                )
            }
            else -> {
                // Featured hero card
                featuredItem?.let { featured ->
                    item {
                        BeamHeroCard(
                            item = featured,
                            actions = {
                                BeamPrimaryActionButton(label = if (featured.playbackPositionTicks > 0L) "Resume" else "Play") {
                                    launchServerItem(context, featured)
                                }
                                BeamSecondaryActionButton(label = "Details") {
                                    openServerItem(featured, onOpenLibrary, onOpenShow, onOpenSeason, onOpenItem)
                                }
                            },
                        )
                    }
                }

                // Suggestions carousel
                state.suggestionsSection?.let { suggestions ->
                    val filtered = suggestions.items.filter { it.id != featuredItem?.id }.deduplicateMovieVersions()
                    if (filtered.isNotEmpty()) {
                        item {
                            BeamHomeSectionHeader("Suggestions")
                        }
                        item {
                            BeamPosterCarousel(
                                items = filtered,
                                onItemClick = { openServerItem(it, onOpenLibrary, onOpenShow, onOpenSeason, onOpenItem) },
                            )
                        }
                    }
                }

                // Resume carousel
                state.resumeSection?.let { section ->
                    val items = section.homeSection.items.deduplicateMovieVersions()
                    if (items.isNotEmpty()) {
                        item {
                            BeamHomeSectionHeader(section.homeSection.name.asString())
                        }
                        item {
                            BeamPosterCarousel(
                                items = items,
                                onItemClick = { openServerItem(it, onOpenLibrary, onOpenShow, onOpenSeason, onOpenItem) },
                                showProgress = true,
                            )
                        }
                    }
                }

                // Next Up carousel
                state.nextUpSection?.let { section ->
                    val items = section.homeSection.items.deduplicateMovieVersions()
                    if (items.isNotEmpty()) {
                        item {
                            BeamHomeSectionHeader(section.homeSection.name.asString())
                        }
                        item {
                            BeamPosterCarousel(
                                items = items,
                                onItemClick = { openServerItem(it, onOpenLibrary, onOpenShow, onOpenSeason, onOpenItem) },
                            )
                        }
                    }
                }

                // Library sections with carousels
                if (state.views.isNotEmpty()) {
                    state.views.forEach { homeView ->
                        item {
                            BeamHomeSectionHeader(
                                title = homeView.view.name,
                                actionLabel = "See All",
                                onAction = {
                                    onOpenLibrary(
                                        homeView.view.id,
                                        homeView.view.name,
                                        homeView.view.type,
                                    )
                                },
                            )
                        }
                        val viewItems = homeView.view.items.deduplicateMovieVersions()
                        if (viewItems.isNotEmpty()) {
                            item {
                                BeamPosterCarousel(
                                    items = viewItems,
                                    onItemClick = { openServerItem(it, onOpenLibrary, onOpenShow, onOpenSeason, onOpenItem) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BeamHomeSectionHeader(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(20.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(2.dp),
                    )
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun BeamPosterCarousel(
    items: List<SpatialFinItem>,
    onItemClick: (SpatialFinItem) -> Unit,
    showProgress: Boolean = false,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(items, key = { it.id }) { item ->
            BeamPosterCard(
                item = item,
                onClick = { onItemClick(item) },
                showProgress = showProgress,
            )
        }
    }
}

@Composable
private fun BeamPosterCard(
    item: SpatialFinItem,
    onClick: () -> Unit,
    showProgress: Boolean = false,
) {
    val imageModel = item.images.primary ?: item.images.showPrimary ?: item.images.backdrop ?: item.images.showBackdrop
    val cardWidth = if (LocalBeamWidth.current.isCompact) 120.dp else 152.dp
    Card(
        onClick = onClick,
        modifier = Modifier.width(cardWidth),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp, hoveredElevation = 8.dp, focusedElevation = 8.dp, pressedElevation = 4.dp),
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(0.67f),
            ) {
                if (imageModel != null) {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        Text(
                            text = item.name.take(1).uppercase(),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                if (showProgress) {
                    buildPlaybackFraction(item)?.let { progress ->
                        FloatingProgressBar(
                            progress = progress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .align(androidx.compose.ui.Alignment.BottomCenter),
                            progressColor = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = remember(item) { buildServerItemSubtitle(item) }
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun BeamLibraryScreen(
    contentPadding: PaddingValues,
    parentId: UUID,
    title: String,
    type: CollectionType,
    onBack: () -> Unit,
    onOpenLibrary: (UUID, String, CollectionType) -> Unit,
    onOpenShow: (UUID) -> Unit,
    onOpenSeason: (UUID) -> Unit,
    onOpenItem: (UUID) -> Unit,
    viewModel: BeamLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(parentId, title, type) {
        viewModel.load(parentId, title, type)
    }

    BeamScaffoldBody(contentPadding = contentPadding) {
        item {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onBack) {
                    Text("Back")
                }
                Text(
                    text = state.title.ifBlank { title },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        when {
            state.isLoading -> item { LoadingCard("Loading library...") }
            state.error != null -> item {
                ErrorCard(
                    title = "Couldn't load this library",
                    body = state.error?.localizedMessage ?: "Unknown error",
                    onRetry = { viewModel.load(parentId, title, type) },
                )
            }
            state.items.isEmpty() -> item { BeamEmptyCard("No items found in this library.") }
            else -> {
                item {
                    BeamPosterGrid(
                        items = state.items,
                        onItemClick = { openServerItem(it, onOpenLibrary, onOpenShow, onOpenSeason, onOpenItem) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BeamPosterGrid(
    items: List<SpatialFinItem>,
    onItemClick: (SpatialFinItem) -> Unit,
) {
    val chunked = remember(items) { items.chunked(5) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        chunked.forEach { rowItems ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                rowItems.forEach { item ->
                    Box(modifier = Modifier.weight(1f)) {
                        BeamPosterCard(
                            item = item,
                            onClick = { onItemClick(item) },
                        )
                    }
                }
                // Fill remaining cells for incomplete rows
                repeat(5 - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun BeamShowScreen(
    contentPadding: PaddingValues,
    showId: UUID,
    onBack: () -> Unit,
    onOpenSeason: (UUID) -> Unit,
    onOpenItem: (UUID) -> Unit,
    onOpenPerson: (UUID) -> Unit,
    viewModel: BeamShowViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val setBackground = LocalBeamBackground.current
    var showBulkDownloadDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(showId) {
        viewModel.load(showId)
    }

    LaunchedEffect(state.show?.id) {
        state.show?.let { show ->
            setBackground(beamBackdropArtwork(show))
        }
    }

    LaunchedEffect(state.bulkDownload.result) {
        val result = state.bulkDownload.result ?: return@LaunchedEffect
        result.storageShortfallBytes?.let { shortfall ->
            val mb = shortfall / (1024 * 1024)
            Toast.makeText(context, "Low storage: need ~${mb}MB more space", Toast.LENGTH_LONG).show()
        }
        val msg = buildString {
            if (result.queued > 0) append("${result.queued} episodes queued")
            if (result.skipped > 0) {
                if (isNotEmpty()) append(", ")
                append("${result.skipped} already downloaded")
            }
            if (result.failed > 0) {
                if (isNotEmpty()) append(", ")
                append("${result.failed} failed")
            }
        }
        if (msg.isNotBlank()) Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    BeamScaffoldBody(contentPadding = contentPadding) {
        when {
            state.isLoading -> item { LoadingCard("Loading series...") }
            state.error != null -> item {
                ErrorCard(
                    title = "Couldn't load this series",
                    body = state.error?.localizedMessage ?: "Unknown error",
                    onRetry = { viewModel.load(showId) },
                )
            }
            state.show == null -> item { BeamEmptyCard("This series is no longer available.") }
            else -> {
                val show = state.show ?: return@BeamScaffoldBody
                val supportingLine =
                    show.originalTitle?.takeIf { !it.isNullOrBlank() && it != show.name }
                        ?: show.genres.take(3).takeIf { it.isNotEmpty() }?.joinToString(" • ")
                val metadata =
                    buildList {
                        getShowDateString(show).takeIf { it.isNotBlank() }?.let(::add)
                        if (state.seasons.isNotEmpty()) {
                            add("${state.seasons.size} seasons")
                        }
                        show.officialRating?.takeIf { it.isNotBlank() }?.let(::add)
                        show.communityRating?.let { add("${"%.1f".format(it)}/10") }
                        show.unplayedItemCount?.takeIf { it > 0 }?.let { add("$it unwatched") }
                    }
                item {
                    BeamDetailHeroCard(
                        item = show,
                        eyebrow = "Series",
                        supportingLine = supportingLine,
                        metadata = metadata,
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            state.nextUp?.let { nextEpisode ->
                                BeamPrimaryActionButton(
                                    label = if (nextEpisode.playbackPositionTicks > 0L) "Resume Episode" else "Play Next",
                                    onClick = { launchServerItem(context, nextEpisode) },
                                )
                                BeamSecondaryActionButton(
                                    label = "SyncPlay",
                                    onClick = {
                                        dev.jdtech.jellyfin.player.beam.BeamPlayerActivity
                                            .createIntentForSpatialItem(
                                                context = context,
                                                item = nextEpisode,
                                                openSyncPlayDialogOnStart = true,
                                            )
                                            ?.let(context::startActivity)
                                    },
                                )
                            }
                            BeamSecondaryActionButton(
                                label = if (show.favorite) "Favorited" else "Favorite",
                                onClick = { viewModel.toggleFavorite() },
                            )
                            BeamSecondaryActionButton(
                                label = if (show.played) "Watched" else "Mark watched",
                                onClick = { viewModel.togglePlayed() },
                            )
                            BeamSecondaryActionButton(
                                label = if (state.bulkDownload.isQueuing) "Queuing…" else "Download Show",
                                onClick = { showBulkDownloadDialog = true },
                            )
                            BeamSecondaryActionButton(label = "Back", onClick = onBack)
                        }
                    }
                }
                if (state.seasons.isNotEmpty()) {
                    item {
                        Text("Seasons", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                    item {
                        BeamSeasonStrip(
                            seasons = state.seasons,
                            onOpenSeason = onOpenSeason,
                        )
                    }
                }
                state.nextUp?.let { nextEpisode ->
                    item {
                        Text("Next Up", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                    item {
                        BeamServerItemCard(
                            item = nextEpisode,
                            onPlay = { launchServerItem(context, nextEpisode) },
                            onOpen = { onOpenItem(nextEpisode.id) },
                        )
                    }
                }
                val showActors = show.people.filter { person ->
                    person.type == org.jellyfin.sdk.model.api.PersonKind.ACTOR
                }
                if (showActors.isNotEmpty()) {
                    item {
                        dev.jdtech.jellyfin.presentation.film.components.ActorsRow(
                            actors = showActors,
                            onActorClick = onOpenPerson,
                            contentPadding = PaddingValues(horizontal = 0.dp),
                        )
                    }
                }
            }
        }
    }

    if (showBulkDownloadDialog) {
        BeamBulkDownloadDialog(
            title = "Download Show",
            description = "All episodes across ${state.seasons.size} season(s) will be queued for download.",
            confirmLabel = "Download All",
            onConfirm = { settings ->
                viewModel.downloadShow(showId, settings)
                showBulkDownloadDialog = false
            },
            onDismiss = { showBulkDownloadDialog = false },
        )
    }
}

@Composable
fun BeamSeasonScreen(
    contentPadding: PaddingValues,
    seasonId: UUID,
    onBack: () -> Unit,
    onOpenItem: (UUID) -> Unit,
    viewModel: BeamSeasonViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val setBackground = LocalBeamBackground.current
    var showBulkDownloadDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(seasonId) {
        viewModel.load(seasonId)
    }

    LaunchedEffect(state.season?.id) {
        state.season?.let { season ->
            setBackground(beamBackdropArtwork(season))
        }
    }

    LaunchedEffect(state.bulkDownload.result) {
        val result = state.bulkDownload.result ?: return@LaunchedEffect
        result.storageShortfallBytes?.let { shortfall ->
            val mb = shortfall / (1024 * 1024)
            Toast.makeText(context, "Low storage: need ~${mb}MB more space", Toast.LENGTH_LONG).show()
        }
        val msg = buildString {
            if (result.queued > 0) append("${result.queued} queued")
            if (result.skipped > 0) {
                if (isNotEmpty()) append(", ")
                append("${result.skipped} already downloaded")
            }
            if (result.failed > 0) {
                if (isNotEmpty()) append(", ")
                append("${result.failed} failed")
            }
        }
        if (msg.isNotBlank()) Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    BeamScaffoldBody(contentPadding = contentPadding) {
        when {
            state.isLoading -> item { LoadingCard("Loading season...") }
            state.error != null -> item {
                ErrorCard(
                    title = "Couldn't load this season",
                    body = state.error?.localizedMessage ?: "Unknown error",
                    onRetry = { viewModel.load(seasonId) },
                )
            }
            state.season == null -> item { BeamEmptyCard("This season is no longer available.") }
            else -> {
                val season = state.season ?: return@BeamScaffoldBody
                val downloadableEpisodes = state.episodes.filter { !it.isDownloaded() }
                item {
                    BeamMediaFeatureCard(
                        item = season,
                        actions = {
                            if (season.canPlay) {
                                BeamPrimaryActionButton(
                                    label = if (season.playbackPositionTicks > 0L) "Resume" else "Play",
                                    onClick = { launchServerItem(context, season) },
                                )
                            }
                            if (downloadableEpisodes.isNotEmpty()) {
                                BeamSecondaryActionButton(
                                    label = if (state.bulkDownload.isQueuing) "Queuing…" else "Download Season",
                                    onClick = { showBulkDownloadDialog = true },
                                )
                            }
                            BeamSecondaryActionButton(label = "Back", onClick = onBack)
                        },
                    )
                }
                if (state.episodes.isEmpty()) {
                    item { BeamEmptyCard("No episodes in this season.") }
                } else {
                    item {
                        Text("Episodes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                    items(state.episodes, key = { it.id }) { episode ->
                        BeamServerItemCard(
                            item = episode,
                            onPlay = { launchServerItem(context, episode) },
                            onOpen = { onOpenItem(episode.id) },
                        )
                    }
                }
            }
        }
    }

    if (showBulkDownloadDialog) {
        val downloadableEpisodes = state.episodes.filter { !it.isDownloaded() }
        BeamBulkDownloadDialog(
            title = "Download Season",
            description = "${downloadableEpisodes.size} episodes will be queued for download.",
            confirmLabel = "Download ${downloadableEpisodes.size} Episodes",
            onConfirm = { settings ->
                viewModel.downloadEpisodes(downloadableEpisodes, settings)
                showBulkDownloadDialog = false
            },
            onDismiss = { showBulkDownloadDialog = false },
        )
    }
}

@Composable
fun BeamItemDetailScreen(
    contentPadding: PaddingValues,
    itemId: UUID,
    onBack: () -> Unit,
    onOpenLibrary: (UUID, String, CollectionType) -> Unit,
    onOpenShow: (UUID) -> Unit,
    onOpenSeason: (UUID) -> Unit,
    onOpenPerson: (UUID) -> Unit,
    viewModel: BeamItemDetailViewModel = hiltViewModel(),
    downloaderViewModel: DownloaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloaderState by downloaderViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val setBackground = LocalBeamBackground.current
    var showDownloadDialog by rememberSaveable(itemId) { mutableStateOf(false) }
    var showPlaybackOptions by rememberSaveable(itemId) { mutableStateOf(false) }

    LaunchedEffect(itemId) {
        viewModel.load(itemId)
    }

    LaunchedEffect(state.item?.id) {
        state.item?.let {
            downloaderViewModel.update(it)
            setBackground(beamBackdropArtwork(it))
        }
    }

    LaunchedEffect(Unit) {
        downloaderViewModel.events.collect { event ->
            val message =
                when (event) {
                    DownloaderEvent.Successful -> "Download completed"
                    DownloaderEvent.Deleted -> "Download deleted"
                }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    BeamScaffoldBody(contentPadding = contentPadding) {
        when {
            state.isLoading -> item { LoadingCard("Loading...") }
            state.error != null -> item {
                ErrorCard(
                    title = "Couldn't load this item",
                    body = state.error?.localizedMessage ?: "Unknown error",
                    onRetry = { viewModel.load(itemId) },
                )
            }
            state.item == null -> item { BeamEmptyCard("This item is no longer available.") }
            else -> {
                val itemData = state.item ?: return@BeamScaffoldBody
                val supportingLine =
                    when (itemData) {
                        is SpatialFinMovie ->
                            itemData.originalTitle?.takeIf { !it.isNullOrBlank() && it != itemData.name }
                                ?: itemData.genres.take(3).takeIf { it.isNotEmpty() }?.joinToString(" • ")
                        is SpatialFinEpisode ->
                            listOf(itemData.seriesName, itemData.seasonName, buildEpisodeLabel(itemData))
                                .filterNotNull()
                                .filter { it.isNotBlank() }
                                .joinToString(" • ")
                                .ifBlank { null }
                        is SpatialFinSeason -> itemData.seriesName
                        else -> itemData.originalTitle?.takeIf { !it.isNullOrBlank() && it != itemData.name }
                    }
                val metadata =
                    buildList {
                        when (itemData) {
                            is SpatialFinMovie -> {
                                itemData.productionYear?.let { add(it.toString()) }
                                beamRuntimeLabel(itemData.runtimeTicks)?.let(::add)
                                itemData.officialRating?.takeIf { it.isNotBlank() }?.let(::add)
                                itemData.communityRating?.let { add("${"%.1f".format(it)}/10") }
                                addAll(itemData.genres.take(2))
                            }
                            is SpatialFinEpisode -> {
                                add(buildEpisodeLabel(itemData))
                                itemData.premiereDate?.year?.let { add(it.toString()) }
                                beamRuntimeLabel(itemData.runtimeTicks)?.let(::add)
                                itemData.communityRating?.let { add("${"%.1f".format(it)}/10") }
                            }
                            is SpatialFinSeason -> {
                                add(beamSeasonLabel(itemData))
                                itemData.unplayedItemCount?.takeIf { it > 0 }?.let { add("$it unwatched") }
                            }
                            else -> Unit
                        }
                    }
                item {
                    BeamDetailHeroCard(
                        item = itemData,
                        eyebrow = buildPrimaryBadge(itemData),
                        supportingLine = supportingLine,
                        metadata = metadata,
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (itemData.canPlay) {
                                BeamPrimaryActionButton(
                                    label = if (itemData.playbackPositionTicks > 0L) "Resume" else "Play",
                                    onClick = { launchServerItem(context, itemData) },
                                )
                                BeamSecondaryActionButton(label = "From Start") {
                                    launchServerItem(context = context, item = itemData, startFromBeginning = true)
                                }
                                BeamSecondaryActionButton(
                                    label = "SyncPlay",
                                    onClick = {
                                        dev.jdtech.jellyfin.player.beam.BeamPlayerActivity
                                            .createIntentForSpatialItem(
                                                context = context,
                                                item = itemData,
                                                openSyncPlayDialogOnStart = true,
                                            )
                                            ?.let(context::startActivity)
                                    },
                                )
                                OutlinedButton(onClick = { showPlaybackOptions = true }) {
                                    Text("Playback Options")
                                }
                            }
                            if (itemData is SpatialFinCollection || itemData is SpatialFinFolder) {
                                BeamPrimaryActionButton(
                                    label = "Open Collection",
                                    onClick = { openServerItem(itemData, onOpenLibrary, onOpenShow, onOpenSeason, {}) },
                                )
                            }
                            if (itemData is SpatialFinEpisode) {
                                BeamSecondaryActionButton(
                                    label = "Go to series",
                                    onClick = { onOpenShow(itemData.seriesId) },
                                )
                                BeamSecondaryActionButton(
                                    label = "Go to season",
                                    onClick = { onOpenSeason(itemData.seasonId) },
                                )
                            }
                            BeamSecondaryActionButton(
                                label = if (itemData.favorite) "Favorited" else "Favorite",
                                onClick = { viewModel.toggleFavorite() },
                            )
                            BeamSecondaryActionButton(
                                label = if (itemData.played) "Watched" else "Mark watched",
                                onClick = { viewModel.togglePlayed() },
                            )
                            BeamSecondaryActionButton(label = "Back", onClick = onBack)
                        }
                        if (itemData.canPlay) {
                            BeamDownloadActions(
                                item = itemData,
                                downloaderState = downloaderState,
                                onOpenOptions = { showDownloadDialog = true },
                                onCancelDownload = {
                                    downloaderViewModel.onAction(DownloaderAction.CancelDownload(itemData))
                                },
                                onPauseDownload = {
                                    downloaderViewModel.onAction(DownloaderAction.PauseDownload(itemData))
                                },
                                onResumeDownload = {
                                    downloaderViewModel.onAction(DownloaderAction.ResumeDownload(itemData))
                                },
                                onDeleteDownload = {
                                    downloaderViewModel.onAction(DownloaderAction.DeleteDownload(itemData))
                                },
                            )
                        }
                        if (state.availableVersions.size > 1) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Version",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFD7DDE6),
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                state.availableVersions.forEach { version ->
                                    FilterChip(
                                        selected = version.id == itemData.id,
                                        onClick = { if (version.id != itemData.id) viewModel.load(version.id) },
                                        label = { Text(version.versionChipLabel()) },
                                    )
                                }
                            }
                        }
                    }
                }
                val actors = beamPeopleOf(itemData).filter { person ->
                    person.type == org.jellyfin.sdk.model.api.PersonKind.ACTOR
                }
                if (actors.isNotEmpty()) {
                    item {
                        dev.jdtech.jellyfin.presentation.film.components.ActorsRow(
                            actors = actors,
                            onActorClick = onOpenPerson,
                            contentPadding = PaddingValues(horizontal = 0.dp),
                        )
                    }
                }
                if (itemData.chapters.isNotEmpty()) {
                    item {
                        BeamChaptersRow(
                            chapters = itemData.chapters,
                            // Seek-to-chapter needs a launchServerItem start-position param;
                            // for now tapping a chapter just starts playback from beginning.
                            onChapterClick = { _ -> launchServerItem(context, itemData) },
                        )
                    }
                }
            }
        }
    }

    if (showDownloadDialog) {
        val item = state.item
        if (item != null) {
            BeamDownloadOptionsDialog(
                item = item,
                onConfirm = { request ->
                    downloaderViewModel.onAction(DownloaderAction.Download(item, request))
                    showDownloadDialog = false
                },
                onDismiss = { showDownloadDialog = false },
            )
        }
    }
    if (showPlaybackOptions) {
        val item = state.item
        if (item != null) {
            BeamPlaybackOptionsDialog(
                item = item,
                onDismiss = { showPlaybackOptions = false },
                onPlay = { sourceIndex, bitrate, fromBeginning ->
                    launchServerItem(
                        context = context,
                        item = item,
                        startFromBeginning = fromBeginning,
                        mediaSourceIndex = sourceIndex,
                        maxBitrate = bitrate,
                    )
                    showPlaybackOptions = false
                },
            )
        }
    }
}

private fun beamPrimaryArtwork(item: SpatialFinItem): Any? =
    when (item) {
        is SpatialFinEpisode ->
            item.images.showPrimary ?: item.images.primary ?: item.images.showBackdrop ?: item.images.backdrop
        else ->
            item.images.primary ?: item.images.showPrimary ?: item.images.backdrop ?: item.images.showBackdrop
    }

private fun beamBackdropArtwork(item: SpatialFinItem): Any? =
    when (item) {
        is SpatialFinEpisode ->
            item.images.showBackdrop ?: item.images.backdrop ?: item.images.showPrimary ?: item.images.primary
        else ->
            item.images.backdrop ?: item.images.showBackdrop ?: item.images.primary ?: item.images.showPrimary
    }

private fun beamRuntimeLabel(runtimeTicks: Long): String? =
    runtimeTicks
        .takeIf { it > 0L }
        ?.div(600000000)
        ?.takeIf { it > 0L }
        ?.let { "$it min" }

private fun beamSeasonLabel(season: SpatialFinSeason): String =
    if (season.indexNumber > 0) {
        "Season ${season.indexNumber}"
    } else {
        season.name.ifBlank { "Season" }
    }

@Composable
private fun BeamPlaybackOptionsDialog(
    item: SpatialFinItem,
    onDismiss: () -> Unit,
    onPlay: (sourceIndex: Int?, bitrate: Long?, fromBeginning: Boolean) -> Unit,
) {
    val sources = remember(item) { item.sources.filter { it.type == SpatialFinSourceType.REMOTE } }
    var selectedSourceIndex by remember { mutableStateOf(0) }
    var selectedBitrate by remember { mutableStateOf(0L) }
    var startFromBeginning by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playback Options") },
        text = {
            BeamScrollableDialogBody {
                Text(
                    "Choose the version and streaming quality before playback starts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (sources.size > 1) {
                    BeamChoiceSection(title = "Version") {
                        sources.forEachIndexed { index, source ->
                            BeamChoiceRow(
                                selected = selectedSourceIndex == index,
                                title = source.name.ifBlank { "Source ${index + 1}" },
                                subtitle = source.size.takeIf { it > 0 }?.let(::formatDownloadFileSize),
                                onClick = { selectedSourceIndex = index },
                            )
                        }
                    }
                }
                BeamChoiceSection(title = "Streaming Quality") {
                    BeamChoiceRow(
                        selected = selectedBitrate == 0L,
                        title = "Auto",
                        subtitle = "Let Jellyfin choose the best direct play or transcode path.",
                        onClick = { selectedBitrate = 0L },
                    )
                    DEFAULT_DOWNLOAD_BITRATES.forEach { (bitrate, label) ->
                        BeamChoiceRow(
                            selected = selectedBitrate == bitrate.toLong(),
                            title = label,
                            onClick = { selectedBitrate = bitrate.toLong() },
                        )
                    }
                }
                BeamChoiceSection(title = "Start Position") {
                    BeamChoiceRow(
                        selected = !startFromBeginning,
                        title = "Resume",
                        onClick = { startFromBeginning = false },
                    )
                    BeamChoiceRow(
                        selected = startFromBeginning,
                        title = "Play From Start",
                        onClick = { startFromBeginning = true },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val sourceIndex = if (sources.size > 1) selectedSourceIndex else null
                    onPlay(sourceIndex, selectedBitrate.takeIf { it > 0L }, startFromBeginning)
                },
            ) {
                Text("Play")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun BeamDownloadActions(
    item: SpatialFinItem,
    downloaderState: dev.jdtech.jellyfin.core.presentation.downloader.DownloaderState,
    onOpenOptions: () -> Unit,
    onCancelDownload: () -> Unit,
    onPauseDownload: () -> Unit,
    onResumeDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
) {
    if (!item.canDownload) return

    val isDownloaded = item.isDownloaded()
    val isDownloading = item.isDownloading() || downloaderState.isDownloading
    val isPaused = downloaderState.status == DownloadManager.STATUS_PAUSED
    val isFailed = downloaderState.status == DownloadManager.STATUS_FAILED

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            isDownloaded -> {
                FilledTonalButton(onClick = onDeleteDownload) {
                    Text("Delete Download")
                }
            }
            isPaused || isFailed -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onResumeDownload) {
                        Text("Resume Download")
                    }
                    FilledTonalButton(onClick = onCancelDownload) {
                        Text("Cancel")
                    }
                }
            }
            isDownloading -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onPauseDownload) {
                        val pct = (downloaderState.progress * 100).toInt()
                        Text(if (pct > 0) "Pause ($pct%)" else "Pause")
                    }
                    FilledTonalButton(onClick = onCancelDownload) {
                        Text("Cancel")
                    }
                }
            }
            else -> {
                FilledTonalButton(onClick = onOpenOptions) {
                    Text("Download")
                }
            }
        }
        downloaderState.errorText?.let { error ->
            Text(
                text = error.asString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun BeamDownloadOptionsDialog(
    item: SpatialFinItem,
    onConfirm: (DownloadRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    val sources = remember(item) { item.sources.filter { it.type == SpatialFinSourceType.REMOTE } }
    if (sources.isEmpty()) return

    var selectedSourceIndex by remember { mutableStateOf(0) }
    var selectedMode by remember { mutableStateOf(DownloadMode.ORIGINAL) }
    var selectedBitrate by remember { mutableStateOf(DEFAULT_DOWNLOAD_BITRATES.first().first) }
    var selectedAudioStreamIndex by remember { mutableStateOf<Int?>(null) }
    var selectedSubtitleStreamIndex by remember { mutableStateOf<Int?>(null) }

    val selectedSource = sources.getOrNull(selectedSourceIndex) ?: return
    val audioStreams = selectedSource.mediaStreams.filter { it.type == MediaStreamType.AUDIO && it.index != null }
    val subtitleStreams = selectedSource.mediaStreams.filter { it.type == MediaStreamType.SUBTITLE && it.index != null }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        DownloadRequest(
                            sourceId = selectedSource.id,
                            mode = selectedMode,
                            videoBitrate = if (selectedMode == DownloadMode.TRANSCODED) selectedBitrate else null,
                            audioStreamIndex = if (selectedMode == DownloadMode.TRANSCODED) selectedAudioStreamIndex else null,
                            subtitleStreamIndex = if (selectedMode == DownloadMode.TRANSCODED) selectedSubtitleStreamIndex else null,
                            subtitleDeliveryMethod =
                                if (selectedMode == DownloadMode.TRANSCODED && selectedSubtitleStreamIndex != null) {
                                    SubtitleDeliveryMethod.EMBED
                                } else {
                                    SubtitleDeliveryMethod.DROP
                                },
                        )
                    )
                },
            ) {
                Text("Start Download")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = { Text("Download Options") },
        text = {
            BeamScrollableDialogBody {
                Text(
                    "Choose the source, bitrate, and tracks for this download.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (sources.size > 1) {
                    BeamChoiceSection(title = "Video Version") {
                        sources.forEachIndexed { index, source ->
                            BeamChoiceRow(
                                selected = selectedSourceIndex == index,
                                title = source.name.ifBlank { "Source ${index + 1}" },
                                subtitle = source.size.takeIf { it > 0 }?.let(::formatDownloadFileSize),
                                onClick = { selectedSourceIndex = index },
                            )
                        }
                    }
                }
                BeamChoiceSection(title = "Download Mode") {
                    BeamChoiceRow(
                        selected = selectedMode == DownloadMode.ORIGINAL,
                        title = "Original",
                        subtitle = "Download the original file for best quality and fastest setup.",
                        onClick = { selectedMode = DownloadMode.ORIGINAL },
                    )
                    BeamChoiceRow(
                        selected = selectedMode == DownloadMode.TRANSCODED,
                        title = "Transcoded",
                        subtitle = "Smaller file with explicit audio and subtitle embedding.",
                        onClick = { selectedMode = DownloadMode.TRANSCODED },
                    )
                }
                if (selectedMode == DownloadMode.TRANSCODED) {
                    BeamChoiceSection(title = "Video Quality") {
                        DEFAULT_DOWNLOAD_BITRATES.forEach { (bitrate, label) ->
                            BeamChoiceRow(
                                selected = selectedBitrate == bitrate,
                                title = label,
                                onClick = { selectedBitrate = bitrate },
                            )
                        }
                    }
                    if (audioStreams.isNotEmpty()) {
                        BeamChoiceSection(title = "Audio Track") {
                            audioStreams.forEach { stream ->
                                BeamChoiceRow(
                                    selected = selectedAudioStreamIndex == stream.index,
                                    title = streamLabel(stream),
                                    onClick = { selectedAudioStreamIndex = stream.index },
                                )
                            }
                        }
                    }
                    BeamChoiceSection(title = "Subtitle Track") {
                        BeamChoiceRow(
                            selected = selectedSubtitleStreamIndex == null,
                            title = "None",
                            onClick = { selectedSubtitleStreamIndex = null },
                        )
                        subtitleStreams.forEach { stream ->
                            BeamChoiceRow(
                                selected = selectedSubtitleStreamIndex == stream.index,
                                title = streamLabel(stream),
                                onClick = { selectedSubtitleStreamIndex = stream.index },
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun BeamScrollableDialogBody(
    content: @Composable ColumnScope.() -> Unit,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.55f).coerceAtLeast(220.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun BeamChoiceSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    })
}

@Composable
private fun BeamChoiceRow(
    selected: Boolean,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (selected) "●" else "○", style = MaterialTheme.typography.bodyLarge)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val DEFAULT_DOWNLOAD_BITRATES =
    listOf(
        8_000_000 to "8 Mbps",
        5_000_000 to "5 Mbps",
        3_000_000 to "3 Mbps",
        2_000_000 to "2 Mbps",
        1_000_000 to "1 Mbps",
    )

@Composable
private fun BeamBulkDownloadDialog(
    title: String,
    description: String,
    confirmLabel: String,
    onConfirm: (BulkDownloadSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedMode by remember { mutableStateOf(DownloadMode.ORIGINAL) }
    var selectedBitrate by remember { mutableStateOf(DEFAULT_DOWNLOAD_BITRATES.first().first) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        BulkDownloadSettings(
                            mode = selectedMode,
                            videoBitrate = if (selectedMode == DownloadMode.TRANSCODED) selectedBitrate else null,
                        )
                    )
                },
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        title = { Text(title) },
        text = {
            BeamScrollableDialogBody {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BeamChoiceSection(title = "Download Mode") {
                    BeamChoiceRow(
                        selected = selectedMode == DownloadMode.ORIGINAL,
                        title = "Original",
                        subtitle = "Download the original files for best quality.",
                        onClick = { selectedMode = DownloadMode.ORIGINAL },
                    )
                    BeamChoiceRow(
                        selected = selectedMode == DownloadMode.TRANSCODED,
                        title = "Transcoded",
                        subtitle = "Smaller files with embedded audio and subtitles.",
                        onClick = { selectedMode = DownloadMode.TRANSCODED },
                    )
                }
                if (selectedMode == DownloadMode.TRANSCODED) {
                    BeamChoiceSection(title = "Video Quality") {
                        DEFAULT_DOWNLOAD_BITRATES.forEach { (bitrate, label) ->
                            BeamChoiceRow(
                                selected = selectedBitrate == bitrate,
                                title = label,
                                onClick = { selectedBitrate = bitrate },
                            )
                        }
                    }
                }
            }
        },
    )
}

private fun streamLabel(stream: SpatialFinMediaStream): String {
    val language = stream.language.ifBlank { "Unknown" }
    val details =
        listOfNotNull(
            stream.displayTitle?.takeIf { it.isNotBlank() },
            stream.codec.takeIf { it.isNotBlank() }?.uppercase(),
        )
    return listOf(language, *details.toTypedArray()).joinToString(" • ")
}

private fun formatDownloadFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return "%.1f %s".format(size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

@Composable
fun BeamSearchScreen(
    contentPadding: PaddingValues,
    voiceQuery: String? = null,
    onVoiceQueryConsumed: () -> Unit = {},
    onOpenLibrary: (UUID, String, CollectionType) -> Unit,
    onOpenShow: (UUID) -> Unit,
    onOpenSeason: (UUID) -> Unit,
    onOpenItem: (UUID) -> Unit,
    viewModel: BeamSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var submittedInitialSearch by rememberSaveable { mutableStateOf(false) }
    var pendingSeerrRequest by remember { mutableStateOf<SeerrSearchResult?>(null) }

    LaunchedEffect(voiceQuery) {
        if (!voiceQuery.isNullOrBlank()) {
            viewModel.setQuery(voiceQuery)
            viewModel.search()
            onVoiceQueryConsumed()
        }
    }

    LaunchedEffect(submittedInitialSearch) {
        if (!submittedInitialSearch && state.query.isBlank()) {
            submittedInitialSearch = true
        }
    }

    pendingSeerrRequest?.let { item ->
        BeamSeerrRequestDialog(
            item = item,
            onConfirm = { is4k ->
                viewModel.requestSeerrItem(item, is4k)
                pendingSeerrRequest = null
            },
            onDismiss = { pendingSeerrRequest = null },
        )
    }

    BeamScaffoldBody(contentPadding = contentPadding) {
        item {
            BeamScreenHeader(
                title = "Search",
                body = "Find movies, shows, and more.",
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Search Jellyfin") },
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = viewModel::search) {
                            Text("Search")
                        }
                        if (state.query.isNotBlank()) {
                            OutlinedButton(onClick = { viewModel.setQuery("") }) {
                                Text("Clear")
                            }
                        }
                    }
                }
            }
        }
        when {
            state.isLoading -> item { LoadingCard("Searching Jellyfin and Jellyseerr...") }
            state.error != null -> item {
                ErrorCard(
                    title = "Search failed.",
                    body = state.error?.localizedMessage ?: "Unknown error",
                    onRetry = viewModel::search,
                )
            }
            state.hasSearched && state.items.isEmpty() && state.seerrItems.isEmpty() -> item {
                BeamEmptyCard("No items matched your search.")
            }
            state.items.isNotEmpty() -> {
                item {
                    Text("In Your Library", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                items(state.items, key = { it.id }) { item ->
                    BeamServerItemCard(
                        item = item,
                        onPlay = { launchServerItem(context, item) },
                        onOpen = { openServerItem(item, onOpenLibrary, onOpenShow, onOpenSeason, onOpenItem) },
                    )
                }
            }
            else -> Unit
        }
        state.seerrError?.let { seerrError ->
            item {
                ErrorCard(
                    title = "Jellyseerr unavailable",
                    body = seerrError,
                    onRetry = viewModel::search,
                )
            }
        }
        if (state.seerrItems.isNotEmpty()) {
            item {
                Text("Request From Jellyseerr", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            items(
                items = state.seerrItems,
                key = { "${it.mediaType}-${it.mediaId ?: it.id ?: it.title ?: it.name}" },
            ) { item ->
                BeamSeerrItemCard(
                    item = item,
                    onRequestClick = { pendingSeerrRequest = item },
                )
            }
        }
    }
}

@Composable
private fun BeamSeerrItemCard(
    item: SeerrSearchResult,
    onRequestClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title ?: item.name ?: "Unknown title",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    buildBeamSeerrSubtitle(item)?.let { subtitle ->
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                BeamSeerrStatusBadge(item.mediaInfo?.status)
            }
            item.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when (item.mediaInfo?.status ?: 1) {
                1 -> Button(onClick = onRequestClick) { Text("Request") }
                2 -> Text("Request pending", color = MaterialTheme.colorScheme.primary)
                3 -> Text("Request processing", color = MaterialTheme.colorScheme.primary)
                4 -> Text("Partially available", color = MaterialTheme.colorScheme.primary)
                5 -> Text("Already available", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun BeamSeerrRequestDialog(
    item: SeerrSearchResult,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Request From Jellyseerr") },
        text = {
            BeamScrollableDialogBody {
                Text("Choose request quality for ${item.title ?: item.name ?: "this item"}.")
                FilledTonalButton(onClick = { onConfirm(false) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Standard")
                }
                FilledTonalButton(onClick = { onConfirm(true) }, modifier = Modifier.fillMaxWidth()) {
                    Text("4K")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun BeamDownloadsScreen(
    contentPadding: PaddingValues,
    onOpenShow: (UUID) -> Unit,
    onOpenSeason: (UUID) -> Unit,
    onOpenItem: (UUID) -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()
    val storageUsedBytes by viewModel.storageUsedBytes.collectAsStateWithLifecycle()
    val continueWatchingItems by viewModel.continueWatchingItems.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingDeleteItem by remember { mutableStateOf<SpatialFinItem?>(null) }

    LaunchedEffect(Unit) {
        viewModel.loadItems()
    }

    pendingDeleteItem?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDeleteItem = null },
            title = { Text("Delete Download") },
            text = { Text("Remove ${item.name} from downloaded media?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteItem(item)
                        pendingDeleteItem = null
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteItem = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    BeamScaffoldBody(contentPadding = contentPadding) {
        item {
            val storageLabel = if (storageUsedBytes > 0L) {
                android.text.format.Formatter.formatFileSize(context, storageUsedBytes)
            } else null
            BeamScreenHeader(
                title = "Downloads",
                body = storageLabel
                    ?.let { "Movies and shows saved for offline playback. Using $it." }
                    ?: "Movies and shows saved for offline playback.",
            )
        }
        if (activeDownloads.isNotEmpty()) {
            item {
                Text(
                    text = "In Progress",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(activeDownloads, key = { it.taskId }) { entry ->
                BeamActiveDownloadCard(
                    entry = entry,
                    onPause = { viewModel.pauseDownload(entry.itemId) },
                    onResume = { viewModel.resumeDownload(entry.itemId) },
                    onCancel = { viewModel.cancelActiveDownload(entry.itemId) },
                )
            }
        }
        if (continueWatchingItems.isNotEmpty()) {
            item {
                Text(
                    text = "Continue Watching",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(continueWatchingItems, key = { it.id }) { item ->
                BeamDownloadedItemCard(
                    item = item,
                    onPlay = { launchServerItem(context, item) },
                    onOpen = {
                        when (item) {
                            is SpatialFinShow -> onOpenShow(item.id)
                            is SpatialFinSeason -> onOpenSeason(item.id)
                            else -> onOpenItem(item.id)
                        }
                    },
                    onDelete = { pendingDeleteItem = item },
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = viewModel::setSearchQuery,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search downloads...") },
                    singleLine = true,
                )
                TextButton(
                    onClick = {
                        viewModel.setSortOrder(
                            if (sortOrder == DownloadSortOrder.NAME) DownloadSortOrder.DATE_ADDED
                            else DownloadSortOrder.NAME
                        )
                    },
                ) {
                    Text(if (sortOrder == DownloadSortOrder.NAME) "A–Z" else "Recent")
                }
            }
        }
        when {
            state.isLoading -> item { LoadingCard("Loading downloads...") }
            state.error != null -> item {
                ErrorCard(
                    title = "Failed to load downloads.",
                    body = state.error?.localizedMessage ?: "Unknown error",
                    onRetry = viewModel::loadItems,
                )
            }
            state.sections.isEmpty() && activeDownloads.isEmpty() ->
                item { BeamEmptyCard("No downloaded media found.") }
            else -> {
                state.sections.forEach { section ->
                    item {
                        Text(
                            text = section.name.asString(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    items(section.items, key = { it.id }) { item ->
                        BeamDownloadedItemCard(
                            item = item,
                            onPlay = { launchServerItem(context, item) },
                            onOpen = {
                                when (item) {
                                    is SpatialFinShow -> onOpenShow(item.id)
                                    is SpatialFinSeason -> onOpenSeason(item.id)
                                    else -> onOpenItem(item.id)
                                }
                            },
                            onDelete = { pendingDeleteItem = item },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BeamActiveDownloadCard(
    entry: ActiveDownloadEntry,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val statusLabel = when (entry.status) {
        android.app.DownloadManager.STATUS_RUNNING -> {
            val total = entry.totalBytes
            val sizeStr = if (total != null && total > 0) {
                "${android.text.format.Formatter.formatFileSize(context, entry.bytesDownloaded)} / ${android.text.format.Formatter.formatFileSize(context, total)}"
            } else {
                "${entry.progress}%"
            }
            val speed = entry.downloadSpeedBytesPerSec
            if (speed != null && speed > 0) {
                "$sizeStr · ${android.text.format.Formatter.formatFileSize(context, speed)}/s"
            } else sizeStr
        }
        android.app.DownloadManager.STATUS_PAUSED -> "Paused"
        android.app.DownloadManager.STATUS_FAILED -> "Failed"
        else -> "Pending"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = entry.itemName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.status == android.app.DownloadManager.STATUS_RUNNING || entry.status == android.app.DownloadManager.STATUS_PAUSED) {
                LinearProgressIndicator(
                    progress = { entry.progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = if (entry.status == android.app.DownloadManager.STATUS_PAUSED)
                        MaterialTheme.colorScheme.outline
                    else
                        MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            entry.errorMessage?.takeIf { it.isNotBlank() && it != "Paused" }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (entry.status) {
                    android.app.DownloadManager.STATUS_RUNNING -> {
                        FilledTonalButton(onClick = onPause) { Text("Pause") }
                        FilledTonalButton(onClick = onCancel) { Text("Cancel") }
                    }
                    android.app.DownloadManager.STATUS_PAUSED,
                    android.app.DownloadManager.STATUS_FAILED -> {
                        FilledTonalButton(onClick = onResume) { Text("Resume") }
                        FilledTonalButton(onClick = onCancel) { Text("Cancel") }
                    }
                    else -> {
                        FilledTonalButton(onClick = onCancel) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

@Composable
private fun BeamServerItemCard(
    item: SpatialFinItem,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
) {
    BeamMediaFeatureCard(
        item = item,
        actions = {
            when (item) {
                is SpatialFinMovie,
                is SpatialFinEpisode,
                is SpatialFinShow,
                is SpatialFinSeason,
                is SpatialFinBoxSet -> {
                    BeamPrimaryActionButton(label = "Play", onClick = onPlay)
                }
                else -> Unit
            }
            BeamSecondaryActionButton(
                label =
                    when (item) {
                        is SpatialFinCollection,
                        is SpatialFinFolder -> "Open"
                        else -> "Details"
                    },
                onClick = onOpen,
            )
        },
    )
}

@Composable
private fun BeamDownloadedItemCard(
    item: SpatialFinItem,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    BeamMediaFeatureCard(
        item = item,
        actions = {
            BeamPrimaryActionButton(label = "Play", onClick = onPlay)
            BeamSecondaryActionButton(label = "Details", onClick = onOpen)
            BeamSecondaryActionButton(label = "Delete", onClick = onDelete)
        },
    )
}

@Composable
private fun BeamHeroCard(
    item: SpatialFinItem,
    actions: @Composable RowScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().height(240.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f)),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = item.images.backdrop ?: item.images.primary ?: item.images.showPrimary,
                contentDescription = item.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.6f,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            colors = listOf(Color(0xDD0F141C), Color.Transparent),
                            startX = 0f,
                            endX = 800f,
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(24.dp)
                    .fillMaxWidth(0.7f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildServerItemSubtitle(item) ?: item.overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.LightGray,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    actions()
                }
            }
        }
    }
}

@Composable
private fun BeamDetailHeroCard(
    item: SpatialFinItem,
    eyebrow: String,
    supportingLine: String?,
    metadata: List<String>,
    actions: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.55f)),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            AsyncImage(
                model = beamBackdropArtwork(item),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.45f,
            )
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xF2080A10),
                                    Color(0xD0080A10),
                                    Color(0x80080A10),
                                    Color.Transparent,
                                ),
                            )
                        )
            )
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(Color(0x660C1016), Color.Transparent, Color(0xCC0C1016)),
                            )
                        )
            )
            val stacked = LocalBeamWidth.current.isCompact
            val info: @Composable ColumnScope.() -> Unit = {
                BeamBadge(text = eyebrow)
                Text(
                    text = item.name,
                    style = if (stacked) MaterialTheme.typography.headlineSmall
                            else MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                supportingLine?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFFE6EBF2),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (metadata.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        metadata.forEach { token ->
                            BeamDetailPill(text = token)
                        }
                    }
                }
                if (item.ratings.isNotEmpty()) {
                    RatingsRow(ratings = item.ratings)
                }
                Text(
                    text = item.overview.ifBlank { "No overview available." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFD7DDE6),
                    maxLines = if (stacked) 6 else 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                actions()
            }
            if (stacked) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BeamPosterArtwork(
                        item = item,
                        modifier = Modifier.width(150.dp).aspectRatio(0.67f),
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) { info() }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 26.dp),
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    BeamPosterArtwork(
                        item = item,
                        modifier = Modifier.width(180.dp).aspectRatio(0.67f),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) { info() }
                }
            }
        }
    }
}

@Composable
private fun BeamDetailPill(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Color.Black.copy(alpha = 0.25f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
        )
    }
}

@Composable
private fun BeamSeasonStrip(
    seasons: List<SpatialFinSeason>,
    onOpenSeason: (UUID) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(seasons, key = { it.id }) { season ->
            BeamSeasonCard(
                season = season,
                onClick = { onOpenSeason(season.id) },
            )
        }
    }
}

@Composable
private fun BeamSeasonCard(
    season: SpatialFinSeason,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(220.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BeamPosterArtwork(
                item = season,
                modifier = Modifier.width(58.dp).aspectRatio(0.67f),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = beamSeasonLabel(season),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        season.unplayedItemCount
                            ?.takeIf { it > 0 }
                            ?.let { "$it unwatched" }
                            ?: season.seriesName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BeamMediaFeatureCard(
    item: SpatialFinItem,
    actions: @Composable RowScope.() -> Unit,
) {
    val subtitle = remember(item) { buildServerItemSubtitle(item) }
    val badge = remember(item) { buildPrimaryBadge(item) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BeamPosterArtwork(
                item = item,
                modifier = Modifier.width(80.dp).aspectRatio(0.67f),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BeamBadge(text = badge)
                    if (item.isDownloaded()) {
                        BeamBadge(text = "Downloaded")
                    }
                }
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                item.overview.takeIf { it.isNotBlank() }?.let { overview ->
                    Text(
                        text = overview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                buildPlaybackFraction(item)?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(0.5f).height(3.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = actions,
                )
            }
        }
    }
}

@Composable
private fun BeamPosterArtwork(
    item: SpatialFinItem,
    modifier: Modifier,
) {
    val imageModel = beamPrimaryArtwork(item)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        if (imageModel != null) {
            AsyncImage(
                model = imageModel,
                contentDescription = item.name,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Text(
                    text = item.name.take(1).uppercase(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun BeamBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun BeamPrimaryActionButton(
    label: String,
    onClick: () -> Unit,
) {
    Button(onClick = onClick) {
        Text(label, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun BeamSecondaryActionButton(
    label: String,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick) {
        Text(label, maxLines = 1, softWrap = false)
    }
}

private fun openServerItem(
    item: SpatialFinItem,
    onOpenLibrary: (UUID, String, CollectionType) -> Unit,
    onOpenShow: (UUID) -> Unit,
    onOpenSeason: (UUID) -> Unit,
    onOpenItem: (UUID) -> Unit,
) {
    when (item) {
        is SpatialFinCollection -> onOpenLibrary(item.id, item.name, item.type)
        is SpatialFinFolder -> onOpenLibrary(item.id, item.name, CollectionType.Folders)
        is SpatialFinShow -> onOpenShow(item.id)
        is SpatialFinSeason -> onOpenSeason(item.id)
        else -> onOpenItem(item.id)
    }
}

@Composable
private fun BeamChaptersRow(
    chapters: List<dev.jdtech.jellyfin.models.SpatialFinChapter>,
    onChapterClick: (dev.jdtech.jellyfin.models.SpatialFinChapter) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Chapters",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(chapters, key = { it.startPosition }) { chapter ->
                BeamChapterCard(chapter = chapter, onClick = { onChapterClick(chapter) })
            }
        }
    }
}

@Composable
private fun BeamChapterCard(
    chapter: dev.jdtech.jellyfin.models.SpatialFinChapter,
    onClick: () -> Unit,
) {
    val title = chapter.name?.takeIf { it.isNotBlank() } ?: formatChapterTime(chapter.startPosition)
    Column(
        modifier = Modifier
            .width(200.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.77f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF161D28)),
        ) {
            if (chapter.imageUri != null) {
                AsyncImage(
                    model = chapter.imageUri,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .align(Alignment.BottomStart)
                    .background(
                        color = Color.Black.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = formatChapterTime(chapter.startPosition),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun beamPeopleOf(item: SpatialFinItem): List<dev.jdtech.jellyfin.models.SpatialFinItemPerson> =
    when (item) {
        is SpatialFinMovie -> item.people
        is SpatialFinEpisode -> item.people
        is SpatialFinShow -> item.people
        else -> emptyList()
    }

private fun formatChapterTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun launchServerItem(
    context: Context,
    item: SpatialFinItem,
    startFromBeginning: Boolean = false,
    mediaSourceIndex: Int? = null,
    maxBitrate: Long? = null,
) {
    val kind =
        when (item) {
            is SpatialFinMovie -> "Movie"
            is SpatialFinEpisode -> "Episode"
            is SpatialFinSeason -> "Season"
            is SpatialFinShow -> "Series"
            is SpatialFinBoxSet -> "BoxSet"
            else -> null
        } ?: return

    context.startActivity(
        BeamPlayerActivity.createIntent(
            context = context,
            itemId = item.id,
            itemKind = kind,
            startFromBeginning = startFromBeginning,
            mediaSourceIndex = mediaSourceIndex,
            maxBitrate = maxBitrate,
        )
    )
}

private fun buildServerItemSubtitle(item: SpatialFinItem): String =
    buildList {
        when (item) {
            is SpatialFinMovie -> add("Movie")
            is SpatialFinEpisode -> {
                add(buildEpisodeLabel(item))
                item.seasonName?.takeIf { !it.isNullOrBlank() }?.let(::add)
            }
            is SpatialFinShow -> add("Series")
            is SpatialFinSeason -> {
                add(if (item.indexNumber > 0) "Season ${item.indexNumber}" else "Season")
                item.seriesName.takeIf { it.isNotBlank() }?.let(::add)
            }
            is SpatialFinBoxSet -> add("Box Set")
            is SpatialFinCollection -> add(item.type.type.replaceFirstChar(Char::titlecase))
            is SpatialFinFolder -> add("Folder")
            else -> add("Item")
        }
        item.originalTitle?.takeIf { it.isNotBlank() && it != item.name }?.let(::add)
        buildProgressLabel(item)?.let(::add)
        buildRemainingLabel(item)?.let(::add)
    }.joinToString(" • ")

private fun buildPrimaryBadge(item: SpatialFinItem): String =
    when (item) {
        is SpatialFinMovie -> "Movie"
        is SpatialFinEpisode -> buildEpisodeLabel(item)
        is SpatialFinShow -> "Series"
        is SpatialFinSeason -> if (item.indexNumber > 0) "Season ${item.indexNumber}" else "Season"
        is SpatialFinBoxSet -> "Collection"
        is SpatialFinCollection -> item.type.type.replaceFirstChar(Char::titlecase)
        is SpatialFinFolder -> "Folder"
        else -> "Item"
    }

private fun buildPlaybackFraction(item: SpatialFinItem): Float? {
    val runtime = item.runtimeTicks
    val position = item.playbackPositionTicks
    if (runtime <= 0L || position <= 0L) return null
    return (position.toFloat() / runtime.toFloat()).coerceIn(0f, 1f)
}

private fun buildBeamSeerrSubtitle(item: SeerrSearchResult): String? {
    val parts = mutableListOf<String>()
    parts += when (item.mediaType) {
        "movie" -> "Movie"
        "tv" -> "Series"
        else -> item.mediaType.replaceFirstChar(Char::titlecase)
    }
    item.releaseDate?.takeIf { it.length >= 4 }?.substring(0, 4)?.let(parts::add)
    item.firstAirDate?.takeIf { it.length >= 4 }?.substring(0, 4)?.let(parts::add)
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
}

@Composable
private fun BeamSeerrStatusBadge(status: Int?) {
    val (text, color) =
        when (status ?: 1) {
            2 -> "Pending" to Color(0xFFFFC107)
            3 -> "Processing" to Color(0xFF2196F3)
            4 -> "Partial" to Color(0xFF8BC34A)
            5 -> "Available" to Color(0xFF4CAF50)
            else -> return
        }
    Box(
        modifier =
            Modifier
                .background(color.copy(alpha = 0.92f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text = text, color = Color.Black, style = MaterialTheme.typography.labelMedium)
    }
}

private fun buildServerItemMeta(item: SpatialFinItem): List<String> {
    val lines = mutableListOf<String>()
    val typeLabel =
        when (item) {
            is SpatialFinMovie -> "Movie"
            is SpatialFinEpisode -> item.seriesName.ifBlank { "Episode" }
            is SpatialFinShow -> "Series"
            is SpatialFinSeason -> item.seriesName.ifBlank { "Season" }
            is SpatialFinBoxSet -> "Box Set"
            is SpatialFinCollection -> item.type.type
            is SpatialFinFolder -> "Folder"
            else -> "Item"
        }
    lines += typeLabel
    when (item) {
        is SpatialFinEpisode -> lines += "${buildEpisodeLabel(item)}${item.seasonName?.takeIf { !it.isNullOrBlank() }?.let { " • $it" } ?: ""}"
        is SpatialFinSeason -> if (item.indexNumber > 0) lines += "Season ${item.indexNumber}"
        else -> Unit
    }
    item.originalTitle?.takeIf { it.isNotBlank() && it != item.name }?.let { lines += "Original title: $it" }
    formatRuntime(item.runtimeTicks)?.let { lines += "Runtime: $it" }
    if (item.playbackPositionTicks > 0L) {
        formatRuntime(item.playbackPositionTicks)?.let { lines += "Progress: $it watched" }
        buildRemainingLabel(item)?.let { lines += it }
    }
    item.unplayedItemCount?.let { if (it > 0) lines += "Episodes left: $it" }
    if (item.favorite) lines += "Favorite"
    if (item.played) lines += "Played"
    lines += item.ratings.mapNotNull { rating ->
        rating.value.takeIf { it.isNotBlank() }?.let { "${rating.type.label}: $it" }
    }
    return lines
}

private fun buildEpisodeLabel(episode: SpatialFinEpisode): String {
    val seasonPart = if (episode.parentIndexNumber > 0) "S${episode.parentIndexNumber}" else "S?"
    val episodePart = if (episode.indexNumber > 0) "E${episode.indexNumber}" else "E?"
    return "$seasonPart$episodePart"
}

private fun buildProgressLabel(item: SpatialFinItem): String? {
    if (item.played) return "Played"
    val position = item.playbackPositionTicks
    val runtime = item.runtimeTicks
    if (position <= 0L || runtime <= 0L) return null
    val percent = ((position.toDouble() / runtime.toDouble()) * 100.0).toInt().coerceIn(1, 99)
    return "$percent% watched"
}

private fun buildRemainingLabel(item: SpatialFinItem): String? {
    if (item.played) return null
    val runtime = item.runtimeTicks
    val position = item.playbackPositionTicks
    if (runtime <= 0L || position <= 0L || position >= runtime) return null
    return formatRuntime(runtime - position)?.let { "$it left" }
}

private fun formatRuntime(ticks: Long): String? {
    if (ticks <= 0L) return null
    val totalMinutes = ticks / 10_000_000L / 60L
    if (totalMinutes <= 0L) return null
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}
