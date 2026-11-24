package dev.jdtech.jellyfin.setup.presentation.users

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.setup.domain.SetCurrentUserResult
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class UsersViewModel @Inject constructor(private val repository: SetupRepository) : ViewModel() {
    private val _state = MutableStateFlow(UsersState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<UsersEvent>(Channel.BUFFERED)
    val events = eventsChannel.receiveAsFlow()

    private var currentServerId: String = ""
    private var currentServerName: String = ""
    private var currentServerAddress: String = ""

    fun loadUsers(
        serverId: String,
        serverName: String,
        serverAddress: String,
    ) {
        currentServerId = serverId
        currentServerName = serverName
        currentServerAddress = serverAddress
        viewModelScope.launch {
            val users = repository.getUsers(serverId)
            val userIds = users.map { it.id }.toSet()

            _state.emit(
                UsersState(
                    users = users,
                    serverName = serverName,
                    serverAddress = serverAddress,
                ),
            )

            if (serverAddress.isNotBlank()) {
                try {
                    val publicUsers = repository.getPublicUsers(serverId, serverAddress)
                    _state.emit(
                        _state.value.copy(
                            publicUsers = publicUsers.filterNot { userIds.contains(it.id) },
                        ),
                    )
                } catch (_: Exception) {
                    // Best-effort public user listing
                }
            }
        }
    }

    private fun deleteUser(userId: UUID) {
        viewModelScope.launch {
            repository.deleteUser(userId)
            if (currentServerId.isNotBlank()) {
                loadUsers(currentServerId, currentServerName, currentServerAddress)
            }
        }
    }

    private fun loginAsUser(userId: UUID) {
        val serverId = currentServerId
        if (serverId.isBlank()) {
            viewModelScope.launch { eventsChannel.send(UsersEvent.Failed(OnboardingFailureCode.STORAGE_ERROR)) }
            return
        }
        viewModelScope.launch {
            Timber.d("[UserSwitch] loginAsUser: calling setCurrentUser(%s, %s)", serverId, userId)
            when (val result = repository.setCurrentUser(serverId, userId)) {
                SetCurrentUserResult.Activated -> {
                    eventsChannel.send(UsersEvent.UserActivated)
                }
                is SetCurrentUserResult.NeedsLogin -> {
                    eventsChannel.send(UsersEvent.NeedsLogin(result.userId, result.username))
                }
                is SetCurrentUserResult.Failed -> {
                    eventsChannel.send(UsersEvent.Failed(result.code))
                }
            }
        }
    }

    fun onAction(action: UsersAction) {
        when (action) {
            is UsersAction.OnUserClick -> loginAsUser(action.userId)
            is UsersAction.OnPublicUserClick -> {
                viewModelScope.launch {
                    eventsChannel.send(UsersEvent.NeedsLogin(userId = null, username = action.username))
                }
            }
            is UsersAction.OnDeleteUser -> deleteUser(action.userId)
            else -> Unit
        }
    }
}
