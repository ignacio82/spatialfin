package dev.jdtech.jellyfin.film.presentation.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.film.R as FilmR
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.HomeItem
import dev.jdtech.jellyfin.models.HomeSection
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.offline.OfflineSyncStatusMonitor
import dev.jdtech.jellyfin.offline.ServerConnectionMonitor
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.toView
import dev.jdtech.jellyfin.watchnext.WatchNextScheduler
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class HomeViewModel
@Inject
constructor(
    val repository: JellyfinRepository,
    val appPreferences: AppPreferences,
    val database: ServerDatabaseDao,
    private val connectionMonitor: ServerConnectionMonitor,
    private val offlineSyncStatusMonitor: OfflineSyncStatusMonitor,
    private val watchNextScheduler: WatchNextScheduler,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state = _state.asStateFlow()

    private val uuidSuggestions = UUID.fromString("31e47044-9b79-4bb0-99d0-0e477ed65420")
    private val uuidContinueWatching =
        UUID(4937169328197226115, -4704919157662094443) // 44845958-8326-4e83-beb4-c4f42e9eeb95
    private val uuidNextUp =
        UUID(1783371395749072194, -6164625418200444295) // 18bfced5-f237-4d42-aa72-d9d7fed19279
    private val uuidOfflineFavorites =
        UUID.fromString("efad1d86-fc13-4d17-8204-44f6b35456ce")
    private val uuidOfflineMovies =
        UUID.fromString("4dd2d857-5917-4c97-8886-68dff8dd8452")
    private val uuidOfflineShows =
        UUID.fromString("78482034-8cc1-47a9-9ff1-0ecb1da759f4")

    private val uiTextContinueWatching = UiText.StringResource(FilmR.string.continue_watching)
    private val uiTextNextUp = UiText.StringResource(FilmR.string.next_up)
    private val uiTextOfflineFavorites =
        UiText.StringResource(FilmR.string.offline_favorites)
    private val uiTextOfflineMovies =
        UiText.StringResource(FilmR.string.offline_downloaded_movies)
    private val uiTextOfflineShows =
        UiText.StringResource(FilmR.string.offline_downloaded_shows)
    private var hasLoadedData = false

    init {
        observeConnectionState()
        observeSyncStatus()
        observeRealtimeEvents()
    }

    fun loadData() {
        hasLoadedData = true
        Timber.i("Loading data")
        viewModelScope.launch(Dispatchers.Default) {
            _state.emit(_state.value.copy(isLoading = true, error = null))
            try {
                appPreferences.getValue(appPreferences.currentServer)?.let { serverId ->
                    loadServerName(serverId)
                }

                loadResumeItems()
                loadNextUpItems()
                if (connectionMonitor.shouldUseOfflineRepository()) {
                    _state.update { it.copy(suggestionsSection = null, views = emptyList()) }
                    loadOfflineLibrarySections()
                    _state.emit(_state.value.copy(isLoading = false))
                } else {
                    _state.update { it.copy(offlineLibrarySections = emptyList()) }
                    loadSuggestions()
                    // Resolve the main loading spinner as soon as the above-the-fold
                    // content (resume / next-up / suggestions) is in. loadViews()
                    // does N+1 API calls (one per library for latest media) and was
                    // blocking first paint by 200-500ms on slow TV connections.
                    _state.emit(_state.value.copy(isLoading = false))
                    // Publish resume/next-up to Google TV's Watch Next row. No-op
                    // on non-TV devices, so unconditional here is fine. Fire after
                    // the sections are in _state so Watch Next mirrors what the
                    // user just saw on the home screen.
                    watchNextScheduler.syncNow(appContext)
                    try {
                        loadViews()
                    } catch (e: Exception) {
                        Timber.w(e, "loadViews failed after first paint")
                    }
                }
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e, isLoading = false))
            }
        }
    }

    private suspend fun loadServerName(serverId: String) {
        val server = database.get(serverId)
        if (server != null) {
            _state.emit(_state.value.copy(server = server))
        }
    }

    private suspend fun loadSuggestions() {
        Timber.i("Loading suggestions")
        if (!appPreferences.getValue(appPreferences.homeSuggestions)) {
            _state.emit(_state.value.copy(suggestionsSection = null))
            return
        }

        val items = repository.getSuggestions()

        val section =
            if (items.isEmpty()) {
                null
            } else {
                HomeItem.Suggestions(id = uuidSuggestions, items = items)
            }

        _state.emit(_state.value.copy(suggestionsSection = section))
    }

    private suspend fun loadResumeItems() {
        Timber.i("Loading resume items")
        if (!appPreferences.getValue(appPreferences.homeContinueWatching)) {
            _state.emit(_state.value.copy(resumeSection = null))
            return
        }

        val resumeItems = repository.getResumeItems()

        val section =
            if (resumeItems.isEmpty()) {
                null
            } else {
                HomeItem.Section(
                    HomeSection(uuidContinueWatching, uiTextContinueWatching, resumeItems)
                )
            }

        _state.emit(_state.value.copy(resumeSection = section))
    }

    private suspend fun loadNextUpItems() {
        Timber.i("Loading next up items")
        if (!appPreferences.getValue(appPreferences.homeNextUp)) {
            _state.emit(_state.value.copy(nextUpSection = null))
            return
        }

        val nextUpItems = repository.getNextUp()

        val section =
            if (nextUpItems.isEmpty()) {
                null
            } else {
                HomeItem.Section(HomeSection(uuidNextUp, uiTextNextUp, nextUpItems))
            }

        _state.emit(_state.value.copy(nextUpSection = section))
    }

    private suspend fun loadViews() {
        Timber.i("Loading views")
        val items =
            if (appPreferences.getValue(appPreferences.homeLatest)) {
                repository
                    .getUserViews()
                    .filter { view ->
                        CollectionType.fromString(view.collectionType?.serialName) in
                            CollectionType.supported
                    }
                    .map { view -> view to repository.getLatestMedia(view.id) }
                    .filter { (_, latest) -> latest.isNotEmpty() }
                    .map { (view, latest) -> view.toView(latest) }
                    .map { HomeItem.ViewItem(it) }
            } else {
                emptyList()
            }

        _state.emit(_state.value.copy(views = items))
    }

    private suspend fun loadOfflineLibrarySections() {
        Timber.i("Loading offline library sections")
        val downloadedItems = repository.getDownloads()
        val favoriteItems =
            repository
                .getFavoriteItems()
                .filter { it is SpatialFinShow || it.isDownloaded() }
        val sections = buildList {
            if (favoriteItems.isNotEmpty()) {
                add(
                    HomeItem.Section(
                        HomeSection(uuidOfflineFavorites, uiTextOfflineFavorites, favoriteItems)
                    )
                )
            }
            downloadedItems
                .filterIsInstance<SpatialFinMovie>()
                .filter { it.isDownloaded() }
                .takeIf { it.isNotEmpty() }
                ?.let {
                    add(HomeItem.Section(HomeSection(uuidOfflineMovies, uiTextOfflineMovies, it)))
                }
            downloadedItems
                .filterIsInstance<SpatialFinShow>()
                .takeIf { it.isNotEmpty() }
                ?.let {
                    add(HomeItem.Section(HomeSection(uuidOfflineShows, uiTextOfflineShows, it)))
                }
        }
        _state.update { it.copy(offlineLibrarySections = sections) }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            var previousState = connectionMonitor.state.value
            connectionMonitor.state.collect { connectionState ->
                _state.update {
                    it.copy(
                        isOfflineMode = connectionState.effectiveOfflineMode,
                        isConnectionDegraded = connectionState.isDegradedMode,
                        manualOfflineMode = connectionState.manualOfflineMode,
                    )
                }
                val shouldReload =
                    previousState.effectiveOfflineMode != connectionState.effectiveOfflineMode ||
                        (!previousState.serverAccessible && connectionState.serverAccessible)
                previousState = connectionState
                if (shouldReload) {
                    loadData()
                }
            }
        }
    }

    private fun observeSyncStatus() {
        viewModelScope.launch {
            offlineSyncStatusMonitor.state.collect { syncStatus ->
                _state.update { it.copy(syncStatus = syncStatus) }
            }
        }
    }

    private fun observeRealtimeEvents() {
        viewModelScope.launch {
            repository.observeRealtimeEvents()
                .debounce(300)
                .collect {
                    if (hasLoadedData && !_state.value.isLoading) {
                        loadData()
                    }
                }
        }
    }

    fun onAction(action: HomeAction) {
        when (action) {
            is HomeAction.OnRetryClick -> {
                loadData()
            }
            else -> Unit
        }
    }
}
