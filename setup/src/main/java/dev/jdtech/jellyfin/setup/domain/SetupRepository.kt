package dev.jdtech.jellyfin.setup.domain

import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.ServerWithAddresses
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.session.ActivationResult
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo

data class ProbedServer(
    val serverId: String,
    val serverName: String,
    val serverAddress: String,
)

data class AddedServer(
    val server: Server,
    val address: ServerAddress,
)

sealed interface SetCurrentServerResult {
    data object Activated : SetCurrentServerResult

    data class NeedsUserSelection(
        val serverId: String,
        val serverName: String,
        val serverAddress: String,
    ) : SetCurrentServerResult

    data class Failed(val code: OnboardingFailureCode) : SetCurrentServerResult
}

sealed interface SetCurrentUserResult {
    data object Activated : SetCurrentUserResult

    data class NeedsLogin(
        val userId: UUID,
        val username: String,
    ) : SetCurrentUserResult

    data class Failed(val code: OnboardingFailureCode) : SetCurrentUserResult
}

interface SetupRepository {
    fun discoverServers(): Flow<ServerDiscoveryInfo>

    suspend fun getServers(): List<ServerWithAddresses>

    suspend fun getCurrentServer(): Server?

    /** Resolve the current server's currently-selected address (base URL). */
    suspend fun getCurrentServerAddress(): String?

    suspend fun deleteServer(serverId: String)

    suspend fun setCurrentServer(serverId: String): SetCurrentServerResult

    suspend fun probeServer(address: String, timeoutMs: Long = 8_000L): ProbedServer

    suspend fun addServer(address: String): AddedServer

    suspend fun loadDisclaimer(serverAddress: String): String?

    suspend fun login(
        serverId: String,
        serverName: String,
        serverAddress: String,
        username: String,
        password: String,
        beforeCommit: (suspend () -> Boolean)? = null,
    ): ActivationResult

    suspend fun getUsers(serverId: String): List<User>

    suspend fun getPublicUsers(serverId: String, serverAddress: String): List<User>

    suspend fun getCurrentUser(): User?

    suspend fun deleteUser(userId: UUID)

    suspend fun setCurrentUser(serverId: String, userId: UUID): SetCurrentUserResult

    suspend fun setCurrentAddress(serverId: String, addressId: UUID): ActivationResult
}
