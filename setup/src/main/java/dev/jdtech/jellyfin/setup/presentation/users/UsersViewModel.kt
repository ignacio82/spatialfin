package dev.jdtech.jellyfin.setup.presentation.users

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.companion.CompanionCandidate
import dev.jdtech.jellyfin.companion.CompanionConfigImporter
import dev.jdtech.jellyfin.companion.CompanionImportOutcome
import dev.jdtech.jellyfin.companion.CompanionLiveSyncClient
import dev.jdtech.jellyfin.companion.ImportMode
import dev.jdtech.jellyfin.companion.SelectedCompanionUser
import dev.jdtech.jellyfin.models.companion.CompanionEndpoint
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.onboarding.toOnboardingFailureCode
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.setup.domain.SetCurrentUserResult
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class UsersViewModel
@Inject
constructor(
    private val repository: SetupRepository,
    private val appPreferences: AppPreferences,
    private val companionConfigImporter: CompanionConfigImporter,
    private val companionLiveSyncClient: CompanionLiveSyncClient,
) : ViewModel() {
    private val _state = MutableStateFlow(UsersState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<UsersEvent>(Channel.BUFFERED)
    val events = eventsChannel.receiveAsFlow()

    private var currentServerId: String = ""
    private var currentServerName: String = ""
    private var currentServerAddress: String = ""
    private var loadJob: Job? = null
    private var pausedWithActiveTarget: Boolean = false

    init {
        viewModelScope.launch {
            companionLiveSyncClient.configChangedEvents.collect {
                if (currentServerId.isNotBlank()) {
                    loadUsers(currentServerId, currentServerName, currentServerAddress)
                }
            }
        }
    }

    fun onPauseOrStop() {
        if (currentServerId.isNotBlank() && CompanionEndpoint.fromPreferences(appPreferences) != null) {
            pausedWithActiveTarget = true
        }
    }

    fun onResumeOrStart() {
        if (currentServerId.isNotBlank() && CompanionEndpoint.fromPreferences(appPreferences) != null) {
            companionLiveSyncClient.ensureConnected()
            if (pausedWithActiveTarget) {
                pausedWithActiveTarget = false
                loadUsers(currentServerId, currentServerName, currentServerAddress)
            }
        }
    }

    fun loadUsers(
        serverId: String,
        serverName: String,
        serverAddress: String,
    ) {
        val sameServer = currentServerId == serverId
        currentServerId = serverId
        currentServerName = serverName
        currentServerAddress = serverAddress
        loadJob?.cancel()
        loadJob =
            viewModelScope.launch {
                val users = repository.getUsers(serverId)
                val savedIds = users.map { it.id }.toSet()
                val savedNames = users.map { it.name.lowercase() }.toSet()

                _state.update { current ->
                    if (sameServer) {
                        val extraCompanion =
                            current.allCompanionUsers.filterNot { cand ->
                                (cand.userId != null && cand.userId in savedIds) ||
                                    cand.username.lowercase() in savedNames
                            }
                        val compIds = extraCompanion.mapNotNull { it.userId }.toSet()
                        val compNames = extraCompanion.map { it.username.lowercase() }.toSet()
                        current.copy(
                            users = users,
                            companionUsers = extraCompanion,
                            publicUsers =
                                current.publicUsers.filterNot { pub ->
                                    pub.id in savedIds ||
                                        pub.name.lowercase() in savedNames ||
                                        pub.id in compIds ||
                                        pub.name.lowercase() in compNames
                                },
                            serverName = serverName,
                            serverAddress = serverAddress,
                        )
                    } else {
                        UsersState(
                            users = users,
                            serverName = serverName,
                            serverAddress = serverAddress,
                        )
                    }
                }

                val endpoint = CompanionEndpoint.fromPreferences(appPreferences)
                val companionDeferred =
                    async {
                        if (endpoint == null) {
                            emptyList()
                        } else {
                            companionLiveSyncClient.ensureConnected()
                            try {
                                companionConfigImporter.fetchCompanionUsersForServer(
                                    endpoint = endpoint,
                                    serverId = serverId,
                                    serverAddress = serverAddress,
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    }
                val publicDeferred =
                    async {
                        if (serverAddress.isBlank()) {
                            emptyList()
                        } else {
                            try {
                                repository.getPublicUsers(serverId, serverAddress)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    }

                val allCompanionUsers = companionDeferred.await()
                val rawPublicUsers = publicDeferred.await()

                val extraCompanionUsers =
                    allCompanionUsers.filterNot { cand ->
                        (cand.userId != null && cand.userId in savedIds) ||
                            cand.username.lowercase() in savedNames
                    }
                val companionIds = extraCompanionUsers.mapNotNull { it.userId }.toSet()
                val companionNames = extraCompanionUsers.map { it.username.lowercase() }.toSet()
                val filteredPublicUsers =
                    rawPublicUsers.filterNot { pub ->
                        pub.id in savedIds ||
                            pub.name.lowercase() in savedNames ||
                            pub.id in companionIds ||
                            pub.name.lowercase() in companionNames
                    }

                _state.update { current ->
                    current.copy(
                        users = users,
                        companionUsers = extraCompanionUsers,
                        allCompanionUsers = allCompanionUsers,
                        publicUsers = filteredPublicUsers,
                        serverName = serverName,
                        serverAddress = serverAddress,
                    )
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
        val currentState = _state.value
        val localUser = currentState.users.firstOrNull { it.id == userId }
        val companionStatus = localUser?.let { currentState.companionStatusFor(it) }
        if (localUser != null && localUser.accessToken.isNullOrBlank() && companionStatus != null) {
            selectCompanionUser(companionStatus)
            return
        }
        viewModelScope.launch {
            Timber.d("[UserSwitch] loginAsUser: calling setCurrentUser(%s, %s)", serverId, userId)
            when (val result = repository.setCurrentUser(serverId, userId)) {
                SetCurrentUserResult.Activated -> {
                    eventsChannel.send(UsersEvent.UserActivated)
                }
                is SetCurrentUserResult.NeedsLogin -> {
                    val failureCode =
                        when {
                            companionStatus == null || !companionStatus.requiresCredentialUpdate -> null
                            !companionStatus.isVerified -> OnboardingFailureCode.PAIRING_NO_VERIFIED_USER
                            else -> OnboardingFailureCode.QC_BROKER_REJECTED
                        }
                    eventsChannel.send(UsersEvent.NeedsLogin(result.userId, result.username, failureCode))
                }
                is SetCurrentUserResult.Failed -> {
                    eventsChannel.send(UsersEvent.Failed(result.code))
                }
            }
        }
    }

    private fun selectCompanionUser(candidate: CompanionCandidate) {
        viewModelScope.launch {
            if (!candidate.isVerified) {
                eventsChannel.send(
                    UsersEvent.NeedsLogin(
                        userId = candidate.userId,
                        username = candidate.username,
                        failureCode = OnboardingFailureCode.PAIRING_NO_VERIFIED_USER,
                    ),
                )
                return@launch
            }
            if (!candidate.quickConnectBrokerEnabled) {
                eventsChannel.send(
                    UsersEvent.NeedsLogin(
                        userId = candidate.userId,
                        username = candidate.username,
                        failureCode = OnboardingFailureCode.QC_BROKER_REJECTED,
                    ),
                )
                return@launch
            }
            if (_state.value.isConnectingCompanion) return@launch
            val endpoint = CompanionEndpoint.fromPreferences(appPreferences)
            if (endpoint == null) {
                eventsChannel.send(
                    UsersEvent.NeedsLogin(
                        userId = candidate.userId,
                        username = candidate.username,
                        failureCode = null,
                    ),
                )
                return@launch
            }
            _state.update { it.copy(isConnectingCompanion = true) }
            try {
                val outcome =
                    companionConfigImporter.importFromEndpoint(
                        endpoint = endpoint,
                        mode = ImportMode.PAIRING,
                        selectedUser =
                            SelectedCompanionUser(
                                serverId = candidate.serverId,
                                userId = candidate.userId,
                                username = candidate.username,
                            ),
                    )
                when (outcome) {
                    is CompanionImportOutcome.Activated ->
                        eventsChannel.send(UsersEvent.UserActivated)
                    is CompanionImportOutcome.BrokerFallbackToLogin ->
                        eventsChannel.send(
                            UsersEvent.NeedsLogin(
                                userId = candidate.userId,
                                username = outcome.prefilledUsername ?: candidate.username,
                                failureCode = outcome.failureCode,
                            ),
                        )
                    is CompanionImportOutcome.Failed ->
                        eventsChannel.send(UsersEvent.Failed(outcome.code))
                    is CompanionImportOutcome.NeedsUserSelection ->
                        eventsChannel.send(
                            UsersEvent.NeedsLogin(
                                userId = candidate.userId,
                                username = candidate.username,
                                failureCode = null,
                            ),
                        )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                eventsChannel.send(UsersEvent.Failed(t.toOnboardingFailureCode()))
            } finally {
                _state.update { it.copy(isConnectingCompanion = false) }
            }
        }
    }

    fun onAction(action: UsersAction) {
        when (action) {
            is UsersAction.OnUserClick -> loginAsUser(action.userId)
            is UsersAction.OnCompanionUserClick -> selectCompanionUser(action.candidate)
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
