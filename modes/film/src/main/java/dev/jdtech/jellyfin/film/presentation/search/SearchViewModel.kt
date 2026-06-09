package dev.jdtech.jellyfin.film.presentation.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.api.SeerrApi
import dev.jdtech.jellyfin.api.SeerrMediaInfo
import dev.jdtech.jellyfin.api.SeerrSearchResult
import dev.jdtech.jellyfin.data.musicassistant.api.ServiceClient
import dev.jdtech.jellyfin.data.musicassistant.repository.MaSessionRepository
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverService
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.data.musicassistant.repository.MusicAssistantRepository
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
    private val seerrApi: SeerrApi,
    private val maRepository: MusicAssistantRepository,
    private val maSession: MaSessionRepository,
    private val maServiceClient: ServiceClient,
    @ApplicationContext private val appContext: Context,
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
                            maItems = null,
                            displayRatings = displayRatings,
                            loading = true,
                            hasSearched = true,
                            errorMessage = null,
                        )
                    )
                    
                    val jellyfinSearch = async { repository.getSearchItems(trimmedQuery) }
                    val seerrSearch = async { seerrApi.search(trimmedQuery) }
                    val maSearch = async { maRepository.search(trimmedQuery) }

                    val items = jellyfinSearch.await()
                    val seerrResults = seerrSearch.await()
                    val maResults = maSearch.await()
                    val filteredSeerrResults = seerrResults?.results?.filter { 
                        it.mediaId != null && it.mediaType != "person" && it.mediaType != "collection"
                    } ?: emptyList()

                    Timber.i(
                        "SEARCH: query=%s jellyfin=%d seerr=%s",
                        trimmedQuery,
                        items.size,
                        if (seerrResults == null) "error" else filteredSeerrResults.size.toString()
                    )
                    _state.emit(
                        SearchState(
                            query = trimmedQuery,
                            items = items,
                            seerrItems = filteredSeerrResults,
                            maItems = maResults,
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
                            maItems = null,
                            displayRatings = displayRatings,
                            loading = false,
                            hasSearched = true,
                            errorMessage = e.message ?: "Search failed",
                        )
                    )
                }
            }
    }

    private fun requestSeerrItem(item: SeerrSearchResult, is4k: Boolean) {
        val mediaId = item.mediaId ?: return
        viewModelScope.launch {
            val success = seerrApi.createRequest(item.mediaType, mediaId, is4k, item.tvdbId)
            if (success) {
                // Refresh search to show updated status if possible, 
                // but Seerr API might not reflect it immediately.
                // For now just toast or log.
                Timber.i("Successfully requested ${item.title ?: item.name} (4K: $is4k)")
                // Optionally update state to show it was requested
                val updatedSeerrItems = _state.value.seerrItems.map {
                    if (it.mediaId == mediaId && it.mediaType == item.mediaType) {
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
                requestSeerrItem(action.item, action.is4k)
            }
            is SearchAction.OnMaItemClick -> {
                val item = action.item
                val uri = item.uri
                if (uri.isNullOrBlank()) {
                    Timber.w("MA search tap: item has no uri (item_id=%s)", item.itemId)
                } else {
                    val artwork = (item.image?.path ?: item.metadata?.images?.firstOrNull()?.path)
                        ?.let(maServiceClient::rebaseServerImageUrl)
                    maSession.reportOptimisticPlay(
                        uri = uri,
                        title = item.name,
                        artist = item.artists?.firstOrNull()?.name ?: item.album?.name,
                        artworkUrl = artwork,
                        targetPlayerId = null,
                    )
                    SendspinReceiverService.playMusicAssistantMedia(appContext, uri)
                }
            }
            else -> Unit
        }
    }
}
