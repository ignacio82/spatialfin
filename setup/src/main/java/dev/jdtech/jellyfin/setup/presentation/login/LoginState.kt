package dev.jdtech.jellyfin.setup.presentation.login

import dev.jdtech.jellyfin.companion.CompanionCandidate
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.setup.quickconnect.QuickConnectState

sealed interface CompanionRelinkState {
    data object Idle : CompanionRelinkState

    data object Linking : CompanionRelinkState

    data class Failed(
        val message: String,
        val isAdminPasswordError: Boolean = false,
    ) : CompanionRelinkState

    data class Succeeded(val username: String?) : CompanionRelinkState
}

data class LoginState(
    val serverName: String? = null,
    val disclaimer: String? = null,
    val quickConnectState: QuickConnectState = QuickConnectState.Idle,
    val isLoading: Boolean = false,
    val error: UiText? = null,
    val failureCode: OnboardingFailureCode? = null,
    val companionUrl: String? = null,
    val showCompanionRelinkOption: Boolean = false,
    val companionRelinkEnabled: Boolean = false,
    val companionRelinkState: CompanionRelinkState = CompanionRelinkState.Idle,
    val pendingSignedInUsername: String? = null,
    val isCheckingCompanion: Boolean = false,
    val companionSelectionCandidates: List<CompanionCandidate> = emptyList(),
    val suggestedUsername: String? = null,
)
