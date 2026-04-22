package dev.jdtech.jellyfin.film.presentation.movie

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.film.domain.VideoMetadataParser
import dev.jdtech.jellyfin.models.SpatialFinItemPerson
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.movieVersionGroupKey
import dev.jdtech.jellyfin.models.versionOptionsFrom
import dev.jdtech.jellyfin.repository.JellyfinRealtimeEvent
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.PersonKind

@HiltViewModel
class MovieViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    val appPreferences: AppPreferences,
    private val videoMetadataParser: VideoMetadataParser,
) : ViewModel() {
    private val _state = MutableStateFlow(MovieState())
    val state = _state.asStateFlow()
    private var hasLoadedMovie = false

    lateinit var movieId: UUID

    init {
        observeRealtimeEvents()
    }

    fun loadMovie(movieId: UUID) {
        hasLoadedMovie = true
        this.movieId = movieId
        viewModelScope.launch {
            try {
                val movie = repository.getMovie(movieId)
                val videoMetadata = videoMetadataParser.parse(movie.sources.first())
                val availableVersions =
                    loadAvailableVersions(movie)
                val actors = getActors(movie)
                val director = getDirector(movie)
                val writers = getWriters(movie)
                val displayExtraInfo = appPreferences.getValue(appPreferences.displayExtraInfo)
                val displayRatings = appPreferences.getValue(appPreferences.displayRatings)
                _state.emit(
                    _state.value.copy(
                        movie = movie,
                        availableVersions = availableVersions,
                        videoMetadata = videoMetadata,
                        actors = actors,
                        director = director,
                        writers = writers,
                        displayExtraInfo = displayExtraInfo,
                        displayRatings = displayRatings,
                    )
                )
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e))
            }
        }
    }

    private fun observeRealtimeEvents() {
        viewModelScope.launch {
            repository.observeRealtimeEvents()
                .debounce(300)
                .collect { event ->
                    if (!hasLoadedMovie || !::movieId.isInitialized) return@collect
                    if (event.affects(movieId) || event is JellyfinRealtimeEvent.LibraryChanged) {
                        loadMovie(movieId)
                    }
                }
        }
    }

    private suspend fun loadAvailableVersions(movie: SpatialFinMovie): List<SpatialFinMovie> {
        val targetGroupKey = movie.movieVersionGroupKey()
        val hydratedCandidates =
            repository
                .getSearchItems(movie.name)
                .filterIsInstance<SpatialFinMovie>()
                .filter { candidate ->
                    targetGroupKey != null && candidate.movieVersionGroupKey() == targetGroupKey
                }
                .mapNotNull { candidate ->
                    runCatching { repository.getMovie(candidate.id) }
                        .getOrElse { candidate }
                }

        return movie.versionOptionsFrom(hydratedCandidates)
    }

    private suspend fun getActors(item: SpatialFinMovie): List<SpatialFinItemPerson> {
        return withContext(Dispatchers.Default) {
            item.people.filter { it.type == PersonKind.ACTOR }
        }
    }

    private suspend fun getDirector(item: SpatialFinMovie): SpatialFinItemPerson? {
        return withContext(Dispatchers.Default) {
            item.people.firstOrNull { it.type == PersonKind.DIRECTOR }
        }
    }

    private suspend fun getWriters(item: SpatialFinMovie): List<SpatialFinItemPerson> {
        return withContext(Dispatchers.Default) {
            item.people.filter { it.type == PersonKind.WRITER }
        }
    }

    fun onAction(action: MovieAction) {
        when (action) {
            is MovieAction.MarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsPlayed(movieId)
                    loadMovie(movieId)
                }
            }
            is MovieAction.UnmarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsUnplayed(movieId)
                    loadMovie(movieId)
                }
            }
            is MovieAction.MarkAsFavorite -> {
                viewModelScope.launch {
                    repository.markAsFavorite(movieId)
                    loadMovie(movieId)
                }
            }
            is MovieAction.UnmarkAsFavorite -> {
                viewModelScope.launch {
                    repository.unmarkAsFavorite(movieId)
                    loadMovie(movieId)
                }
            }
            is MovieAction.ReloadAfterMetadataEdit -> {
                // Jellyfin's metadata refresh is queued server-side after our
                // updateItem call, so a reload right now would still read the
                // stale BaseItemDto. A 5s delay covers typical refresh times
                // for a single item (IMDb/TMDB round trip + local DB write);
                // on a slow server the user can scroll out and back in to pull
                // again. Realtime-event-driven reload via observeRealtimeEvents
                // is not guaranteed — Jellyfin doesn't emit LibraryChanged for
                // single-item metadata refreshes, only for adds/removes.
                viewModelScope.launch {
                    kotlinx.coroutines.delay(METADATA_REFRESH_WAIT_MS)
                    loadMovie(movieId)
                }
            }
            else -> Unit
        }
    }

    companion object {
        private const val METADATA_REFRESH_WAIT_MS = 5_000L
    }
}
