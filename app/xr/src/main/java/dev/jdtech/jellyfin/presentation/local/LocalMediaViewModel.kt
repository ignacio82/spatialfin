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

data class LocalMediaState(
    val items: List<LocalVideoItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: Throwable? = null,
)

@HiltViewModel
class LocalMediaViewModel @Inject constructor(private val repository: LocalMediaRepository) : ViewModel() {
    private val _state = MutableStateFlow(LocalMediaState())
    val state = _state.asStateFlow()

    fun loadVideos() {
        viewModelScope.launch {
            _state.emit(_state.value.copy(isLoading = true, error = null))
            try {
                _state.emit(_state.value.copy(items = repository.getVideos(), isLoading = false))
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e, isLoading = false))
            }
        }
    }
}
