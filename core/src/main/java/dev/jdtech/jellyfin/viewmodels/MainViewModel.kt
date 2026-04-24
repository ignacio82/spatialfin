package dev.jdtech.jellyfin.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.offline.ServerConnectionMonitor
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel
@Inject
constructor(
    private val appPreferences: AppPreferences,
    private val database: ServerDatabaseDao,
    private val connectionMonitor: ServerConnectionMonitor,
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
    }

    private fun check() {
        viewModelScope.launch {
            _state.emit(MainState(isLoading = true))
            val serverId = appPreferences.getValue(appPreferences.currentServer)
            val serverData = serverId?.let { database.getServerWithAddressAndUser(it) }
            val mainState =
                MainState(
                    isLoading = false,
                    isDynamicColors = checkIsDynamicColors(),
                    hasServers = checkHasServers(),
                    hasCurrentServer = serverData?.server != null,
                    hasCurrentUser = serverData?.user != null,
                    isOfflineMode = connectionMonitor.state.value.effectiveOfflineMode,
                    currentUser = serverData?.user,
                    currentServerAddress = serverData?.address?.address,
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
            connectionMonitor.state.collect { connectionState ->
                _state.update {
                    it.copy(isOfflineMode = connectionState.effectiveOfflineMode)
                }
            }
        }
    }

    fun reconnect() {
        connectionMonitor.triggerRefresh()
    }

    fun loadServerAndUser() {
        viewModelScope.launch {
            val serverId = appPreferences.getValue(appPreferences.currentServer)
            serverId?.let { id ->
                database.getServerWithAddressAndUser(id)?.let { data ->
                    _uiState.emit(UiState.Normal(data.server, data.user))
                }
            }
        }
    }

    private fun checkHasServers(): Boolean = database.getServersCount() > 0

    private fun checkIsDynamicColors(): Boolean =
        appPreferences.getValue(appPreferences.dynamicColors)

}

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
