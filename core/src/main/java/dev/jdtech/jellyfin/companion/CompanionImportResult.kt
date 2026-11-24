package dev.jdtech.jellyfin.companion

import dev.jdtech.jellyfin.models.companion.CompanionConfig
import dev.jdtech.jellyfin.models.companion.CompanionEndpoint
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import java.util.UUID

data class SelectedCompanionUser(
    val serverId: String,
    val userId: UUID,
)

data class CompanionCandidate(
    val serverId: String,
    val serverName: String,
    val userId: UUID,
    val username: String,
)

sealed interface CompanionImportOutcome {
    data class Activated(
        val serverUrl: String?,
        val standalone: Boolean = false,
    ) : CompanionImportOutcome

    data class NeedsUserSelection(
        val config: CompanionConfig,
        val endpoint: CompanionEndpoint,
        val policy: UrlTargetPolicy,
        val candidates: List<CompanionCandidate>,
    ) : CompanionImportOutcome

    data class BrokerFallbackToLogin(
        val serverId: String,
        val serverName: String,
        val serverAddress: String,
        val prefilledUsername: String?,
        val failureCode: OnboardingFailureCode,
    ) : CompanionImportOutcome

    data class Failed(
        val code: OnboardingFailureCode,
        val message: String,
    ) : CompanionImportOutcome
}
