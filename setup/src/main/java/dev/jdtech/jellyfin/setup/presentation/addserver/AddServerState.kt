package dev.jdtech.jellyfin.setup.presentation.addserver

import dev.jdtech.jellyfin.models.DiscoveredServer
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode

data class AddServerState(
    val isLoading: Boolean = false,
    val isDiscovering: Boolean = false,
    val discoveredServers: List<DiscoveredServer> = emptyList(),
    val error: Collection<UiText>? = null,
    val failureCode: OnboardingFailureCode? = null,
)
