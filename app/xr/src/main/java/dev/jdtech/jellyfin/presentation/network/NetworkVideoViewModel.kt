package dev.jdtech.jellyfin.presentation.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.NetworkVideoItem
import dev.jdtech.jellyfin.repository.NetworkMediaRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NetworkVideoState(
    val item: NetworkVideoItem? = null,
    val isLoading: Boolean = false,
    val error: Throwable? = null,
)

@HiltViewModel
class NetworkVideoViewModel @Inject constructor(
    private val repository: NetworkMediaRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(NetworkVideoState())
    val state = _state.asStateFlow()

    fun load(videoId: String) {
        viewModelScope.launch {
            _state.emit(_state.value.copy(isLoading = true, error = null))
            try {
                _state.emit(
                    _state.value.copy(
                        item = repository.getVideo(videoId),
                        isLoading = false,
                    )
                )
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e, isLoading = false))
            }
        }
    }

    fun markPlayed(videoId: String, played: Boolean) {
        viewModelScope.launch {
            repository.markPlayed(videoId, played)
            load(videoId)
        }
    }
}
