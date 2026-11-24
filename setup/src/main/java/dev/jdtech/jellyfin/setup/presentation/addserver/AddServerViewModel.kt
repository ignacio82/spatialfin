package dev.jdtech.jellyfin.setup.presentation.addserver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.DiscoveredServer
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.onboarding.OnboardingFailureException
import dev.jdtech.jellyfin.onboarding.toOnboardingFailureCode
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import dev.jdtech.jellyfin.setup.lan.LanSetupController
import dev.jdtech.jellyfin.setup.lan.LanSetupMode
import dev.jdtech.jellyfin.setup.lan.LanSetupUiState
import dev.jdtech.jellyfin.setup.lan.LeaseHandle
import dev.jdtech.jellyfin.setup.onboarding.OnboardingFailureMessages
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class AddServerViewModel
@Inject
constructor(
    private val repository: SetupRepository,
    private val lanSetupController: LanSetupController,
) : ViewModel() {

    private val _state = MutableStateFlow(AddServerState())
    val state = _state.asStateFlow()

    val lanSetupUiState: StateFlow<LanSetupUiState> = lanSetupController.uiState

    private val eventsChannel = Channel<AddServerEvent>(Channel.BUFFERED)
    val events = eventsChannel.receiveAsFlow()

    private var discoveryJob: Job? = null
    private var lastClearedEntryId: String? = null

    fun acquireBrowserLease(entryId: String, clearCompletedDrain: Boolean = false): LeaseHandle {
        val shouldClear = clearCompletedDrain && lastClearedEntryId != entryId
        if (shouldClear) {
            lastClearedEntryId = entryId
        }
        return lanSetupController.acquire(
            mode = LanSetupMode.BROWSER_URL_ENTRY,
            clearCompletedDrain = shouldClear,
        )
    }

    fun releaseBrowserLease(
        lease: LeaseHandle,
        graceMs: Long = LanSetupController.DEFAULT_RELEASE_GRACE_MS,
    ) {
        lanSetupController.release(lease, graceMs)
    }

    fun regenerateManualCode() {
        lanSetupController.regenerateManualCode()
    }

    fun discoverServers() {
        discoveryJob?.cancel()
        discoveryJob =
            viewModelScope.launch {
                _state.update { it.copy(isDiscovering = true, discoveredServers = emptyList()) }
                try {
                    repository.discoverServers().collect { info ->
                        val discovered =
                            DiscoveredServer(
                                id = info.id,
                                name = info.name,
                                address = info.address,
                            )
                        _state.update { current ->
                            if (current.discoveredServers.any {
                                    it.id == discovered.id && it.address == discovered.address
                                }
                            ) {
                                current
                            } else {
                                current.copy(discoveredServers = current.discoveredServers + discovered)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Best-effort LAN UDP discovery; manual URL and /setup remain available.
                } finally {
                    _state.update { it.copy(isDiscovering = false) }
                }
            }
    }

    private fun connectToServer(address: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, failureCode = null) }
            try {
                val added = repository.addServer(address)
                _state.update { it.copy(isLoading = false, error = null, failureCode = null) }
                eventsChannel.send(
                    AddServerEvent.Success(
                        serverId = added.server.id,
                        serverName = added.server.name,
                        serverAddress = added.address.address,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: OnboardingFailureException) {
                emitFailure(e.code)
            } catch (e: Throwable) {
                emitFailure(e.toOnboardingFailureCode())
            }
        }
    }

    private fun emitFailure(code: OnboardingFailureCode) {
        _state.update {
            it.copy(
                isLoading = false,
                failureCode = code,
                error = listOf(UiText.StringResource(OnboardingFailureMessages.messageRes(code))),
            )
        }
    }

    fun onAction(action: AddServerAction) {
        when (action) {
            is AddServerAction.OnConnectClick -> connectToServer(address = action.address)
            else -> Unit
        }
    }
}
