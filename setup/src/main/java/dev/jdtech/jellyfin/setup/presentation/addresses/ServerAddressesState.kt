package dev.jdtech.jellyfin.setup.presentation.addresses

import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode

data class ServerAddressesState(
    val addresses: List<ServerAddress> = emptyList(),
    val isAddDialogOpen: Boolean = false,
    val isAddingAddress: Boolean = false,
    val addError: OnboardingFailureCode? = null,
)
