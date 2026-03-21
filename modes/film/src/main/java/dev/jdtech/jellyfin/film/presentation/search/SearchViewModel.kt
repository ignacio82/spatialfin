package dev.jdtech.jellyfin.film.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.api.SeerrApi
import dev.jdtech.jellyfin.api.SeerrMediaInfo
import dev.jdtech.jellyfin.api.SeerrSearchResult
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    private val seerrApi: SeerrApi
) :
    ViewModel() {
    private val _state = MutableStateFlow(SearchState())
    val state = _state.asStateFlow()

    var currentJob: Job? = null

    private fun search(query: String) {
        currentJob?.cancel()
        currentJob =
            viewModelScope.launch {
                val trimmedQuery = query.trim()
                val displayRatings = appPreferences.getValue(appPreferences.displayRatings)
                try {
                    if (trimmedQuery.isBlank()) {
                        _state.emit(SearchState(displayRatings = displayRatings))
                        return@launch
                    }

                    _state.emit(
                        SearchState(
                            query = trimmedQuery,
                            items = emptyList(),
                            seerrItems = emptyList(),
                            displayRatings = displayRatings,
                            loading = true,
                            hasSearched = true,
                            errorMessage = null,
                        )
                    )
                    
                    val jellyfinSearch = async { repository.getSearchItems(trimmedQuery) }
                    val seerrSearch = async { seerrApi.search(trimmedQuery) }

                    val items = jellyfinSearch.await()
                    val seerrResults = seerrSearch.await()

                    Timber.i(
                        "SEARCH: query=%s jellyfin=%d seerr=%d",
                        trimmedQuery,
                        items.size,
                        seerrResults?.results?.size ?: 0
                    )
                    _state.emit(
                        SearchState(
                            query = trimmedQuery,
                            items = items,
                            seerrItems = seerrResults?.results ?: emptyList(),
                            displayRatings = displayRatings,
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
                            seerrItems = emptyList(),
                            displayRatings = displayRatings,
                            loading = false,
                            hasSearched = true,
                            errorMessage = e.message ?: "Search failed",
                        )
                    )
                }
            }
    }

    private fun requestSeerrItem(item: SeerrSearchResult) {
        viewModelScope.launch {
            val success = seerrApi.createRequest(item.mediaType, item.tmdbId)
            if (success) {
                // Refresh search to show updated status if possible, 
                // but Seerr API might not reflect it immediately.
                // For now just toast or log.
                Timber.i("Successfully requested ${item.title ?: item.name}")
                // Optionally update state to show it was requested
                val updatedSeerrItems = _state.value.seerrItems.map {
                    if (it.tmdbId == item.tmdbId && it.mediaType == item.mediaType) {
                        it.copy(mediaInfo = it.mediaInfo?.copy(status = 2) ?: SeerrMediaInfo(status = 2))
                    } else it
                }
                _state.emit(_state.value.copy(seerrItems = updatedSeerrItems))
            } else {
                Timber.e("Failed to request ${item.title ?: item.name}")
            }
        }
    }

    fun onAction(action: SearchAction) {
        when (action) {
            is SearchAction.Search -> {
                search(query = action.query)
            }
            is SearchAction.RequestSeerrItem -> {
                requestSeerrItem(action.item)
            }
            else -> Unit
        }
    }
}
