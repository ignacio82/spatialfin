package dev.jdtech.jellyfin.presentation.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.NetworkShareDto
import dev.jdtech.jellyfin.models.NetworkVideoItem
import dev.jdtech.jellyfin.repository.NetworkMediaRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NetworkState(
    val shares: List<NetworkShareDto> = emptyList(),
    val resumeItems: List<NetworkVideoItem> = emptyList(),
    val homeVisibilityByShareId: Map<String, Boolean> = emptyMap(),
    val isLoading: Boolean = false,
    val error: Throwable? = null,
)

@HiltViewModel
class NetworkViewModel @Inject constructor(
    private val repository: NetworkMediaRepository,
    private val appPreferences: AppPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow(NetworkState())
    val state = _state.asStateFlow()

    fun loadShares() {
        viewModelScope.launch {
            _state.emit(_state.value.copy(isLoading = true, error = null))
            try {
                val shares = repository.getShares()
                val resumeItems = repository.getResumeItems()
                _state.emit(
                    _state.value.copy(
                        shares = shares,
                        resumeItems = resumeItems,
                        homeVisibilityByShareId = shares.associate { share ->
                            share.id to appPreferences.isNetworkShareHomeVisible(share.id)
                        },
                        isLoading = false,
                    )
                )
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e, isLoading = false))
            }
        }
    }

    fun removeShare(shareId: String) {
        viewModelScope.launch {
            repository.removeShare(shareId)
            loadShares()
        }
    }

    fun setShareVisibleOnHome(shareId: String, visible: Boolean) {
        appPreferences.setNetworkShareHomeVisible(shareId, visible)
        _state.value = _state.value.copy(
            homeVisibilityByShareId = _state.value.homeVisibilityByShareId + (shareId to visible),
        )
    }
}
