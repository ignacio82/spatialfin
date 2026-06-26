package dev.spatialfin.unified.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.SearchResult
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerMediaItem
import dev.jdtech.jellyfin.data.musicassistant.repository.MusicAssistantRepository
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Standalone Music Assistant search screen — owns its own ViewModel rather
 * than reusing the Jellyfin search VM in [modes/film/.../SearchViewModel] so
 * the MA flow doesn't fight with Jellyfin's debounce, paging, or Seerr
 * fallback. The Jellyfin VM still handles MA result rows inside the unified
 * search bar; this one is for the dedicated "Music" search route.
 *
 * Search is **debounced 350 ms** to stop us thrashing MA on every keystroke
 * (the official mobile app uses 300 ms; 350 is a hair slower to give voice
 * dictation a chance to settle). Each new query cancels the prior request via
 * the [Job] in [searchJob] — without that, a slow request from "metalli"
 * would race ahead of the fast "metallica" reply and replace the right
 * results with stale ones.
 */
@HiltViewModel
class MaSearchViewModel @Inject constructor(
    private val repository: MusicAssistantRepository,
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _libraryOnly = MutableStateFlow(false)
    val libraryOnly: StateFlow<Boolean> = _libraryOnly.asStateFlow()

    private val _state: MutableStateFlow<MaSearchState> = MutableStateFlow(MaSearchState.Empty)
    val state: StateFlow<MaSearchState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        observeQueryChanges()
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setLibraryOnly(value: Boolean) {
        _libraryOnly.value = value
    }

    fun retry() {
        runSearch(_query.value, _libraryOnly.value)
    }

    @OptIn(FlowPreview::class)
    private fun observeQueryChanges() {
        viewModelScope.launch {
            _query
                .drop(1) // The initial empty value would otherwise immediately
                         // flip us to Empty before the user typed anything.
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .onEach { runSearch(it, _libraryOnly.value) }
                .collect {}
        }
        viewModelScope.launch {
            _libraryOnly.collect { runSearch(_query.value, it) }
        }
    }

    private fun runSearch(query: String, libraryOnly: Boolean) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _state.value = MaSearchState.Empty
            return
        }
        _state.value = MaSearchState.Loading(trimmed)
        searchJob = viewModelScope.launch {
            val result = try {
                repository.search(
                    query = trimmed,
                    mediaTypes = ALL_MEDIA_TYPES,
                    limit = RESULT_LIMIT,
                )
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "search failed for '%s'", trimmed)
                _state.value = MaSearchState.Error(trimmed, e.message ?: "Search failed")
                return@launch
            }
            _state.value = if (result == null || result.isEmpty()) {
                MaSearchState.Empty(lastQuery = trimmed)
            } else {
                MaSearchState.Results(trimmed, result.filterByLibrary(libraryOnly))
            }
        }
    }

    private fun SearchResult.filterByLibrary(libraryOnly: Boolean): SearchResult =
        if (!libraryOnly) this
        else copy(
            tracks = tracks.filter { it.isInLibrary() },
            albums = albums.filter { it.isInLibrary() },
            artists = artists.filter { it.isInLibrary() },
            playlists = playlists.filter { it.isInLibrary() },
            podcasts = podcasts.filter { it.isInLibrary() },
            radio = radio.filter { it.isInLibrary() },
        )

    private fun ServerMediaItem.isInLibrary(): Boolean =
        uri?.startsWith("library://") == true || favorite == true

    private fun SearchResult.isEmpty(): Boolean =
        tracks.isEmpty() && albums.isEmpty() && artists.isEmpty() &&
            playlists.isEmpty() && podcasts.isEmpty() && radio.isEmpty()

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 350L
        private const val RESULT_LIMIT = 30
        private const val TAG = "MaSearch"
        private val ALL_MEDIA_TYPES = listOf(
            "track", "album", "artist", "playlist", "podcast", "audiobook", "radio",
        )
    }
}

sealed interface MaSearchState {
    data class Empty(val lastQuery: String = "") : MaSearchState
    data class Loading(val query: String) : MaSearchState
    data class Results(val query: String, val result: SearchResult) : MaSearchState
    data class Error(val query: String, val message: String) : MaSearchState

    companion object {
        val Empty = Empty()
    }
}
