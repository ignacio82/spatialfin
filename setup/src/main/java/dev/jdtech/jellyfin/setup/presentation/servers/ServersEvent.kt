package dev.jdtech.jellyfin.setup.presentation.servers

import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode

sealed interface ServersEvent {
    data object ServerChanged : ServersEvent

    data class NavigateToUsers(
        val serverId: String,
        val serverName: String,
        val serverAddress: String,
    ) : ServersEvent

    data class Failed(val code: OnboardingFailureCode) : ServersEvent
}
