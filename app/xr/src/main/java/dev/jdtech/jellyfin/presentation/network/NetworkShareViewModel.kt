package dev.jdtech.jellyfin.presentation.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.NetworkShareDto
import dev.jdtech.jellyfin.models.NetworkVideoItem
import dev.jdtech.jellyfin.repository.NetworkMediaRepository
import javax.inject.Inject
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

                val movies = videos.filter { it.tmdbType == "movie" || (it.seasonNumber == null && it.seriesGroupKey == null) }
                val tvGrouped = videos.filter { it.seriesGroupKey != null }
                    .groupBy { it.seriesGroupKey!! }
                val uncategorized = videos.filter { it.tmdbType == null && it.seasonNumber == null && it.seriesGroupKey == null }

                _state.emit(
                    _state.value.copy(
                        share = share,
                        movies = movies,
                        tvShows = tvGrouped,
                        uncategorized = uncategorized,
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
            _state.emit(_state.value.copy(isScanning = true))
            try {
                repository.scanShare(shareId)
                repository.enrichMetadata(shareId)
                _state.emit(_state.value.copy(isScanning = false))
                load(shareId)
            } catch (e: Exception) {
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
