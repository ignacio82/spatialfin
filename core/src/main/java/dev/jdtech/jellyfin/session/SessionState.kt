package dev.jdtech.jellyfin.session

import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import java.util.UUID

enum class SessionMode {
    UNCONFIGURED,
    SERVER_AUTHENTICATED,
    SERVER_NEEDS_AUTH,
    STANDALONE_SOURCES,
}

enum class RouteRole {
    SOURCE_PICKER,
    SERVER_CONTENT,
    MA_CONTENT,
    OFFLINE_OR_LOCAL_CONTENT,
    SETUP_FLOW,
    REAUTH_GATE,
    SETTINGS,
}

enum class SessionChangeReason {
    INITIAL_BOOT,
    MANUAL_LOGIN,
    QUICK_CONNECT,
    LAN_SETUP_PAGE,
    COMPANION_HANDOFF,
    COMPANION_QR,
    COMPANION_BACKGROUND_SYNC,
    SERVER_SWITCH,
    USER_SWITCH,
    ADDRESS_REFRESH,
    STANDALONE_ONBOARDING,
    LOGOUT,
}

data class CompanionCredentials(
    val url: String,
    val token: String,
)

data class SessionState(
    val isLoading: Boolean = true,
    val coordinatorInstanceId: String = "",
    val sessionMode: SessionMode = SessionMode.UNCONFIGURED,
    val sessionEpoch: Long = 0L,
    val activationEpoch: Long = 0L,
    val connectionEpoch: Long = 0L,
    val activeServerId: String? = null,
    val activeServerName: String? = null,
    val activeServerAddress: String? = null,
    val activeUserId: UUID? = null,
    val activeUser: User? = null, // accessToken always null
    val hasServers: Boolean = false,
    val savedServerIds: Set<String> = emptySet(),
    val hasNetworkShares: Boolean = false,
    val standaloneModeChosen: Boolean = false,
)

data class BackgroundUserSyncUpdate(
    val user: User,
    val clearUserTokenOnAuthFailure: Boolean = false,
    val expectedPreviousToken: String? = null,
    val overwritePreferences: Boolean = true,
)

data class BackgroundServerSyncUpdate(
    val serverId: String,
    val serverName: String? = null,
    val addressUrl: String? = null,
    val addresses: List<String> = emptyList(),
    val legacyServerIds: List<String> = emptyList(),
    val users: List<BackgroundUserSyncUpdate> = emptyList(),
)

sealed interface BackgroundSyncResult {
    data class Committed(val sessionEpoch: Long) : BackgroundSyncResult

    data object EpochMismatch : BackgroundSyncResult

    data class Failed(val code: OnboardingFailureCode) : BackgroundSyncResult
}

sealed interface ActivationResult {
    data class Activated(
        val serverId: String,
        val userId: UUID,
        val serverAddress: String,
        val sessionEpoch: Long,
        val activationEpoch: Long,
    ) : ActivationResult

    data class NeedsAuth(
        val serverId: String,
        val serverName: String,
        val serverAddress: String,
    ) : ActivationResult

    data class Failed(val code: OnboardingFailureCode) : ActivationResult
}

fun deriveSessionMode(
    hasCurrentServer: Boolean,
    hasCurrentServerAddress: Boolean,
    hasCurrentUserWithToken: Boolean,
    hasNetworkShares: Boolean,
    standaloneModeChosen: Boolean,
): SessionMode =
    when {
        hasCurrentServer && hasCurrentServerAddress && hasCurrentUserWithToken ->
            SessionMode.SERVER_AUTHENTICATED
        hasCurrentServer ->
            SessionMode.SERVER_NEEDS_AUTH
        hasNetworkShares || standaloneModeChosen ->
            SessionMode.STANDALONE_SOURCES
        else ->
            SessionMode.UNCONFIGURED
    }
