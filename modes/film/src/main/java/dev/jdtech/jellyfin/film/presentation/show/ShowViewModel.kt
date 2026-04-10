package dev.jdtech.jellyfin.film.presentation.show

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItemPerson
import dev.jdtech.jellyfin.models.SpatialFinShow
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
class ShowViewModel @Inject constructor(
    private val repository: JellyfinRepository,
    private val appPreferences: AppPreferences
) : ViewModel() {
    private val _state = MutableStateFlow(ShowState())
    val state = _state.asStateFlow()
    private var hasLoadedShow = false

    lateinit var showId: UUID

    init {
        observeRealtimeEvents()
    }

    fun loadShow(showId: UUID) {
        hasLoadedShow = true
        this.showId = showId
        viewModelScope.launch {
            try {
                val show = repository.getShow(showId)
                val nextUp = getNextUp(showId)
                val seasons = repository.getSeasons(showId)
                val actors = getActors(show)
                val director = getDirector(show)
                val writers = getWriters(show)
                val displayRatings = appPreferences.getValue(appPreferences.displayRatings)
                _state.emit(
                    _state.value.copy(
                        show = show,
                        nextUp = nextUp,
                        seasons = seasons,
                        actors = actors,
                        director = director,
                        writers = writers,
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
                    if (!hasLoadedShow || !::showId.isInitialized) return@collect
                    val relatedIds =
                        buildSet {
                            add(showId)
                            _state.value.nextUp?.id?.let(::add)
                            _state.value.seasons.forEach { season -> add(season.id) }
                        }
                    if (event.itemIds.any(relatedIds::contains) || event is JellyfinRealtimeEvent.LibraryChanged) {
                        loadShow(showId)
                    }
                }
        }
    }

    private suspend fun getNextUp(showId: UUID): SpatialFinEpisode? {
        val nextUpItems = repository.getNextUp(showId)
        return nextUpItems.getOrNull(0)
    }

    private suspend fun getActors(item: SpatialFinShow): List<SpatialFinItemPerson> {
        return withContext(Dispatchers.Default) {
            item.people.filter { it.type == PersonKind.ACTOR }
        }
    }

    private suspend fun getDirector(item: SpatialFinShow): SpatialFinItemPerson? {
        return withContext(Dispatchers.Default) {
            item.people.firstOrNull { it.type == PersonKind.DIRECTOR }
        }
    }

    private suspend fun getWriters(item: SpatialFinShow): List<SpatialFinItemPerson> {
        return withContext(Dispatchers.Default) {
            item.people.filter { it.type == PersonKind.WRITER }
        }
    }

    fun onAction(action: ShowAction) {
        when (action) {
            is ShowAction.MarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsPlayed(showId)
                    loadShow(showId)
                }
            }
            is ShowAction.UnmarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsUnplayed(showId)
                    loadShow(showId)
                }
            }
            is ShowAction.MarkAsFavorite -> {
                viewModelScope.launch {
                    repository.markAsFavorite(showId)
                    loadShow(showId)
                }
            }
            is ShowAction.UnmarkAsFavorite -> {
                viewModelScope.launch {
                    repository.unmarkAsFavorite(showId)
                    loadShow(showId)
                }
            }
            else -> Unit
        }
    }
}
