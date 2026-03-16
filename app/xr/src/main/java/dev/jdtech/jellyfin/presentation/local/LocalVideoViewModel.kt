package dev.jdtech.jellyfin.presentation.local

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.LocalVideoItem
import dev.jdtech.jellyfin.repository.LocalMediaRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LocalVideoState(
    val item: LocalVideoItem? = null,
    val isLoading: Boolean = false,
    val error: Throwable? = null,
)

@HiltViewModel
class LocalVideoViewModel @Inject constructor(private val repository: LocalMediaRepository) : ViewModel() {
    private val _state = MutableStateFlow(LocalVideoState())
    val state = _state.asStateFlow()

    fun load(mediaStoreId: Long) {
        viewModelScope.launch {
            _state.emit(_state.value.copy(isLoading = true, error = null))
            try {
                _state.emit(
                    _state.value.copy(
                        item = repository.getVideo(mediaStoreId),
                        isLoading = false,
                    )
                )
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e, isLoading = false))
            }
        }
    }

    fun markPlayed(mediaStoreId: Long, played: Boolean) {
        viewModelScope.launch {
            repository.markPlayed(mediaStoreId, played)
            load(mediaStoreId)
        }
    }
}
