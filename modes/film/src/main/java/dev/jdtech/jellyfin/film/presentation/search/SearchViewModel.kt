package dev.jdtech.jellyfin.film.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.repository.JellyfinRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class SearchViewModel @Inject constructor(private val repository: JellyfinRepository) :
    ViewModel() {
    private val _state = MutableStateFlow(SearchState())
    val state = _state.asStateFlow()

    var currentJob: Job? = null

    private fun search(query: String) {
        currentJob?.cancel()
        currentJob =
            viewModelScope.launch {
                val trimmedQuery = query.trim()
                try {
                    if (trimmedQuery.isBlank()) {
                        _state.emit(SearchState())
                        return@launch
                    }

                    _state.emit(
                        SearchState(
                            query = trimmedQuery,
                            items = emptyList(),
                            loading = true,
                            hasSearched = true,
                            errorMessage = null,
                        )
                    )
                    val items = repository.getSearchItems(trimmedQuery)

                    Timber.i(
                        "SEARCH: query=%s results=%d",
                        trimmedQuery,
                        items.size,
                    )
                    _state.emit(
                        SearchState(
                            query = trimmedQuery,
                            items = items,
                            loading = false,
                            hasSearched = true,
                            errorMessage = null,
                        )
                    )
                } catch (_: CancellationException) {} catch (e: Exception) {
                    Timber.e(e, "SEARCH: query failed: %s", trimmedQuery)
                    _state.emit(
                        SearchState(
                            query = trimmedQuery,
                            items = emptyList(),
                            loading = false,
                            hasSearched = true,
                            errorMessage = e.message ?: "Search failed",
                        )
                    )
                }
            }
    }

    fun onAction(action: SearchAction) {
        when (action) {
            is SearchAction.Search -> {
                search(query = action.query)
            }
            else -> Unit
        }
    }
}
