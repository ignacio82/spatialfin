package dev.jdtech.jellyfin.film.presentation.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.session.ActiveSessionBus
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class MediaViewModel @Inject constructor(
    private val repository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    private val activeSessionBus: ActiveSessionBus,
) : ViewModel() {
    private val _state = MutableStateFlow(MediaState())
    val state = _state.asStateFlow()
    private var hasLoadedData = false

    init {
        observeSessionChanges()
    }

    private fun observeSessionChanges() {
        viewModelScope.launch {
            // Reload only after the screen has been shown at least once.
            // Otherwise we'd race the first LaunchedEffect-driven load on
            // app launch and emit duplicate fetches.
            activeSessionBus.events.collect { if (hasLoadedData) loadData() }
        }
    }

    fun loadData() {
        hasLoadedData = true
        viewModelScope.launch {
            _state.emit(_state.value.copy(isLoading = true, error = null))
            try {
                val libraries = repository.getLibraries()
                val displayRatings = appPreferences.getValue(appPreferences.displayRatings)
                _state.emit(_state.value.copy(libraries = libraries, displayRatings = displayRatings))
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e))
            }
            _state.emit(_state.value.copy(isLoading = false))
        }
    }

    fun onAction(action: MediaAction) {
        when (action) {
            is MediaAction.OnRetryClick -> {
                loadData()
            }
            else -> Unit
        }
    }
}
