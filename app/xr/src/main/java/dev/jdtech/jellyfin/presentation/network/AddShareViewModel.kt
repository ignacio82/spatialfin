package dev.jdtech.jellyfin.presentation.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.network.DiscoveredShare
import dev.jdtech.jellyfin.network.NetworkCredentials
import dev.jdtech.jellyfin.network.NetworkFileClientFactory
import dev.jdtech.jellyfin.repository.NetworkMediaRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AddShareState(
    val discoveredShares: List<DiscoveredShare> = emptyList(),
    val isDiscovering: Boolean = false,
    val isTesting: Boolean = false,
    val isSaving: Boolean = false,
    val testResult: Boolean? = null,
    val saved: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AddShareViewModel @Inject constructor(
    private val repository: NetworkMediaRepository,
    private val clientFactory: NetworkFileClientFactory,
) : ViewModel() {
    private val _state = MutableStateFlow(AddShareState())
    val state = _state.asStateFlow()

    fun discoverShares() {
        viewModelScope.launch {
            _state.emit(_state.value.copy(isDiscovering = true))
            try {
                val discovered = repository.discoverShares()
                _state.emit(
                    _state.value.copy(
                        discoveredShares = discovered,
                        isDiscovering = false,
                    )
                )
            } catch (e: Exception) {
                _state.emit(
                    _state.value.copy(
                        isDiscovering = false,
                        error = e.message,
                    )
                )
            }
        }
    }

    fun testConnection(
        protocol: String,
        host: String,
        shareName: String,
        username: String?,
        password: String?,
        domain: String?,
    ) {
        viewModelScope.launch {
            _state.emit(_state.value.copy(isTesting = true, testResult = null))
            val success = clientFactory.clientFor(protocol).testConnection(
                host = host,
                shareName = shareName,
                credentials = NetworkCredentials(
                    username = username?.takeIf { it.isNotBlank() },
                    password = password?.takeIf { it.isNotBlank() },
                    domain = domain?.takeIf { it.isNotBlank() },
                ),
            )
            _state.emit(_state.value.copy(isTesting = false, testResult = success))
        }
    }

    fun saveShare(
        protocol: String,
        host: String,
        shareName: String,
        username: String?,
        password: String?,
        domain: String?,
        displayName: String?,
    ) {
        viewModelScope.launch {
            _state.emit(_state.value.copy(isSaving = true))
            try {
                val share = repository.addShare(
                    protocol = protocol,
                    host = host,
                    shareName = shareName,
                    username = username?.takeIf { it.isNotBlank() },
                    password = password?.takeIf { it.isNotBlank() },
                    domain = domain?.takeIf { it.isNotBlank() },
                    displayName = displayName?.takeIf { it.isNotBlank() },
                )
                // Auto-scan after adding
                repository.scanShare(share.id)
                // Auto-enrich metadata
                repository.enrichMetadata(share.id)
                _state.emit(_state.value.copy(isSaving = false, saved = true))
            } catch (e: Exception) {
                _state.emit(
                    _state.value.copy(
                        isSaving = false,
                        error = e.message,
                    )
                )
            }
        }
    }
}
