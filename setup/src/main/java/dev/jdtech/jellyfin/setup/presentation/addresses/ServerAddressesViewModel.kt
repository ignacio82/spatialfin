package dev.jdtech.jellyfin.setup.presentation.addresses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.normalizeJellyfinId
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.onboarding.OnboardingFailureException
import dev.jdtech.jellyfin.onboarding.toOnboardingFailureCode
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class ServerAddressesViewModel
@Inject
constructor(
    private val setupRepository: SetupRepository,
    private val database: ServerDatabaseDao,
) : ViewModel() {
    private val _state = MutableStateFlow(ServerAddressesState())
    val state = _state.asStateFlow()

    private var currentServerId: String = ""

    fun loadAddresses(serverId: String) {
        currentServerId = serverId
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val serverWithAddresses = database.getServerWithAddresses(serverId)
                _state.update {
                    it.copy(addresses = serverWithAddresses?.addresses.orEmpty())
                }
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    fun deleteAddress(addressId: UUID) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentAddress = database.getServerCurrentAddress(currentServerId)
            if (addressId == currentAddress?.id) {
                Timber.e("You cannot delete the current address")
                return@launch
            }
            database.deleteServerAddress(addressId)
            loadAddresses(currentServerId)
        }
    }

    fun addAddress(address: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isAddingAddress = true, addError = null) }
            try {
                val probed = setupRepository.probeServer(address)
                val normalizedProbedId = normalizeJellyfinId(probed.serverId)
                val isLegacy = currentServerId.startsWith("srv-")
                if (!isLegacy && normalizedProbedId != normalizeJellyfinId(currentServerId)) {
                    _state.update {
                        it.copy(
                            isAddingAddress = false,
                            addError = OnboardingFailureCode.SERVER_IDENTITY_MISMATCH,
                        )
                    }
                    return@launch
                }
                // Check if another canonical Room Server already owns probed.serverId
                val allServers = database.getAllServersSync()
                val ownedByOther =
                    allServers.any { srv ->
                        srv.id != currentServerId &&
                            !srv.id.startsWith("srv-") &&
                            normalizeJellyfinId(srv.id) == normalizedProbedId
                    }
                if (ownedByOther) {
                    _state.update {
                        it.copy(
                            isAddingAddress = false,
                            addError = OnboardingFailureCode.SERVER_IDENTITY_MISMATCH,
                        )
                    }
                    return@launch
                }

                val normalizedAddress = probed.serverAddress.trim().trimEnd('/')
                val existingAddresses = database.getServerWithAddresses(currentServerId)?.addresses.orEmpty()
                val duplicate =
                    existingAddresses.any {
                        it.address.trim().trimEnd('/').equals(normalizedAddress, ignoreCase = true)
                    }
                if (!duplicate) {
                    val serverAddress = ServerAddress(UUID.randomUUID(), currentServerId, normalizedAddress)
                    database.insertServerAddress(serverAddress)
                }
                val refreshed = database.getServerWithAddresses(currentServerId)?.addresses.orEmpty()
                _state.update {
                    it.copy(
                        addresses = refreshed,
                        isAddDialogOpen = false,
                        isAddingAddress = false,
                        addError = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OnboardingFailureException) {
                _state.update { it.copy(isAddingAddress = false, addError = e.code) }
            } catch (e: Throwable) {
                Timber.w(e, "ServerAddressesViewModel: addAddress failed")
                _state.update { it.copy(isAddingAddress = false, addError = e.toOnboardingFailureCode()) }
            }
        }
    }

    fun onAction(action: ServerAddressesAction) {
        when (action) {
            ServerAddressesAction.OpenAddAddressDialog -> {
                _state.update { it.copy(isAddDialogOpen = true, addError = null) }
            }
            ServerAddressesAction.DismissAddAddressDialog -> {
                _state.update { it.copy(isAddDialogOpen = false, isAddingAddress = false, addError = null) }
            }
            is ServerAddressesAction.AddAddress -> addAddress(action.address)
            is ServerAddressesAction.DeleteAddress -> deleteAddress(action.addressId)
            else -> Unit
        }
    }
}
