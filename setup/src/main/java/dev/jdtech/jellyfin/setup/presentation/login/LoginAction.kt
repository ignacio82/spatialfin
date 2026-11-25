package dev.jdtech.jellyfin.setup.presentation.login

import java.util.UUID

sealed interface LoginAction {
    data class OnLoginClick(val username: String, val password: String) : LoginAction

    data object OnChangeServerClick : LoginAction

    data object OnStartQuickConnect : LoginAction

    data object OnCancelQuickConnect : LoginAction

    data object OnRetryQuickConnect : LoginAction

    data object OnBackClick : LoginAction

    data class OnCompanionRelinkToggled(val enabled: Boolean) : LoginAction

    data class OnCompanionAdminPasswordChanged(val password: String) : LoginAction

    data class OnRetryCompanionRelink(val adminPassword: String) : LoginAction

    data object OnSkipCompanionRelinkAndContinue : LoginAction

    data object OnRetryCompanionPairing : LoginAction

    data class OnSelectCompanionCandidate(
        val serverId: String,
        val userId: UUID?,
        val username: String? = null,
    ) : LoginAction
}
