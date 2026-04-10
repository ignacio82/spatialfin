package dev.jdtech.jellyfin.film.presentation.season

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.repository.JellyfinRealtimeEvent
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.ItemFields

@HiltViewModel
class SeasonViewModel @Inject constructor(private val repository: JellyfinRepository) :
    ViewModel() {
    private val _state = MutableStateFlow(SeasonState())
    val state = _state.asStateFlow()
    private var hasLoadedSeason = false

    lateinit var seasonId: UUID

    init {
        observeRealtimeEvents()
    }

    fun loadSeason(seasonId: UUID) {
        hasLoadedSeason = true
        this.seasonId = seasonId
        viewModelScope.launch {
            try {
                val season = repository.getSeason(seasonId)
                val episodes =
                    repository.getEpisodes(
                        seriesId = season.seriesId,
                        seasonId = seasonId,
                        fields = listOf(ItemFields.OVERVIEW),
                    )
                _state.emit(_state.value.copy(season = season, episodes = episodes))
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
                    if (!hasLoadedSeason || !::seasonId.isInitialized) return@collect
                    val relatedIds =
                        buildSet {
                            add(seasonId)
                            _state.value.episodes.forEach { episode -> add(episode.id) }
                        }
                    if (event.itemIds.any(relatedIds::contains) || event is JellyfinRealtimeEvent.LibraryChanged) {
                        loadSeason(seasonId)
                    }
                }
        }
    }

    fun onAction(action: SeasonAction) {
        when (action) {
            is SeasonAction.MarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsPlayed(seasonId)
                    loadSeason(seasonId)
                }
            }
            is SeasonAction.UnmarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsUnplayed(seasonId)
                    loadSeason(seasonId)
                }
            }
            is SeasonAction.MarkAsFavorite -> {
                viewModelScope.launch {
                    repository.markAsFavorite(seasonId)
                    loadSeason(seasonId)
                }
            }
            is SeasonAction.UnmarkAsFavorite -> {
                viewModelScope.launch {
                    repository.unmarkAsFavorite(seasonId)
                    loadSeason(seasonId)
                }
            }
            else -> Unit
        }
    }
}
