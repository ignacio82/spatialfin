package dev.jdtech.jellyfin.film.presentation.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.core.Constants
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.repository.JellyfinRealtimeEvent
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.UiText
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

@HiltViewModel
class CollectionViewModel @Inject constructor(
    private val repository: JellyfinRepository,
    private val appPreferences: AppPreferences,
) :
    ViewModel() {
    private val _state = MutableStateFlow(CollectionState())
    val state = _state.asStateFlow()
    private var hasLoadedItems = false
    private var currentParentId: UUID? = null

    init {
        observeRealtimeEvents()
    }

    fun loadItems(parentId: UUID) {
        hasLoadedItems = true
        currentParentId = parentId
        viewModelScope.launch {
            _state.emit(_state.value.copy(isLoading = true, error = null))

            try {
                val items = repository.getItems(parentId = parentId, sortBy = SortBy.RELEASE_DATE)
                val displayRatings = appPreferences.getValue(appPreferences.displayRatings)

                val sections = mutableListOf<CollectionSection>()

                withContext(Dispatchers.Default) {
                    CollectionSection(
                            Constants.FAVORITE_TYPE_MOVIES,
                            UiText.StringResource(CoreR.string.movies_label),
                            items.filterIsInstance<SpatialFinMovie>(),
                        )
                        .let {
                            if (it.items.isNotEmpty()) {
                                sections.add(it)
                            }
                        }
                    CollectionSection(
                            Constants.FAVORITE_TYPE_SHOWS,
                            UiText.StringResource(CoreR.string.shows_label),
                            items.filterIsInstance<SpatialFinShow>(),
                        )
                        .let {
                            if (it.items.isNotEmpty()) {
                                sections.add(it)
                            }
                        }
                    CollectionSection(
                            Constants.FAVORITE_TYPE_EPISODES,
                            UiText.StringResource(CoreR.string.episodes_label),
                            items.filterIsInstance<SpatialFinEpisode>(),
                        )
                        .let {
                            if (it.items.isNotEmpty()) {
                                sections.add(it)
                            }
                        }
                }

                _state.emit(_state.value.copy(isLoading = false, sections = sections, displayRatings = displayRatings))
            } catch (e: Exception) {
                _state.emit(_state.value.copy(isLoading = false, error = e))
            }
        }
    }

    private fun observeRealtimeEvents() {
        viewModelScope.launch {
            repository.observeRealtimeEvents()
                .debounce(300)
                .collect { event ->
                    val parentId = currentParentId ?: return@collect
                    if (!hasLoadedItems || _state.value.isLoading) return@collect
                    val visibleItemIds =
                        _state.value.sections
                            .flatMap { section -> section.items }
                            .map { item -> item.id }
                            .toSet()
                    if (event.itemIds.any(visibleItemIds::contains) || event is JellyfinRealtimeEvent.LibraryChanged) {
                        loadItems(parentId)
                    }
                }
        }
    }
}
