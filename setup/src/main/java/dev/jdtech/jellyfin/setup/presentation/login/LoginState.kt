package dev.jdtech.jellyfin.setup.presentation.login

import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.setup.quickconnect.QuickConnectState

data class LoginState(
    val serverName: String? = null,
    val disclaimer: String? = null,
    val quickConnectState: QuickConnectState = QuickConnectState.Idle,
    val isLoading: Boolean = false,
    val error: UiText? = null,
    val failureCode: OnboardingFailureCode? = null,
)
