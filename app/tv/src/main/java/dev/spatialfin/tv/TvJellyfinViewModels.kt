package dev.spatialfin.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.core.presentation.downloader.BulkDownloadState
import dev.jdtech.jellyfin.models.BulkDownloadSettings
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.models.movieVersionGroupKey
import dev.jdtech.jellyfin.models.versionOptionsFrom
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.utils.BulkDownloadResult
import dev.jdtech.jellyfin.utils.Downloader
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TvItemDetailState(
    val item: SpatialFinItem? = null,
    val availableVersions: List<SpatialFinMovie> = emptyList(),
    val isLoading: Boolean = false,
    val error: Throwable? = null,
)

@HiltViewModel
class TvItemDetailViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(TvItemDetailState())
    val state = _state.asStateFlow()

    fun load(itemId: UUID) {
        viewModelScope.launch {
            _state.emit(TvItemDetailState(isLoading = true))
            runCatching { repository.getItem(itemId) }
                .onSuccess { item ->
                    val versions = if (item is SpatialFinMovie) loadAvailableVersions(item) else emptyList()
                    _state.emit(
                        TvItemDetailState(
                            item = item,
                            availableVersions = versions,
                            isLoading = false,
                        )
                    )
                }
                .onFailure { error ->
                    _state.emit(TvItemDetailState(isLoading = false, error = error))
                }
        }
    }

    private suspend fun loadAvailableVersions(movie: SpatialFinMovie): List<SpatialFinMovie> {
        val targetGroupKey = movie.movieVersionGroupKey() ?: return listOf(movie)
        return runCatching {
            val candidates =
                repository
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

data class TvShowState(
    val show: SpatialFinShow? = null,
    val seasons: List<SpatialFinSeason> = emptyList(),
    val nextUp: SpatialFinEpisode? = null,
    val isLoading: Boolean = false,
    val error: Throwable? = null,
    val bulkDownload: BulkDownloadState = BulkDownloadState(),
)

@HiltViewModel
class TvShowViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val downloader: Downloader,
) : ViewModel() {
    private val _state = MutableStateFlow(TvShowState())
    val state = _state.asStateFlow()

    fun load(showId: UUID) {
        viewModelScope.launch {
            _state.emit(TvShowState(isLoading = true))
            runCatching {
                Triple(
                    repository.getShow(showId),
                    repository.getSeasons(showId),
                    repository.getNextUp(showId).firstOrNull(),
                )
            }.onSuccess { (show, seasons, nextUp) ->
                _state.emit(
                    TvShowState(
                        show = show,
                        seasons = seasons,
                        nextUp = nextUp,
                        isLoading = false,
                    )
                )
            }.onFailure { error ->
                _state.emit(TvShowState(isLoading = false, error = error))
            }
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

data class TvSeasonState(
    val season: SpatialFinSeason? = null,
    val episodes: List<SpatialFinEpisode> = emptyList(),
    val isLoading: Boolean = false,
    val error: Throwable? = null,
    val bulkDownload: BulkDownloadState = BulkDownloadState(),
)

@HiltViewModel
class TvSeasonViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val downloader: Downloader,
) : ViewModel() {
    private val _state = MutableStateFlow(TvSeasonState())
    val state = _state.asStateFlow()

    fun load(seasonId: UUID) {
        viewModelScope.launch {
            _state.emit(TvSeasonState(isLoading = true))
            runCatching {
                val season = repository.getSeason(seasonId)
                season to repository.getEpisodes(seriesId = season.seriesId, seasonId = seasonId, limit = 200)
            }.onSuccess { (season, episodes) ->
                _state.emit(
                    TvSeasonState(
                        season = season,
                        episodes = episodes,
                        isLoading = false,
                    )
                )
            }.onFailure { error ->
                _state.emit(TvSeasonState(isLoading = false, error = error))
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

data class TvSearchState(
    val query: String = "",
    val items: List<SpatialFinItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: Throwable? = null,
    val hasSearched: Boolean = false,
)

@HiltViewModel
class TvSearchViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(TvSearchState())
    val state = _state.asStateFlow()

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    fun search() {
        val query = state.value.query.trim()
        viewModelScope.launch {
            if (query.isBlank()) {
                _state.emit(TvSearchState())
                return@launch
            }
            _state.emit(
                _state.value.copy(
                    isLoading = true,
                    error = null,
                    hasSearched = true,
                )
            )
            runCatching { repository.getSearchItems(query) }
                .onSuccess { items ->
                    _state.emit(
                        _state.value.copy(
                            items = items,
                            isLoading = false,
                            error = null,
                            hasSearched = true,
                        )
                    )
                }
                .onFailure { error ->
                    _state.emit(
                        _state.value.copy(
                            items = emptyList(),
                            isLoading = false,
                            error = error,
                            hasSearched = true,
                        )
                    )
                }
        }
    }
}
