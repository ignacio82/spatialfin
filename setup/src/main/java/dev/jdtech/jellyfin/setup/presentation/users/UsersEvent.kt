package dev.jdtech.jellyfin.setup.presentation.users

import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import java.util.UUID

sealed interface UsersEvent {
    data object UserActivated : UsersEvent

    data class NeedsLogin(
        val userId: UUID?,
        val username: String,
        val failureCode: OnboardingFailureCode? = null,
    ) : UsersEvent

    data class Failed(val code: OnboardingFailureCode) : UsersEvent
}
