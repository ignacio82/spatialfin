package dev.jdtech.jellyfin.film.presentation.downloads

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.core.Constants
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.downloads.OrphanedDownloadsReport
import dev.jdtech.jellyfin.film.presentation.collection.CollectionState
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSourceType
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.ActiveDownloadEntry
import dev.jdtech.jellyfin.utils.Downloader
import dev.jdtech.jellyfin.utils.DownloadVerificationReport
import timber.log.Timber
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
    private val appPreferences: AppPreferences,
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
                val filteredItems = section.items.filter { item ->
                    item.name.contains(query, ignoreCase = true) ||
                        (item is SpatialFinEpisode && item.seriesName.contains(query, ignoreCase = true))
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

    private val _isVerifying = MutableStateFlow(false)
    val isVerifying = _isVerifying.asStateFlow()

    private val _verificationReport = MutableStateFlow<DownloadVerificationReport?>(null)
    val verificationReport = _verificationReport.asStateFlow()

    private val _orphanedReport = MutableStateFlow(OrphanedDownloadsReport())
    val orphanedReport = _orphanedReport.asStateFlow()

    private val _isCleaningOrphans = MutableStateFlow(false)
    val isCleaningOrphans = _isCleaningOrphans.asStateFlow()

    private val _pendingMediaStoreDeleteUris = MutableStateFlow<List<Uri>>(emptyList())
    val pendingMediaStoreDeleteUris = _pendingMediaStoreDeleteUris.asStateFlow()

    private val _autoDeleteWatched = MutableStateFlow(
        appPreferences.getValue(appPreferences.autoDeleteWatchedDownloads),
    )
    val autoDeleteWatched = _autoDeleteWatched.asStateFlow()

    private val _autoDownloadNextCount = MutableStateFlow(
        appPreferences.getValue(appPreferences.autoDownloadNextEpisodesCount).coerceIn(0, 2),
    )
    val autoDownloadNextCount = _autoDownloadNextCount.asStateFlow()

    init {
        observeRealtimeEvents()
        downloader.verifyDownloads()
    }

    /**
     * Runs an on-demand deep integrity verification on all completed downloads.
     */
    fun verifyDownloadsManually() {
        if (_isVerifying.value) return
        viewModelScope.launch {
            _isVerifying.emit(true)
            try {
                val report = downloader.verifyDownloadIntegrity()
                _verificationReport.emit(report)
                loadItems()
            } catch (e: Exception) {
                Timber.e(e, "Manual download verification failed")
            } finally {
                _isVerifying.emit(false)
            }
        }
    }

    fun dismissVerificationReport() {
        _verificationReport.value = null
    }

    fun cleanOrphanedDownloads() {
        if (_isCleaningOrphans.value) return
        viewModelScope.launch {
            _isCleaningOrphans.emit(true)
            try {
                val result = downloader.cleanOrphanedDownloads()
                if (result.requiresSystemConfirmationUris.isNotEmpty()) {
                    _pendingMediaStoreDeleteUris.emit(result.requiresSystemConfirmationUris)
                }
                _orphanedReport.emit(downloader.scanOrphanedDownloads())
                _storageUsedBytes.emit(downloader.getStorageUsedBytes())
            } catch (e: Exception) {
                Timber.e(e, "Failed to clean orphaned downloads")
            } finally {
                _isCleaningOrphans.emit(false)
            }
        }
    }

    fun onMediaStoreDeleteCompleted() {
        _pendingMediaStoreDeleteUris.value = emptyList()
        viewModelScope.launch {
            runCatching {
                _orphanedReport.emit(downloader.scanOrphanedDownloads())
                _storageUsedBytes.emit(downloader.getStorageUsedBytes())
            }
        }
    }

    fun setAutoDeleteWatched(enabled: Boolean) {
        appPreferences.setValue(appPreferences.autoDeleteWatchedDownloads, enabled)
        _autoDeleteWatched.value = enabled
    }

    fun setAutoDownloadNextCount(count: Int) {
        val clamped = count.coerceIn(0, 2)
        appPreferences.setValue(appPreferences.autoDownloadNextEpisodesCount, clamped)
        _autoDownloadNextCount.value = clamped
    }

    fun loadItems() {
        hasLoadedItems = true
        viewModelScope.launch {
            _isLoading.emit(true)
            _error.emit(null)

            try {
                _autoDeleteWatched.emit(appPreferences.getValue(appPreferences.autoDeleteWatchedDownloads))
                _autoDownloadNextCount.emit(
                    appPreferences.getValue(appPreferences.autoDownloadNextEpisodesCount).coerceIn(0, 2),
                )
                _storageUsedBytes.emit(downloader.getStorageUsedBytes())
                _orphanedReport.emit(
                    runCatching { downloader.scanOrphanedDownloads() }.getOrDefault(OrphanedDownloadsReport()),
                )
                val items = repository.getDownloads()
                val resumeItems = try { repository.getResumeItems().filter { it.isDownloaded() } } catch (_: Exception) { emptyList() }
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
            _rawSections.emit(
                _rawSections.value.mapNotNull { section ->
                    val remaining =
                        section.items.filterNot { existing ->
                            existing.id == item.id ||
                                (item is SpatialFinShow && existing is SpatialFinEpisode && existing.seriesId == item.id)
                        }
                    if (remaining.isNotEmpty()) section.copy(items = remaining) else null
                }
            )
            when (item) {
                is SpatialFinShow -> deleteShowDownloads(item)
                else -> {
                    val localSources = resolveLocalSources(item)
                    if (localSources.isEmpty()) {
                        downloader.cancelDownload(item)
                    } else {
                        localSources.forEach { source ->
                            downloader.deleteItem(item, source)
                        }
                    }
                }
            }
            loadItems()
        }
    }

    private suspend fun deleteShowDownloads(show: SpatialFinShow) {
        runCatching {
            repository.getSeasons(show.id, offline = true)
                .flatMap { season ->
                    repository.getEpisodes(
                        seriesId = show.id,
                        seasonId = season.id,
                        offline = true,
                    )
                }
        }.getOrDefault(emptyList())
            .forEach { episode ->
                val localSources = resolveLocalSources(episode)
                if (localSources.isEmpty()) {
                    downloader.cancelDownload(episode)
                } else {
                    localSources.forEach { source ->
                        downloader.deleteItem(episode, source)
                    }
                }
            }
        downloader.cancelDownload(show)
    }

    private suspend fun resolveLocalSources(item: SpatialFinItem): List<dev.jdtech.jellyfin.models.SpatialFinSource> {
        val attachedLocal = item.sources.filter { it.type == SpatialFinSourceType.LOCAL }
        if (attachedLocal.isNotEmpty()) return attachedLocal.distinctBy { it.id }
        val fetched = runCatching { repository.getMediaSources(item.id) }.getOrDefault(emptyList())
        return (item.sources + fetched)
            .distinctBy { it.id }
            .filter { it.type == SpatialFinSourceType.LOCAL }
    }
}
