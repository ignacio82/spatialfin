package dev.jdtech.jellyfin.viewmodels

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.offline.ServerConnectionMonitor
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionChangeReason
import dev.jdtech.jellyfin.session.SessionMode
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class PendingViewVideo(
    val intent: Intent,
    val finishAfterLaunch: Boolean,
    val capturedElapsedMs: Long,
)

@HiltViewModel
class MainViewModel
@Inject
constructor(
    private val appPreferences: AppPreferences,
    private val connectionMonitor: ServerConnectionMonitor,
    private val sessionActivationCoordinator: SessionActivationCoordinator,
) : ViewModel() {

    private val _state = MutableStateFlow(MainState())
    val state: StateFlow<MainState> = _state.asStateFlow()

    val pendingViewVideoIntent: MutableStateFlow<PendingViewVideo?> = MutableStateFlow(null)

    init {
        viewModelScope.launch {
            sessionActivationCoordinator.ensureInitialized()
        }
        viewModelScope.launch {
            combine(
                sessionActivationCoordinator.sessionState,
                connectionMonitor.state,
            ) { session, conn ->
                MainState(
                    isLoading = session.isLoading,
                    coordinatorInstanceId = session.coordinatorInstanceId,
                    sessionMode = session.sessionMode,
                    sessionEpoch = session.sessionEpoch,
                    activationEpoch = session.activationEpoch,
                    isOfflineMode = conn.effectiveOfflineMode,
                    isDynamicColors = appPreferences.getValue(appPreferences.dynamicColors),
                    currentServerAddress = session.activeServerAddress,
                    currentUser = session.activeUser,
                    activeServerId = session.activeServerId,
                    activeServerName = session.activeServerName,
                    hasServers = session.hasServers,
                    savedServerIds = session.savedServerIds,
                    hasNetworkShares = session.hasNetworkShares,
                )
            }.collect { next ->
                _state.value = next
            }
        }
    }

    fun reconnect() {
        connectionMonitor.reconnect()
    }

    fun activateStandalone(reason: SessionChangeReason = SessionChangeReason.STANDALONE_ONBOARDING) {
        viewModelScope.launch {
            sessionActivationCoordinator.activateStandalone(reason = reason)
        }
    }

    fun logout() {
        viewModelScope.launch {
            sessionActivationCoordinator.logout(SessionChangeReason.LOGOUT)
        }
    }
}

data class MainState(
    val isLoading: Boolean = true,
    val coordinatorInstanceId: String = "",
    val sessionMode: SessionMode = SessionMode.UNCONFIGURED,
    val sessionEpoch: Long = 0L,
    val activationEpoch: Long = 0L,
    val isOfflineMode: Boolean = false,
    val isDynamicColors: Boolean = true,
    val currentServerAddress: String? = null,
    val currentUser: User? = null,
    val activeServerId: String? = null,
    val activeServerName: String? = null,
    val hasServers: Boolean = false,
    val savedServerIds: Set<String> = emptySet(),
    val hasNetworkShares: Boolean = false,
) {
    val hasCurrentServer: Boolean
        get() = activeServerId != null

    val hasCurrentUser: Boolean
        get() = currentUser != null
}
