package dev.jdtech.jellyfin.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.offline.ServerConnectionMonitor
import dev.jdtech.jellyfin.session.ActiveSessionBus
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class MainViewModel
@Inject
constructor(
    private val appPreferences: AppPreferences,
    private val database: ServerDatabaseDao,
    private val connectionMonitor: ServerConnectionMonitor,
    private val activeSessionBus: ActiveSessionBus,
    private val jellyfinApi: JellyfinApi,
) :
    ViewModel() {
    private val _state = MutableStateFlow(MainState())
    val state = _state.asStateFlow()

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState = _uiState.asStateFlow()

    sealed class UiState {
        data class Normal(val server: Server?, val user: User?) : UiState()

        data object Loading : UiState()
    }

    init {
        check()
        observeOfflineState()
        observeSessionChanges()
    }

    private fun check() {
        viewModelScope.launch {
            _state.emit(MainState(isLoading = true))
            val serverId = appPreferences.getValue(appPreferences.currentServer)
            val loadedSession = withContext(Dispatchers.IO) {
                val serverData = serverId?.let { database.getServerWithAddressAndUser(it) }

                // Repair the cold-start race: ApiModule.provideJellyfinApi is a
                // @Singleton built exactly once, sometimes before SharedPreferences
                // or Room are warm — when that happens it leaves the shared
                // JellyfinApi with no base URL / access token, and never re-binds
                // because the provider only runs once. Authenticated reads then
                // 401, which (with isApparentConnectionFailure no longer treating
                // 401 as offline) surfaces as an auth error rather than a livelock,
                // but the screen still has no credentials. Re-applying the
                // persisted session here — deterministically, before we emit the
                // non-loading state that lets navigation mount Home — guarantees
                // the API client reflects the stored session by first load.
                // Idempotent: re-applying the same session is a no-op.
                val fallbackAddress = serverId?.let { database.getServerCurrentAddress(it) }
                serverId?.let { currentServerId ->
                    fallbackAddress
                        ?.takeIf { serverData?.server?.currentServerAddressId != it.id }
                        ?.let { address -> database.updateServerCurrentAddress(currentServerId, address.id) }
                }
                val address = serverData?.address?.address ?: fallbackAddress?.address
                LoadedMainSession(
                    server = serverData?.server,
                    user = serverData?.user,
                    address = address,
                    hasServers = database.getServersCount() > 0,
                )
            }
            if (loadedSession.address != null) {
                jellyfinApi.apply {
                    api.update(
                        baseUrl = loadedSession.address,
                        accessToken = loadedSession.user?.accessToken,
                    )
                    userId = loadedSession.user?.id
                }
            }

            val mainState =
                MainState(
                    isLoading = false,
                    isDynamicColors = checkIsDynamicColors(),
                    hasServers = loadedSession.hasServers,
                    hasCurrentServer = loadedSession.server != null,
                    hasCurrentUser = loadedSession.user != null,
                    isOfflineMode = connectionMonitor.state.value.effectiveOfflineMode,
                    currentUser = loadedSession.user,
                    currentServerAddress = loadedSession.address,
                )
            _state.emit(mainState)
        }
    }

    /**
     * Re-runs the auth/server query and re-emits MainState.
     * Call after the active user changes (e.g. on return from UsersScreen)
     * so the navigation rail's profile avatar refreshes without an Activity recreate.
     */
    fun refresh() = check()

    private fun observeOfflineState() {
        viewModelScope.launch {
            var previousAccessible = connectionMonitor.state.value.serverAccessible
            connectionMonitor.state.collect { connectionState ->
                _state.update {
                    it.copy(isOfflineMode = connectionState.effectiveOfflineMode)
                }
                // When the server becomes reachable again — possibly via a different address
                // (after connecting to Wi-Fi or Tailscale) that the monitor just switched to —
                // re-apply the current address + token to the live API and refresh session
                // state, so content reloads against the working address without a manual tap.
                if (!previousAccessible && connectionState.serverAccessible) {
                    check()
                }
                previousAccessible = connectionState.serverAccessible
            }
        }
    }

    private fun observeSessionChanges() {
        viewModelScope.launch {
            activeSessionBus.events.collect { check() }
        }
    }

    fun reconnect() {
        check()
        connectionMonitor.reconnect()
    }

    fun loadServerAndUser() {
        viewModelScope.launch {
            val serverId = appPreferences.getValue(appPreferences.currentServer)
            val data = withContext(Dispatchers.IO) {
                serverId?.let { id -> database.getServerWithAddressAndUser(id) }
            }
            data?.let {
                _uiState.emit(UiState.Normal(it.server, it.user))
            }
        }
    }

    private fun checkIsDynamicColors(): Boolean =
        appPreferences.getValue(appPreferences.dynamicColors)

}

private data class LoadedMainSession(
    val server: Server?,
    val user: User?,
    val address: String?,
    val hasServers: Boolean,
)

data class MainState(
    val isLoading: Boolean = true,
    val isDynamicColors: Boolean = true,
    val hasServers: Boolean = false,
    val hasCurrentServer: Boolean = false,
    val hasCurrentUser: Boolean = false,
    val isOfflineMode: Boolean = false,
    val currentUser: User? = null,
    val currentServerAddress: String? = null,
)
