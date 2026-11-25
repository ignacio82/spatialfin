package dev.jdtech.jellyfin.setup.data

import android.content.Context
import android.net.wifi.WifiManager
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerWithAddresses
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.normalizeJellyfinId
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.onboarding.OnboardingFailureException
import dev.jdtech.jellyfin.onboarding.toOnboardingFailureCode
import dev.jdtech.jellyfin.session.ActivationResult
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionChangeReason
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.setup.domain.AddedServer
import dev.jdtech.jellyfin.setup.domain.AuthenticateUserResult
import dev.jdtech.jellyfin.setup.domain.ProbedServer
import dev.jdtech.jellyfin.setup.domain.SetCurrentServerResult
import dev.jdtech.jellyfin.setup.domain.SetCurrentUserResult
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jellyfin.sdk.api.client.extensions.brandingApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.discovery.RecommendedServerInfo
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.discovery.RecommendedServerIssue
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo
import timber.log.Timber

class SetupRepositoryImpl(
    private val context: Context,
    private val jellyfinApi: JellyfinApi,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
    private val coordinator: SessionActivationCoordinator,
) : SetupRepository {

    override fun discoverServers(): Flow<ServerDiscoveryInfo> = flow {
        val multicastLock =
            runCatching {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifiManager?.createMulticastLock("SpatialFinServerDiscovery")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.onFailure {
                Timber.d(it, "Could not acquire WifiManager.MulticastLock for Jellyfin discovery")
            }.getOrNull()

        try {
            jellyfinApi.jellyfin.discovery.discoverLocalServers().collect { info ->
                emit(info)
            }
        } finally {
            runCatching {
                if (multicastLock?.isHeld == true) {
                    multicastLock.release()
                }
            }
        }
    }

    override suspend fun getServers(): List<ServerWithAddresses> =
        withContext(Dispatchers.IO) { database.getServersWithAddresses() }

    override suspend fun getCurrentServer(): Server? =
        withContext(Dispatchers.IO) {
            val id = coordinator.sessionState.value.activeServerId
                ?: appPreferences.getValue(appPreferences.currentServer)
                ?: return@withContext null
            database.get(id)
        }

    override suspend fun getCurrentServerAddress(): String? =
        withContext(Dispatchers.IO) {
            val server = getCurrentServer() ?: return@withContext null
            val addressId = server.currentServerAddressId ?: return@withContext null
            runCatching { database.getAddress(addressId)?.address }.getOrNull()
        }

    override suspend fun deleteServer(serverId: String) {
        coordinator.deleteServerAndNotify(serverId)
    }

    override suspend fun setCurrentServer(serverId: String): SetCurrentServerResult =
        withContext(Dispatchers.IO) {
            val serverWithAddressAndUser = database.getServerWithAddressAndUser(serverId)
                ?: return@withContext SetCurrentServerResult.Failed(OnboardingFailureCode.STORAGE_ERROR)
            val server = serverWithAddressAndUser.server
            val address = serverWithAddressAndUser.address
                ?: database.getServerWithAddresses(serverId)?.addresses?.firstOrNull()
                ?: return@withContext SetCurrentServerResult.Failed(OnboardingFailureCode.STORAGE_ERROR)
            val user = serverWithAddressAndUser.user
            if (user == null || user.accessToken.isNullOrBlank()) {
                return@withContext SetCurrentServerResult.NeedsUserSelection(
                    serverId = server.id,
                    serverName = server.name,
                    serverAddress = address.address,
                )
            }
            when (
                val res =
                    coordinator.activateServer(
                        serverId = server.id,
                        addressUrl = address.address,
                        reason = SessionChangeReason.SERVER_SWITCH,
                        user = user,
                        serverName = server.name,
                        isDedicatedToken = false,
                        overwriteUserPreferences = false,
                    )
            ) {
                is ActivationResult.Activated -> SetCurrentServerResult.Activated
                is ActivationResult.NeedsAuth ->
                    SetCurrentServerResult.NeedsUserSelection(
                        serverId = res.serverId,
                        serverName = res.serverName,
                        serverAddress = res.serverAddress,
                    )
                is ActivationResult.Failed -> SetCurrentServerResult.Failed(res.code)
            }
        }

    override suspend fun probeServer(address: String, timeoutMs: Long): ProbedServer =
        withContext(Dispatchers.IO) {
            val trimmed = address.trim()
            if (trimmed.isEmpty()) {
                throw OnboardingFailureException(
                    OnboardingFailureCode.URL_DNS_UNRESOLVED,
                    "Empty server address",
                )
            }
            try {
                withTimeout(timeoutMs.coerceAtLeast(1_000L)) {
                    val candidates = jellyfinApi.jellyfin.discovery.getAddressCandidates(trimmed)
                    if (candidates.isEmpty()) {
                        throw OnboardingFailureException(
                            OnboardingFailureCode.URL_DNS_UNRESOLVED,
                            "No valid address candidates",
                        )
                    }
                    val recommended =
                        jellyfinApi.jellyfin.discovery.getRecommendedServers(
                            candidates,
                            RecommendedServerInfoScore.OK,
                        )
                    val goodServers = mutableListOf<RecommendedServerInfo>()
                    val okServers = mutableListOf<RecommendedServerInfo>()
                    val rejectedServers = mutableListOf<RecommendedServerInfo>()

                    for (info in recommended) {
                        when (info.score) {
                            RecommendedServerInfoScore.GREAT -> return@withTimeout extractProbedServer(info)
                            RecommendedServerInfoScore.GOOD -> goodServers.add(info)
                            RecommendedServerInfoScore.OK -> okServers.add(info)
                            RecommendedServerInfoScore.BAD -> rejectedServers.add(info)
                        }
                    }

                    when {
                        goodServers.isNotEmpty() -> extractProbedServer(goodServers.first())
                        okServers.isNotEmpty() -> extractProbedServer(okServers.first())
                        rejectedServers.isNotEmpty() -> {
                            throw OnboardingFailureException(
                                mapRejectedIssuesToCode(rejectedServers),
                                "Server probe rejected",
                            )
                        }
                        else -> {
                            throw OnboardingFailureException(
                                OnboardingFailureCode.URL_DNS_UNRESOLVED,
                                "Server not found",
                            )
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                throw OnboardingFailureException(OnboardingFailureCode.URL_TIMEOUT, "Server probe timed out", e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OnboardingFailureException) {
                throw e
            } catch (e: Throwable) {
                throw OnboardingFailureException(e.toOnboardingFailureCode(), e.message, e)
            }
        }

    private fun extractProbedServer(info: RecommendedServerInfo): ProbedServer {
        val systemInfo = info.systemInfo.getOrNull()
        val serverId = systemInfo?.id?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw OnboardingFailureException(
                OnboardingFailureCode.SERVER_HTTP_ERROR,
                "Server returned no systemInfo.id",
            )
        val serverName = systemInfo.serverName?.trim()?.takeIf { it.isNotEmpty() } ?: serverId
        return ProbedServer(
            serverId = serverId,
            serverName = serverName,
            serverAddress = info.address.trim().trimEnd('/'),
        )
    }

    private fun mapRejectedIssuesToCode(rejected: List<RecommendedServerInfo>): OnboardingFailureCode {
        for (server in rejected) {
            val failure = server.systemInfo.exceptionOrNull()
            if (failure != null) {
                val mapped = failure.toOnboardingFailureCode()
                if (mapped != OnboardingFailureCode.SERVER_HTTP_ERROR) return mapped
            }
            for (issue in server.issues) {
                when (issue) {
                    is RecommendedServerIssue.SlowResponse -> return OnboardingFailureCode.URL_TIMEOUT
                    is RecommendedServerIssue.InvalidProductName ->
                        return OnboardingFailureCode.URL_REVERSE_PROXY_HTML_RESPONSE
                    is RecommendedServerIssue.OutdatedServerVersion,
                    is RecommendedServerIssue.UnsupportedServerVersion,
                    -> return OnboardingFailureCode.SERVER_HTTP_ERROR
                    else -> Unit
                }
            }
        }
        return OnboardingFailureCode.URL_DNS_UNRESOLVED
    }

    override suspend fun addServer(address: String): AddedServer {
        val probed = probeServer(address)
        return try {
            val (server, savedAddress) =
                coordinator.saveProbedServer(
                    serverId = probed.serverId,
                    serverName = probed.serverName,
                    addressUrl = probed.serverAddress,
                )
            AddedServer(server = server, address = savedAddress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OnboardingFailureException) {
            throw e
        } catch (e: Throwable) {
            throw OnboardingFailureException(e.toOnboardingFailureCode(), e.message, e)
        }
    }

    override suspend fun loadDisclaimer(serverAddress: String): String? =
        withContext(Dispatchers.IO) {
            val api = jellyfinApi.createApi(baseUrl = serverAddress, accessToken = null)
            api.brandingApi.getBrandingOptions().content.loginDisclaimer
        }

    override suspend fun authenticate(
        serverId: String,
        serverName: String,
        serverAddress: String,
        username: String,
        password: String,
    ): AuthenticateUserResult =
        withContext(Dispatchers.IO) {
            try {
                val api = jellyfinApi.createApi(baseUrl = serverAddress, accessToken = null)
                val authResult by
                    api.userApi.authenticateUserByName(
                        data = AuthenticateUserByName(username = username, pw = password),
                    )
                val returnedServerId = authResult.serverId?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@withContext AuthenticateUserResult.Failed(OnboardingFailureCode.SERVER_IDENTITY_MISMATCH)
                val isLegacyPlaceholder = serverId.startsWith("srv-")
                if (!isLegacyPlaceholder &&
                    normalizeJellyfinId(returnedServerId) != normalizeJellyfinId(serverId)
                ) {
                    return@withContext AuthenticateUserResult.Failed(OnboardingFailureCode.SERVER_IDENTITY_MISMATCH)
                }
                val userDto = authResult.user
                val token = authResult.accessToken?.takeIf { it.isNotBlank() }
                if (userDto == null || token == null) {
                    return@withContext AuthenticateUserResult.Failed(OnboardingFailureCode.AUTH_INVALID_CREDENTIALS)
                }
                val canonicalServerId = if (isLegacyPlaceholder) returnedServerId else serverId
                val user =
                    User(
                        id = userDto.id,
                        name = userDto.name?.takeIf { it.isNotBlank() } ?: username,
                        serverId = canonicalServerId,
                        accessToken = token,
                    )
                AuthenticateUserResult.Authenticated(
                    canonicalServerId = canonicalServerId,
                    serverName = serverName,
                    serverAddress = serverAddress,
                    user = user,
                    legacyServerIds = if (isLegacyPlaceholder) listOf(serverId) else emptyList(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AuthenticateUserResult.Failed(e.toOnboardingFailureCode())
            }
        }

    override suspend fun login(
        serverId: String,
        serverName: String,
        serverAddress: String,
        username: String,
        password: String,
        beforeCommit: (suspend () -> Boolean)?,
    ): ActivationResult =
        withContext(Dispatchers.IO) {
            when (val auth = authenticate(serverId, serverName, serverAddress, username, password)) {
                is AuthenticateUserResult.Failed -> ActivationResult.Failed(auth.code)
                is AuthenticateUserResult.Authenticated -> {
                    if (beforeCommit != null && !beforeCommit.invoke()) {
                        return@withContext ActivationResult.Failed(OnboardingFailureCode.PAIRING_CANCELLED)
                    }
                    coordinator.activateServer(
                        serverId = auth.canonicalServerId,
                        addressUrl = auth.serverAddress,
                        reason = SessionChangeReason.MANUAL_LOGIN,
                        user = auth.user,
                        serverName = auth.serverName,
                        legacyServerIds = auth.legacyServerIds,
                        isDedicatedToken = true,
                        overwriteUserPreferences = false,
                    )
                }
            }
        }

    override suspend fun getUsers(serverId: String): List<User> =
        withContext(Dispatchers.IO) { database.getUsers(serverId) }

    override suspend fun getPublicUsers(serverId: String, serverAddress: String): List<User> =
        withContext(Dispatchers.IO) {
            val api = jellyfinApi.createApi(baseUrl = serverAddress, accessToken = null)
            api.userApi.getPublicUsers().content.mapNotNull {
                User(id = it.id, name = it.name ?: return@mapNotNull null, serverId = serverId)
            }
        }

    override suspend fun getCurrentUser(): User? =
        withContext(Dispatchers.IO) {
            val currentServer = getCurrentServer() ?: return@withContext null
            database.getServerCurrentUser(currentServer.id)
        }

    override suspend fun deleteUser(userId: UUID) {
        coordinator.deleteUserAndNotify(userId)
    }

    override suspend fun setCurrentUser(serverId: String, userId: UUID): SetCurrentUserResult =
        withContext(Dispatchers.IO) {
            val user = database.getUser(userId)
                ?: return@withContext SetCurrentUserResult.Failed(OnboardingFailureCode.STORAGE_ERROR)
            if (user.accessToken.isNullOrBlank()) {
                return@withContext SetCurrentUserResult.NeedsLogin(userId = user.id, username = user.name)
            }
            val serverWithAddresses = database.getServerWithAddresses(serverId)
                ?: return@withContext SetCurrentUserResult.Failed(OnboardingFailureCode.STORAGE_ERROR)
            val server = serverWithAddresses.server
            val address =
                serverWithAddresses.addresses.firstOrNull { it.id == server.currentServerAddressId }
                    ?: serverWithAddresses.addresses.firstOrNull()
                    ?: return@withContext SetCurrentUserResult.Failed(OnboardingFailureCode.STORAGE_ERROR)
            when (
                val res =
                    coordinator.activateServer(
                        serverId = server.id,
                        addressUrl = address.address,
                        reason = SessionChangeReason.USER_SWITCH,
                        user = user,
                        serverName = server.name,
                        isDedicatedToken = false,
                        overwriteUserPreferences = false,
                    )
            ) {
                is ActivationResult.Activated -> SetCurrentUserResult.Activated
                is ActivationResult.NeedsAuth ->
                    SetCurrentUserResult.NeedsLogin(userId = user.id, username = user.name)
                is ActivationResult.Failed -> SetCurrentUserResult.Failed(res.code)
            }
        }

    override suspend fun setCurrentAddress(serverId: String, addressId: UUID): ActivationResult =
        withContext(Dispatchers.IO) {
            if (serverId == coordinator.sessionState.value.activeServerId) {
                return@withContext coordinator.refreshActiveAddress(
                    serverId = serverId,
                    addressId = addressId,
                    expectedEpoch = null,
                )
            }
            val serverWithAddressAndUser = database.getServerWithAddressAndUser(serverId)
                ?: return@withContext ActivationResult.Failed(OnboardingFailureCode.STORAGE_ERROR)
            val address = database.getAddress(addressId)
                ?: return@withContext ActivationResult.Failed(OnboardingFailureCode.STORAGE_ERROR)
            if (address.serverId != serverId) {
                return@withContext ActivationResult.Failed(OnboardingFailureCode.STORAGE_ERROR)
            }
            val server = serverWithAddressAndUser.server
            val user = serverWithAddressAndUser.user
            if (user == null || user.accessToken.isNullOrBlank()) {
                database.updateServerCurrentAddress(serverId, addressId)
                return@withContext ActivationResult.NeedsAuth(
                    serverId = server.id,
                    serverName = server.name,
                    serverAddress = address.address,
                )
            }
            coordinator.activateServer(
                serverId = server.id,
                addressUrl = address.address,
                reason = SessionChangeReason.SERVER_SWITCH,
                user = user,
                serverName = server.name,
                isDedicatedToken = false,
                overwriteUserPreferences = false,
            )
        }
}
