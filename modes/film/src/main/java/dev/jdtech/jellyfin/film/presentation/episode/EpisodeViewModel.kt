package dev.jdtech.jellyfin.film.presentation.episode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.film.domain.VideoMetadataParser
import dev.jdtech.jellyfin.film.domain.loadDetailRelatedRows
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItemPerson
import dev.jdtech.jellyfin.repository.JellyfinRealtimeEvent
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.PersonKind

@HiltViewModel
class EpisodeViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    val appPreferences: AppPreferences,
    private val videoMetadataParser: VideoMetadataParser,
) : ViewModel() {
    private val _state = MutableStateFlow(EpisodeState())
    val state = _state.asStateFlow()
    private var hasLoadedEpisode = false
    private var relatedJob: Job? = null

    lateinit var episodeId: UUID

    init {
        observeRealtimeEvents()
    }

    fun loadEpisode(episodeId: UUID) {
        hasLoadedEpisode = true
        this.episodeId = episodeId
        viewModelScope.launch {
            try {
                val episode = repository.getEpisode(episodeId)
                val selectedIndex = _state.value.selectedSourceIndex.coerceIn(0, (episode.sources.size - 1).coerceAtLeast(0))
                val source = episode.sources.getOrNull(selectedIndex) ?: episode.sources.firstOrNull()
                val videoMetadata = source?.let { videoMetadataParser.parse(it) }
                val actors = getActors(episode)
                val displayExtraInfo = appPreferences.getValue(appPreferences.displayExtraInfo)
                val displayRatings = appPreferences.getValue(appPreferences.displayRatings)
                _state.emit(
                    _state.value.copy(
                        episode = episode,
                        selectedSourceIndex = selectedIndex,
                        videoMetadata = videoMetadata,
                        actors = actors,
                        displayExtraInfo = displayExtraInfo,
                        displayRatings = displayRatings,
                    )
                )
                loadRelated(episode)
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e))
            }
        }
    }

    /** Off the critical path: the hero renders first, the rows fill in after. */
    private fun loadRelated(episode: SpatialFinEpisode) {
        relatedJob?.cancel()
        relatedJob =
            viewModelScope.launch {
                val related = repository.loadDetailRelatedRows(episode)
                _state.update { if (it.episode?.id == episode.id) it.copy(related = related) else it }
            }
    }

    private fun observeRealtimeEvents() {
        viewModelScope.launch {
            repository.observeRealtimeEvents()
                .debounce(300)
                .collect { event ->
                    if (!hasLoadedEpisode || !::episodeId.isInitialized) return@collect
                    if (event.affects(episodeId) || event is JellyfinRealtimeEvent.LibraryChanged) {
                        loadEpisode(episodeId)
                    }
                }
        }
    }

    private suspend fun getActors(item: SpatialFinEpisode): List<SpatialFinItemPerson> {
        return withContext(Dispatchers.Default) {
            item.people.filter { it.type == PersonKind.ACTOR }
        }
    }

    fun onAction(action: EpisodeAction) {
        when (action) {
            is EpisodeAction.MarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsPlayed(episodeId)
                    loadEpisode(episodeId)
                }
            }
            is EpisodeAction.UnmarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsUnplayed(episodeId)
                    loadEpisode(episodeId)
                }
            }
            is EpisodeAction.MarkAsFavorite -> {
                viewModelScope.launch {
                    repository.markAsFavorite(episodeId)
                    loadEpisode(episodeId)
                }
            }
            is EpisodeAction.UnmarkAsFavorite -> {
                viewModelScope.launch {
                    repository.unmarkAsFavorite(episodeId)
                    loadEpisode(episodeId)
                }
            }
            is EpisodeAction.ReloadAfterMetadataEdit -> {
                // See MovieViewModel.ReloadAfterMetadataEdit.
                viewModelScope.launch { loadEpisode(episodeId) }
                viewModelScope.launch {
                    kotlinx.coroutines.delay(METADATA_REFRESH_WAIT_MS)
                    loadEpisode(episodeId)
                }
            }
            is EpisodeAction.SelectSource -> {
                val episode = _state.value.episode ?: return
                if (action.index in episode.sources.indices) {
                    viewModelScope.launch {
                        val newSource = episode.sources[action.index]
                        val videoMetadata = videoMetadataParser.parse(newSource)
                        _state.value = _state.value.copy(
                            selectedSourceIndex = action.index,
                            videoMetadata = videoMetadata,
                        )
                    }
                }
            }
            else -> Unit
        }
    }

    companion object {
        private const val METADATA_REFRESH_WAIT_MS = 15_000L
    }
}
