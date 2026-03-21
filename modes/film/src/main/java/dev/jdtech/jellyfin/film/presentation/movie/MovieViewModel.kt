package dev.jdtech.jellyfin.film.presentation.movie

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.film.domain.VideoMetadataParser
import dev.jdtech.jellyfin.models.SpatialFinItemPerson
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.movieVersionGroupKey
import dev.jdtech.jellyfin.models.versionOptionsFrom
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    lateinit var movieId: UUID

    fun loadMovie(movieId: UUID) {
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
            else -> Unit
        }
    }
}
