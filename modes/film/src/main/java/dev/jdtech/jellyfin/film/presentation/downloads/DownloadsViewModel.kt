package dev.jdtech.jellyfin.film.presentation.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.core.Constants
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.collection.CollectionState
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSourceType
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.utils.ActiveDownloadEntry
import dev.jdtech.jellyfin.utils.Downloader
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DownloadSortOrder { NAME, DATE_ADDED }

@HiltViewModel
class DownloadsViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val downloader: Downloader,
) : ViewModel() {
    private val _rawSections = MutableStateFlow<List<CollectionSection>>(emptyList())
    private val _searchQuery = MutableStateFlow("")
    private val _sortOrder = MutableStateFlow(DownloadSortOrder.NAME)
    private val _isLoading = MutableStateFlow(false)
    private val _error = MutableStateFlow<Exception?>(null)

    val searchQuery = _searchQuery.asStateFlow()
    val sortOrder = _sortOrder.asStateFlow()

    val state = combine(_rawSections, _searchQuery, _sortOrder, _isLoading, _error) { sections, query, sort, loading, error ->
        val filtered = if (query.isBlank()) sections else {
            sections.mapNotNull { section ->
                val filteredItems = section.items.filter {
                    it.name.contains(query, ignoreCase = true)
                }
                if (filteredItems.isNotEmpty()) section.copy(items = filteredItems) else null
            }
        }
        val sorted = when (sort) {
            DownloadSortOrder.NAME -> filtered.map { section ->
                section.copy(items = section.items.sortedBy { it.name })
            }
            DownloadSortOrder.DATE_ADDED -> filtered
        }
        CollectionState(isLoading = loading, sections = sorted, error = error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CollectionState(isLoading = true))

    val activeDownloads = downloader.observeActiveDownloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _storageUsedBytes = MutableStateFlow(0L)
    val storageUsedBytes = _storageUsedBytes.asStateFlow()

    private val _continueWatchingItems = MutableStateFlow<List<SpatialFinItem>>(emptyList())
    val continueWatchingItems = _continueWatchingItems.asStateFlow()
    private var hasLoadedItems = false

    init {
        observeRealtimeEvents()
    }

    fun loadItems() {
        hasLoadedItems = true
        viewModelScope.launch {
            _isLoading.emit(true)
            _error.emit(null)

            try {
                _storageUsedBytes.emit(downloader.getStorageUsedBytes())
                val items = repository.getDownloads()
                val resumeItems = try { repository.getResumeItems() } catch (_: Exception) { emptyList() }
                _continueWatchingItems.emit(resumeItems)

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
                }

                _rawSections.emit(sections)
                _isLoading.emit(false)
            } catch (e: Exception) {
                _isLoading.emit(false)
                _error.emit(e)
            }
        }
    }

    private fun observeRealtimeEvents() {
        viewModelScope.launch {
            repository.observeRealtimeEvents()
                .debounce(300)
                .collect {
                    if (hasLoadedItems && !_isLoading.value) {
                        loadItems()
                    }
                }
        }
    }

    fun setSearchQuery(query: String) {
        viewModelScope.launch { _searchQuery.emit(query) }
    }

    fun setSortOrder(order: DownloadSortOrder) {
        viewModelScope.launch { _sortOrder.emit(order) }
    }

    fun pauseDownload(itemId: java.util.UUID) {
        viewModelScope.launch { downloader.pauseDownloadById(itemId) }
    }

    fun resumeDownload(itemId: java.util.UUID) {
        viewModelScope.launch { downloader.resumeDownloadById(itemId) }
    }

    fun cancelActiveDownload(itemId: java.util.UUID) {
        viewModelScope.launch { downloader.cancelDownloadById(itemId) }
    }

    fun deleteItem(item: SpatialFinItem) {
        viewModelScope.launch {
            when (item) {
                is SpatialFinShow -> deleteShowDownloads(item)
                else ->
                    resolveLocalSources(item).forEach { source ->
                        downloader.deleteItem(item, source)
                    }
            }
            loadItems()
        }
    }

    private suspend fun deleteShowDownloads(show: SpatialFinShow) {
        repository.getSeasons(show.id, offline = true)
            .flatMap { season ->
                repository.getEpisodes(
                    seriesId = show.id,
                    seasonId = season.id,
                    offline = true,
                )
            }
            .forEach { episode ->
                resolveLocalSources(episode).forEach { source ->
                    downloader.deleteItem(episode, source)
                }
            }
    }

    private suspend fun resolveLocalSources(item: SpatialFinItem) =
        (item.sources + repository.getMediaSources(item.id))
            .distinctBy { it.id }
            .filter { it.type == SpatialFinSourceType.LOCAL }
}
