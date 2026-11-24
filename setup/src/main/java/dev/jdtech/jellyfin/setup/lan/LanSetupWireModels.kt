package dev.jdtech.jellyfin.setup.lan

import dev.jdtech.jellyfin.companion.CompanionCandidate
import dev.jdtech.jellyfin.models.companion.CompanionTvPairingEnvelope
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.setup.quickconnect.QuickConnectState
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// NOTE: All @Serializable DTOs in this file MUST NOT declare named `companion object`s
// (see SpatialFin/data/proguard-rules.pro:29-40 and app/unified/proguard-rules.pro).

@Serializable
enum class SetupStage {
    @SerialName("waiting_quick_connect")
    WAITING_QUICK_CONNECT,

    @SerialName("awaiting_device_login")
    AWAITING_DEVICE_LOGIN,

    @SerialName("activating")
    ACTIVATING,

    @SerialName("done")
    DONE,

    @SerialName("failed")
    FAILED,
}

@Serializable
data class LanSetupErrorResponse(
    val error: String,
    @SerialName("error_code") val errorCode: String,
)

@Serializable
data class ServerSubmitRequest(
    @SerialName("server_url") val serverUrl: String,
)

@Serializable
data class ServerSubmitResponse(
    val status: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("server_name") val serverName: String,
    @SerialName("server_url") val serverUrl: String,
    @SerialName("quick_connect") val quickConnect: Boolean,
    @SerialName("quick_connect_code") val quickConnectCode: String? = null,
)

@Serializable
data class CancelSessionRequest(
    @SerialName("session_id") val sessionId: String? = null,
    val force: Boolean = false,
)

@Serializable
data class CancelSessionResponse(
    val cancelled: Boolean = true,
)

@Serializable
data class CompanionHandoffRequest(
    @SerialName("companion_url") val companionUrl: String,
    @SerialName("setup_token") val setupToken: String,
    @SerialName("server_id") val serverId: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("share_only") val shareOnly: Boolean = false,
)

@Serializable
data class CompanionHandoffResponse(
    val ok: Boolean = true,
    val status: String,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("server_url") val serverUrl: String? = null,
    @SerialName("prefilled_username") val prefilledUsername: String? = null,
    val error: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
)

@Serializable
data class SetupStatusResponse(
    @SerialName("session_id") val sessionId: String,
    val stage: SetupStage,
    @SerialName("server_url") val serverUrl: String? = null,
    @SerialName("quick_connect_code") val quickConnectCode: String? = null,
    @SerialName("quick_connect_rotation") val quickConnectRotation: Int? = null,
    val error: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
)

enum class LanSetupMode {
    BROWSER_URL_ENTRY,
    COMPANION_RECEIVER,
}

enum class DrainOutcome {
    ACTIVATED,
    FALLBACK_TO_LOGIN,
}

data class LeaseHandle(
    val id: UUID,
    val mode: LanSetupMode,
)

data class PendingHandoffUserSelection(
    val candidates: List<CompanionCandidate>,
)

sealed interface LanSetupHttpResult<out T> {
    data class Ok<T>(val body: T) : LanSetupHttpResult<T>

    data class Error(
        val statusCode: Int,
        val errorCode: String,
        val message: String,
    ) : LanSetupHttpResult<Nothing>
}

enum class SecretCheckResult {
    VALID,
    REJECTED,
    EXPIRED,
}

data class LanSetupServerSnapshot(
    val isRunning: Boolean = false,
    val localUrls: List<String> = emptyList(),
    val boundPort: Int = LanSetupServer.DEFAULT_PORT,
    val setupUrl: String? = null,
    val setupQrUrl: String? = null,
    val receiverPairingQrJson: String? = null,
    val formattedManualCode: String? = null,
    val expiresAtEpochMs: Long = 0L,
    val manualCodeExpired: Boolean = false,
    val nsdWarning: OnboardingFailureCode? = null,
)

class HandoffReplayRecord(
    val credentialDigest: ByteArray,
    val setupTokenDigest: ByteArray,
    val companionUrl: String,
    val serverId: String?,
    val userId: String?,
    val shareOnly: Boolean,
    val response: CompanionHandoffResponse,
) {
    fun matchesCredential(candidateDigest: ByteArray): Boolean =
        MessageDigest.isEqual(credentialDigest, candidateDigest)

    fun matchesBody(
        setupTokenDigest: ByteArray,
        companionUrl: String,
        serverId: String?,
        userId: String?,
        shareOnly: Boolean,
    ): Boolean =
        MessageDigest.isEqual(this.setupTokenDigest, setupTokenDigest) &&
            this.companionUrl == companionUrl &&
            this.serverId == serverId &&
            this.userId == userId &&
            this.shareOnly == shareOnly
}

interface LanSetupServerCallbacks {
    suspend fun onServerSubmitted(
        serverUrl: String,
        capturedSecretsEpoch: Long,
        usedManualCode: Boolean,
        deadlineElapsedMs: Long,
    ): LanSetupHttpResult<ServerSubmitResponse>

    suspend fun onCompanionHandoff(
        request: CompanionHandoffRequest,
        credentialDigest: ByteArray,
        capturedSecretsEpoch: Long,
        usedManualCode: Boolean,
        deadlineElapsedMs: Long,
    ): LanSetupHttpResult<CompanionHandoffResponse>

    suspend fun onPairingEnvelope(
        envelope: CompanionTvPairingEnvelope,
        credentialDigest: ByteArray,
        capturedSecretsEpoch: Long,
        usedManualCode: Boolean,
        deadlineElapsedMs: Long,
    ): LanSetupHttpResult<CompanionHandoffResponse>

    suspend fun onCancelSession(
        sessionId: String?,
        force: Boolean,
        capturedSecretsEpoch: Long,
        usedManualCode: Boolean,
    ): LanSetupHttpResult<CancelSessionResponse>

    fun onStatusRequested(sessionId: String): SetupStatusResponse?

    fun onWatchRejected()
}

data class LanSetupUiState(
    val isRunning: Boolean = false,
    val localUrls: List<String> = emptyList(),
    val setupUrl: String? = null,
    val setupQrUrl: String? = null,
    val receiverPairingQrJson: String? = null,
    val formattedManualCode: String? = null,
    val expiresAtEpochMs: Long = 0L,
    val manualCodeExpired: Boolean = false,
    val nsdWarning: OnboardingFailureCode? = null,
    val activeSessionId: String? = null,
    val stage: SetupStage? = null,
    val quickConnectState: QuickConnectState = QuickConnectState.Idle,
    val isCompletingUserSelection: Boolean = false,
    val errorCode: OnboardingFailureCode? = null,
)

sealed interface LanSetupNavigationEvent {
    val sessionGeneration: Long

    data class NavigateToLogin(
        val serverId: String,
        val serverName: String,
        val serverAddress: String,
        val prefilledUsername: String? = null,
        val lanSetupSessionId: String? = null,
        val initialFailureCode: OnboardingFailureCode? = null,
        override val sessionGeneration: Long,
    ) : LanSetupNavigationEvent

    data class ReturnToAddServer(
        override val sessionGeneration: Long,
    ) : LanSetupNavigationEvent

    data class NavigateToSetUpFromDevice(
        override val sessionGeneration: Long,
    ) : LanSetupNavigationEvent
}
