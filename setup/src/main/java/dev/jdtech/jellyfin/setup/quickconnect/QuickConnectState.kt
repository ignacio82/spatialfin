package dev.jdtech.jellyfin.setup.quickconnect

import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import org.jellyfin.sdk.model.api.AuthenticationResult

sealed interface QuickConnectState {
    data object Idle : QuickConnectState

    data object Checking : QuickConnectState

    data object DisabledOnServer : QuickConnectState

    data class Active(
        val code: String,
        val secret: String,
        val rotation: Int,
        val startedAtEpochMs: Long,
        val rotatedNotice: Boolean = false,
    ) : QuickConnectState

    data class Authenticated(val result: AuthenticationResult) : QuickConnectState

    data object Expired : QuickConnectState

    data class Failed(val code: OnboardingFailureCode) : QuickConnectState
}
