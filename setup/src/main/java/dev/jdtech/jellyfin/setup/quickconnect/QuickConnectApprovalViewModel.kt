package dev.jdtech.jellyfin.setup.quickconnect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.quickconnect.ApproveResult
import dev.jdtech.jellyfin.quickconnect.QuickConnectClient
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionMode
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface QuickConnectApprovalState {
    data object Idle : QuickConnectApprovalState

    data object Submitting : QuickConnectApprovalState

    data object Approved : QuickConnectApprovalState

    data class Failed(val code: OnboardingFailureCode) : QuickConnectApprovalState
}

@HiltViewModel
class QuickConnectApprovalViewModel
@Inject
constructor(
    private val coordinator: SessionActivationCoordinator,
    private val database: ServerDatabaseDao,
    private val quickConnectClient: QuickConnectClient,
) : ViewModel() {

    private val _state = MutableStateFlow<QuickConnectApprovalState>(QuickConnectApprovalState.Idle)
    val state: StateFlow<QuickConnectApprovalState> = _state.asStateFlow()

    fun reset() {
        _state.value = QuickConnectApprovalState.Idle
    }

    fun approve(code: String) {
        if (_state.value is QuickConnectApprovalState.Submitting) return
        val session = coordinator.sessionState.value
        val activeServerAddress = session.activeServerAddress?.takeIf { it.isNotBlank() }
        val activeUserId = session.activeUserId
        if (session.sessionMode != SessionMode.SERVER_AUTHENTICATED ||
            activeServerAddress == null ||
            activeUserId == null
        ) {
            _state.value = QuickConnectApprovalState.Failed(OnboardingFailureCode.AUTH_INVALID_CREDENTIALS)
            return
        }

        _state.value = QuickConnectApprovalState.Submitting
        viewModelScope.launch {
            val accessToken =
                withContext(Dispatchers.IO) {
                    database.getUser(activeUserId)?.accessToken?.takeIf { it.isNotBlank() }
                }
            if (accessToken == null) {
                _state.value = QuickConnectApprovalState.Failed(OnboardingFailureCode.AUTH_INVALID_CREDENTIALS)
                return@launch
            }
            _state.value =
                when (val result = quickConnectClient.approve(activeServerAddress, accessToken, code)) {
                    ApproveResult.Approved -> QuickConnectApprovalState.Approved
                    is ApproveResult.Failed -> QuickConnectApprovalState.Failed(result.code)
                }
        }
    }
}
