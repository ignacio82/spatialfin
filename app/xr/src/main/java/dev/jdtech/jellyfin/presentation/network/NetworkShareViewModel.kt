package dev.jdtech.jellyfin.presentation.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.NetworkShareDto
import dev.jdtech.jellyfin.models.NetworkVideoItem
import dev.jdtech.jellyfin.repository.NetworkMediaRepository
import javax.inject.Inject
import timber.log.Timber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NetworkShareState(
    val share: NetworkShareDto? = null,
    val movies: List<NetworkVideoItem> = emptyList(),
    val tvShows: Map<String, List<NetworkVideoItem>> = emptyMap(),
    val uncategorized: List<NetworkVideoItem> = emptyList(),
    val isLoading: Boolean = false,
    val isScanning: Boolean = false,
    val error: Throwable? = null,
)

internal data class NetworkShareSections(
    val movies: List<NetworkVideoItem>,
    val tvShows: Map<String, List<NetworkVideoItem>>,
    val uncategorized: List<NetworkVideoItem>,
)

internal fun categorizeNetworkVideos(videos: List<NetworkVideoItem>): NetworkShareSections {
    val uniqueVideos = videos.distinctBy { it.networkVideoId }
    val tvShows = uniqueVideos
        .filter { !it.seriesGroupKey.isNullOrBlank() }
        .groupBy { it.seriesGroupKey!! }
    val tvIds = tvShows.values
        .flatten()
        .mapTo(mutableSetOf()) { it.networkVideoId }
    val movies = uniqueVideos.filter { it.tmdbType == "movie" && it.networkVideoId !in tvIds }
    val movieIds = movies.mapTo(mutableSetOf()) { it.networkVideoId }
    val uncategorized = uniqueVideos.filter { item ->
        item.networkVideoId !in tvIds && item.networkVideoId !in movieIds
    }

    return NetworkShareSections(
        movies = movies,
        tvShows = tvShows,
        uncategorized = uncategorized,
    )
}

@HiltViewModel
class NetworkShareViewModel @Inject constructor(
    private val repository: NetworkMediaRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(NetworkShareState())
    val state = _state.asStateFlow()

    fun load(shareId: String) {
        viewModelScope.launch {
            _state.emit(_state.value.copy(isLoading = true, error = null))
            try {
                val shares = repository.getShares()
                val share = shares.find { it.id == shareId }
                val videos = repository.getVideosByShare(shareId)
                val sections = categorizeNetworkVideos(videos)

                _state.emit(
                    _state.value.copy(
                        share = share,
                        movies = sections.movies,
                        tvShows = sections.tvShows,
                        uncategorized = sections.uncategorized,
                        isLoading = false,
                    )
                )
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e, isLoading = false))
            }
        }
    }

    fun scanShare(shareId: String) {
        viewModelScope.launch {
            _state.emit(_state.value.copy(isScanning = true, error = null))
            runCatching {
                repository.scanShare(shareId)
                repository.enrichMetadata(shareId)
            }.onSuccess {
                _state.emit(_state.value.copy(isScanning = false))
                load(shareId)
            }.onFailure { e ->
                Timber.e(e, "Failed to scan network share $shareId")
                _state.emit(_state.value.copy(isScanning = false, error = e))
            }
        }
    }

    fun removeShare(shareId: String) {
        viewModelScope.launch {
            repository.removeShare(shareId)
        }
    }
}
