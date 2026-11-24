package dev.jdtech.jellyfin.setup.presentation.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.session.ActivationResult
import dev.jdtech.jellyfin.setup.domain.SetCurrentServerResult
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ServersViewModel
@Inject
constructor(
    private val repository: SetupRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ServersState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<ServersEvent>(Channel.BUFFERED)
    val events = eventsChannel.receiveAsFlow()

    fun loadServers() {
        viewModelScope.launch {
            val servers = repository.getServers()
            _state.emit(ServersState(servers = servers))
        }
    }

    private fun setCurrentServer(serverId: String) {
        viewModelScope.launch {
            when (val result = repository.setCurrentServer(serverId)) {
                SetCurrentServerResult.Activated -> {
                    eventsChannel.send(ServersEvent.ServerChanged)
                }
                is SetCurrentServerResult.NeedsUserSelection -> {
                    eventsChannel.send(
                        ServersEvent.NavigateToUsers(
                            serverId = result.serverId,
                            serverName = result.serverName,
                            serverAddress = result.serverAddress,
                        ),
                    )
                }
                is SetCurrentServerResult.Failed -> {
                    eventsChannel.send(ServersEvent.Failed(result.code))
                }
            }
        }
    }

    private fun setCurrentAddress(serverId: String, addressId: UUID) {
        viewModelScope.launch {
            when (val result = repository.setCurrentAddress(serverId, addressId)) {
                is ActivationResult.Activated -> {
                    eventsChannel.send(ServersEvent.ServerChanged)
                }
                is ActivationResult.NeedsAuth -> {
                    eventsChannel.send(
                        ServersEvent.NavigateToUsers(
                            serverId = result.serverId,
                            serverName = result.serverName,
                            serverAddress = result.serverAddress,
                        ),
                    )
                }
                is ActivationResult.Failed -> {
                    eventsChannel.send(ServersEvent.Failed(result.code))
                }
            }
        }
    }

    private fun deleteServer(serverId: String) {
        viewModelScope.launch {
            repository.deleteServer(serverId)
            loadServers()
        }
    }

    fun onAction(action: ServersAction) {
        when (action) {
            is ServersAction.OnServerClick -> setCurrentServer(action.serverId)
            is ServersAction.OnAddressClick -> setCurrentAddress(action.serverId, action.addressId)
            is ServersAction.DeleteServer -> deleteServer(action.serverId)
            else -> Unit
        }
    }
}
