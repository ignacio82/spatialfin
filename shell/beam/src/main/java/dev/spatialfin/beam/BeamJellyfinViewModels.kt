package dev.spatialfin.beam

import androidx.compose.foundation.lazy.items
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.api.SeerrApi
import dev.jdtech.jellyfin.api.SeerrMediaInfo
import dev.jdtech.jellyfin.api.SeerrSearchResult
import dev.jdtech.jellyfin.core.presentation.downloader.BulkDownloadState
import dev.jdtech.jellyfin.models.BulkDownloadSettings
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.models.browsableItemKinds
import dev.jdtech.jellyfin.models.movieVersionGroupKey
import dev.jdtech.jellyfin.models.versionOptionsFrom
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.utils.BulkDownloadResult
import dev.jdtech.jellyfin.utils.Downloader
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Library / item-detail / show / season / search ViewModels and their UI states.
 *
 * Split out of BeamJellyfinScreens.kt, which had grown past 4000 lines. Same
 * package, so nothing here changed except its file.
 */
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
            val includeTypes = type.browsableItemKinds(foldersFirst = true)
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
    /** Exposed for UI callers that need to build share URLs etc. */
    fun serverBaseUrl(): String = repository.getBaseUrl()

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

    fun refreshMetadata() {
        val current = _state.value.item ?: return
        viewModelScope.launch {
            runCatching { repository.refreshItemMetadata(current.id) }
            // Metadata refresh is async on the server side — give it a few seconds then
            // reload the detail page to pick up the refreshed fields.
            kotlinx.coroutines.delay(3_000L)
            load(current.id)
        }
    }

    /** Emits true via [_deletedChannel] on success so the screen can pop back. */
    private val _deletedChannel = kotlinx.coroutines.channels.Channel<Boolean>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val deletedEvents = _deletedChannel.receiveAsFlow()
    fun deleteItem() {
        val current = _state.value.item ?: return
        viewModelScope.launch {
            val deleted = runCatching { repository.deleteItem(current.id) }.getOrElse { false }
            _deletedChannel.send(deleted)
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
