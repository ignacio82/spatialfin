package dev.jdtech.jellyfin.companion

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.NetworkShareDto
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.companion.CompanionConfig
import dev.jdtech.jellyfin.models.companion.CompanionEndpoint
import dev.jdtech.jellyfin.models.companion.CompanionMusicAssistant
import dev.jdtech.jellyfin.models.companion.CompanionNetworkShare
import dev.jdtech.jellyfin.models.companion.CompanionServer
import dev.jdtech.jellyfin.models.companion.CompanionTvPairingEnvelope
import dev.jdtech.jellyfin.models.companion.CompanionUser
import dev.jdtech.jellyfin.models.companion.DeviceIdentity
import dev.jdtech.jellyfin.models.normalizeJellyfinId
import dev.jdtech.jellyfin.models.parseJellyfinUuid
import dev.jdtech.jellyfin.network.SafeUrlBlockedException
import dev.jdtech.jellyfin.network.SafeUrlPolicy
import dev.jdtech.jellyfin.network.SmbConnectionTarget
import dev.jdtech.jellyfin.network.SmbPathNormalizer
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.network.newBuilderWithSsrfGuard
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.onboarding.toOnboardingFailureCode
import dev.jdtech.jellyfin.repository.NetworkMediaRepository
import dev.jdtech.jellyfin.session.ActivationResult
import dev.jdtech.jellyfin.session.BackgroundServerSyncUpdate
import dev.jdtech.jellyfin.session.BackgroundSyncResult
import dev.jdtech.jellyfin.session.BackgroundUserSyncUpdate
import dev.jdtech.jellyfin.session.CompanionCredentials
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionChangeReason
import dev.jdtech.jellyfin.session.SessionMode
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.isSyncableCompanionPreferenceKey
import dev.jdtech.jellyfin.work.CompanionSyncWorker
import dev.jdtech.jellyfin.work.CompanionUserExtrasApplier
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.sdk.api.client.extensions.userApi
import org.json.JSONObject
import timber.log.Timber

enum class ImportMode {
    PAIRING,
    BACKGROUND_SYNC,
}

internal enum class LocalTokenProbeResult {
    VALID,
    UNAUTHORIZED,
    TRANSIENT_ERROR,
}

internal data class ProbedJellyfinServer(
    val serverId: String,
    val serverName: String,
    val addressUrl: String,
    val productName: String,
)

@Singleton
class CompanionConfigImporter
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val appPreferences: AppPreferences,
    private val serverDatabase: ServerDatabaseDao,
    private val jellyfinApi: JellyfinApi,
    private val networkMediaRepository: NetworkMediaRepository,
    private val extrasApplier: CompanionUserExtrasApplier,
    private val sessionActivationCoordinator: SessionActivationCoordinator,
    private val quickConnectBroker: CompanionQuickConnectBroker,
    private val appLockObserver: AppLockObserver,
    private val workManager: dagger.Lazy<WorkManager>,
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val backgroundSyncMutex = Mutex()
    private val postCommitExtrasSupervisor = SupervisorJob()
    private val postCommitExtrasScope = CoroutineScope(
        postCommitExtrasSupervisor + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
            Timber.w(t, "CompanionConfigImporter: post-commit extras failed")
        },
    )

    @Volatile
    internal var allowLoopbackForTests: Boolean = false

    fun cancelPostCommitExtras() {
        postCommitExtrasSupervisor.cancelChildren()
    }

    private fun isAppLocked(): Boolean =
        appLockObserver.lockState.value == AppLockManager.LockState.LOCKED

    suspend fun importFromEndpoint(
        endpoint: CompanionEndpoint,
        mode: ImportMode,
        policy: UrlTargetPolicy = UrlTargetPolicy.LAN_ONLY,
        selectedUser: SelectedCompanionUser? = null,
        shareOnly: Boolean = false,
        handoffDeadlineElapsedMs: Long? = null,
        beforeCommit: (suspend () -> Boolean)? = null,
    ): CompanionImportOutcome {
        if (mode == ImportMode.BACKGROUND_SYNC) {
            if (isAppLocked()) {
                return CompanionImportOutcome.Activated(serverUrl = null, standalone = false)
            }
            return backgroundSyncMutex.withLock {
                if (isAppLocked()) {
                    return@withLock CompanionImportOutcome.Activated(serverUrl = null, standalone = false)
                }
                importFromEndpointInternal(
                    endpoint = endpoint,
                    mode = mode,
                    policy = policy,
                    selectedUser = selectedUser,
                    shareOnly = shareOnly,
                    handoffDeadlineElapsedMs = handoffDeadlineElapsedMs,
                    beforeCommit = beforeCommit,
                )
            }
        }
        return importFromEndpointInternal(
            endpoint = endpoint,
            mode = mode,
            policy = policy,
            selectedUser = selectedUser,
            shareOnly = shareOnly,
            handoffDeadlineElapsedMs = handoffDeadlineElapsedMs,
            beforeCommit = beforeCommit,
        )
    }

    private sealed interface FetchConfigResult {
        data class Success(val config: CompanionConfig) : FetchConfigResult
        data class Failure(val code: OnboardingFailureCode, val message: String) : FetchConfigResult
    }

    private fun fetchConfig(
        endpoint: CompanionEndpoint,
        policy: UrlTargetPolicy,
        timeoutMs: Long,
    ): FetchConfigResult =
        try {
            val client = okHttpClient
                .newBuilderWithSsrfGuard(
                    policy = policy,
                    allowLoopbackForTests = allowLoopbackForTests,
                )
                .connectTimeout(minOf(timeoutMs, 5_000L), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()

            val request = endpoint
                .authorizedRequest(endpoint.configUrl)
                .header("X-SpatialFin-Capabilities", CAPABILITY_QC_BROKER_V1)
                .header("X-Companion-Device", DeviceIdentity.deviceId(context))
                .header("X-Companion-Device-Name", Build.MODEL ?: "Android")
                .header("X-Companion-App-Version", appVersionLabel())
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 ->
                        FetchConfigResult.Failure(
                            OnboardingFailureCode.AUTH_INVALID_CREDENTIALS,
                            "Companion rejected setup token (HTTP ${response.code})",
                        )
                    response.code == 429 ->
                        FetchConfigResult.Failure(
                            OnboardingFailureCode.PAIRING_RATE_LIMITED,
                            "Companion rate limited config request",
                        )
                    !response.isSuccessful ->
                        FetchConfigResult.Failure(
                            OnboardingFailureCode.SERVER_HTTP_ERROR,
                            "Companion returned HTTP ${response.code}",
                        )
                    else -> {
                        val body = response.body.string()
                        if (body.isBlank()) {
                            FetchConfigResult.Failure(
                                OnboardingFailureCode.SERVER_HTTP_ERROR,
                                "Companion returned an empty configuration",
                            )
                        } else {
                            FetchConfigResult.Success(json.decodeFromString<CompanionConfig>(body))
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SafeUrlBlockedException) {
            FetchConfigResult.Failure(OnboardingFailureCode.URL_SSRF_BLOCKED, e.message ?: "URL blocked")
        } catch (e: SocketTimeoutException) {
            FetchConfigResult.Failure(OnboardingFailureCode.URL_TIMEOUT, "Companion request timed out")
        } catch (e: Throwable) {
            FetchConfigResult.Failure(
                e.toOnboardingFailureCode(),
                e.message ?: "Failed to import companion configuration",
            )
        }

    /**
     * Fetches the current Companion user roster for [serverId] / [serverAddress] without
     * persisting any unknown household accounts into Room (preserving Stage 2 isolation).
     */
    suspend fun fetchCompanionUsersForServer(
        endpoint: CompanionEndpoint,
        serverId: String,
        serverAddress: String,
        policy: UrlTargetPolicy = UrlTargetPolicy.LAN_ONLY,
    ): List<CompanionCandidate> =
        withContext(Dispatchers.IO) {
            val config =
                when (val res = fetchConfig(endpoint, policy, timeoutMs = 4_000L)) {
                    is FetchConfigResult.Success -> res.config
                    is FetchConfigResult.Failure -> {
                        Timber.d(
                            "CompanionConfigImporter: live roster fetch failed (%s): %s",
                            res.code,
                            res.message,
                        )
                        return@withContext emptyList()
                    }
                }
            val targetNormalizedId = serverId.trim().takeIf { it.isNotBlank() }?.let(::normalizeJellyfinId)
            val targetNormalizedAddr = normalizeServerAddress(serverAddress).takeIf { it.isNotBlank() }
            val matchingServers =
                config.servers.filter { srv ->
                    val idMatches =
                        targetNormalizedId != null &&
                            (normalizeJellyfinId(srv.id) == targetNormalizedId ||
                                srv.legacyIds.any { normalizeJellyfinId(it) == targetNormalizedId })
                    val addrMatches =
                        targetNormalizedAddr != null &&
                            srv.addresses.any {
                                normalizeServerAddress(it).equals(targetNormalizedAddr, ignoreCase = true)
                            }
                    idMatches || addrMatches
                }
            buildCandidatesForServers(matchingServers)
        }

    private suspend fun importFromEndpointInternal(
        endpoint: CompanionEndpoint,
        mode: ImportMode,
        policy: UrlTargetPolicy,
        selectedUser: SelectedCompanionUser?,
        shareOnly: Boolean,
        handoffDeadlineElapsedMs: Long?,
        beforeCommit: (suspend () -> Boolean)?,
    ): CompanionImportOutcome =
        withContext(Dispatchers.IO) {
            try {
                val defaultFetchMs = if (policy == UrlTargetPolicy.PRIVATE_LAN_ONLY) 2_000L else 10_000L
                val remainingMs = remainingBudgetMs(handoffDeadlineElapsedMs, defaultMs = defaultFetchMs)
                if (remainingMs <= 0L) {
                    return@withContext CompanionImportOutcome.Failed(
                        OnboardingFailureCode.URL_TIMEOUT,
                        "Companion config fetch timed out",
                    )
                }
                val config =
                    when (val res = fetchConfig(endpoint, policy, remainingMs)) {
                        is FetchConfigResult.Success -> res.config
                        is FetchConfigResult.Failure ->
                            return@withContext CompanionImportOutcome.Failed(res.code, res.message)
                    }

                applyConfig(
                    config = config,
                    endpoint = endpoint,
                    mode = mode,
                    policy = policy,
                    selectedUser = selectedUser,
                    shareOnly = shareOnly,
                    handoffDeadlineElapsedMs = handoffDeadlineElapsedMs,
                    beforeCommit = beforeCommit,
                    isLanHandoff = policy == UrlTargetPolicy.PRIVATE_LAN_ONLY,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: SafeUrlBlockedException) {
                CompanionImportOutcome.Failed(OnboardingFailureCode.URL_SSRF_BLOCKED, e.message ?: "URL blocked")
            } catch (e: SocketTimeoutException) {
                CompanionImportOutcome.Failed(OnboardingFailureCode.URL_TIMEOUT, "Companion request timed out")
            } catch (e: Throwable) {
                CompanionImportOutcome.Failed(
                    e.toOnboardingFailureCode(),
                    e.message ?: "Failed to import companion configuration",
                )
            }
        }

    suspend fun importFromEnvelope(
        envelope: CompanionTvPairingEnvelope,
        handoffDeadlineElapsedMs: Long? = null,
        beforeCommit: (suspend () -> Boolean)? = null,
    ): CompanionImportOutcome =
        withContext(Dispatchers.IO) {
            try {
                val endpoint = try {
                    val parsed = CompanionEndpoint.from(envelope, allowLoopbackForTests = allowLoopbackForTests)
                    val isLoopbackHost = parsed.baseUrl.host.lowercase() in setOf("127.0.0.1", "::1", "localhost")
                    if (!allowLoopbackForTests || !isLoopbackHost) {
                        SafeUrlPolicy.resolveLanHost(parsed.baseUrl.host)
                    }
                    parsed
                } catch (e: SafeUrlBlockedException) {
                    return@withContext CompanionImportOutcome.Failed(
                        OnboardingFailureCode.URL_SSRF_BLOCKED,
                        e.message ?: "Companion URL blocked",
                    )
                } catch (e: IllegalArgumentException) {
                    return@withContext CompanionImportOutcome.Failed(
                        OnboardingFailureCode.URL_SSRF_BLOCKED,
                        e.message ?: "Invalid companion URL",
                    )
                }

                val targetServerId = envelope.server_id?.trim()?.takeIf { it.isNotEmpty() }
                val targetUserId = parseJellyfinUuid(envelope.user_id)
                val selectedUser =
                    if (targetServerId != null && targetUserId != null) {
                        SelectedCompanionUser(serverId = targetServerId, userId = targetUserId)
                    } else {
                        null
                    }
                val shareOnly = envelope.config.servers.isEmpty()

                applyConfig(
                    config = envelope.config,
                    endpoint = endpoint,
                    mode = ImportMode.PAIRING,
                    policy = UrlTargetPolicy.PRIVATE_LAN_ONLY,
                    selectedUser = selectedUser,
                    shareOnly = shareOnly,
                    handoffDeadlineElapsedMs = handoffDeadlineElapsedMs,
                    beforeCommit = beforeCommit,
                    isLanHandoff = true,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: SafeUrlBlockedException) {
                CompanionImportOutcome.Failed(OnboardingFailureCode.URL_SSRF_BLOCKED, e.message ?: "URL blocked")
            } catch (e: SocketTimeoutException) {
                CompanionImportOutcome.Failed(OnboardingFailureCode.URL_TIMEOUT, "Pairing handoff timed out")
            } catch (e: Throwable) {
                CompanionImportOutcome.Failed(
                    e.toOnboardingFailureCode(),
                    e.message ?: "Failed to import pairing configuration",
                )
            }
        }

    suspend fun completeUserSelection(
        config: CompanionConfig,
        endpoint: CompanionEndpoint,
        policy: UrlTargetPolicy,
        serverId: String,
        userId: UUID?,
        beforeCommit: (suspend () -> Boolean)? = null,
        username: String? = null,
    ): CompanionImportOutcome =
        applyConfig(
            config = config,
            endpoint = endpoint,
            mode = ImportMode.PAIRING,
            policy = policy,
            selectedUser = SelectedCompanionUser(serverId = serverId, userId = userId, username = username),
            shareOnly = false,
            handoffDeadlineElapsedMs = null,
            beforeCommit = beforeCommit,
            isLanHandoff = false,
        )

    private suspend fun applyConfig(
        config: CompanionConfig,
        endpoint: CompanionEndpoint,
        mode: ImportMode,
        policy: UrlTargetPolicy,
        selectedUser: SelectedCompanionUser?,
        shareOnly: Boolean,
        handoffDeadlineElapsedMs: Long?,
        beforeCommit: (suspend () -> Boolean)?,
        isLanHandoff: Boolean,
    ): CompanionImportOutcome {
        val (baseline, deviceOverrodeSeerrUrl) = computeMergedBaseline(config)
        val validatedShares = validateNetworkShares(config.networkShares)

        return if (mode == ImportMode.BACKGROUND_SYNC) {
            applyBackgroundSync(
                config = config,
                baseline = baseline,
                deviceOverrodeSeerrUrl = deviceOverrodeSeerrUrl,
                validatedShares = validatedShares,
                handoffDeadlineElapsedMs = handoffDeadlineElapsedMs,
            )
        } else {
            applyPairing(
                config = config,
                endpoint = endpoint,
                policy = policy,
                baseline = baseline,
                deviceOverrodeSeerrUrl = deviceOverrodeSeerrUrl,
                validatedShares = validatedShares,
                selectedUser = selectedUser,
                shareOnly = shareOnly,
                handoffDeadlineElapsedMs = handoffDeadlineElapsedMs,
                beforeCommit = beforeCommit,
                isLanHandoff = isLanHandoff,
            )
        }
    }

    private suspend fun applyPairing(
        config: CompanionConfig,
        endpoint: CompanionEndpoint,
        policy: UrlTargetPolicy,
        baseline: Map<String, String>,
        deviceOverrodeSeerrUrl: Boolean,
        validatedShares: List<NetworkShareDto>,
        selectedUser: SelectedCompanionUser?,
        shareOnly: Boolean,
        handoffDeadlineElapsedMs: Long?,
        beforeCommit: (suspend () -> Boolean)?,
        isLanHandoff: Boolean,
    ): CompanionImportOutcome {
        val credentials = CompanionCredentials(url = endpoint.normalizedBaseUrl, token = endpoint.setupToken)
        val changeReason = if (isLanHandoff) SessionChangeReason.COMPANION_HANDOFF else SessionChangeReason.COMPANION_QR

        // Share-only / standalone pairing
        if (shareOnly || config.servers.isEmpty()) {
            if (beforeCommit != null && !beforeCommit.invoke()) {
                return CompanionImportOutcome.Failed(OnboardingFailureCode.PAIRING_CANCELLED, "Pairing cancelled")
            }
            sessionActivationCoordinator.activateStandalone(
                reason = changeReason,
                companionBaseline = baseline,
                deviceOverrodeSeerrUrl = deviceOverrodeSeerrUrl,
                companionCredentials = credentials,
                shares = validatedShares,
            )
            recordSyncTimestampAndSchedulePeriodic()
            launchPostCommitExtras(
                shareIds = validatedShares.map { it.id },
                userMa = emptyMap(),
                activeUserId = null,
                activeUserPlugins = emptyList(),
            )
            return CompanionImportOutcome.Activated(serverUrl = null, standalone = true)
        }

        // Resolve target (server, user) or return NeedsUserSelection
        val (targetServer, targetUser) = resolvePairingTarget(config, selectedUser) ?: run {
            val candidates = buildSelectableCandidates(config)
            if (candidates.size >= 2 && selectedUser == null) {
                return CompanionImportOutcome.NeedsUserSelection(
                    config = config,
                    endpoint = endpoint,
                    policy = policy,
                    candidates = candidates,
                )
            }
            // Fallback to first server with no verified user
            val firstServer = config.servers.first()
            firstServer to firstServer.users.firstOrNull()
        }

        // Probe server.addresses concurrently with 150 ms stagger
        val probeOutcome = probeCompanionServerAddresses(
            companionServer = targetServer,
            handoffDeadlineElapsedMs = handoffDeadlineElapsedMs,
            isV2 = isLanHandoff,
        )
        val probed = when (probeOutcome) {
            is ProbeServerOutcome.Success -> probeOutcome.probed
            is ProbeServerOutcome.Failed ->
                return CompanionImportOutcome.Failed(probeOutcome.code, probeOutcome.message)
        }

        val allNormalizedAddresses = buildList {
            add(probed.addressUrl)
            targetServer.addresses.map(::normalizeServerAddress).filter { it.isNotBlank() }.forEach {
                if (it !in this) add(it)
            }
        }
        val legacyIds = buildList {
            if (targetServer.id != probed.serverId && targetServer.id.isNotBlank()) add(targetServer.id)
            addAll(targetServer.legacyIds.filter { it.isNotBlank() && it != probed.serverId })
        }.distinct()

        suspend fun fallbackToLogin(code: OnboardingFailureCode): CompanionImportOutcome {
            if (beforeCommit != null && !beforeCommit.invoke()) {
                return CompanionImportOutcome.Failed(OnboardingFailureCode.PAIRING_CANCELLED, "Pairing cancelled")
            }
            val canonicalServer = Server(
                id = probed.serverId,
                name = targetServer.name.ifBlank { probed.serverName },
                currentServerAddressId = null,
                currentUserId = null,
            )
            val saved = sessionActivationCoordinator.commitCompanionFallbackMetadata(
                companionCredentials = credentials,
                companionBaseline = baseline,
                shares = validatedShares,
                server = canonicalServer,
                addresses = allNormalizedAddresses,
                legacyServerIds = legacyIds,
                deviceOverrodeSeerrUrl = deviceOverrodeSeerrUrl,
                handoffDeadlineElapsedMs = handoffDeadlineElapsedMs,
            )
            if (!saved) {
                return CompanionImportOutcome.Failed(OnboardingFailureCode.URL_TIMEOUT, "Pairing handoff timed out")
            }
            recordSyncTimestampAndSchedulePeriodic()
            return CompanionImportOutcome.BrokerFallbackToLogin(
                serverId = probed.serverId,
                serverName = canonicalServer.name,
                serverAddress = probed.addressUrl,
                prefilledUsername = targetUser?.username?.takeIf { it.isNotBlank() } ?: targetUser?.name,
                failureCode = code,
            )
        }

        if (targetUser == null) {
            return fallbackToLogin(OnboardingFailureCode.PAIRING_NO_VERIFIED_USER)
        }

        val parsedTargetUserId = parseJellyfinUuid(targetUser.id)
        val serializedUserPrefs = serializeUserPrefs(targetUser.preferences)
        val legacySharedToken = targetUser.accessToken?.takeIf { it.isNotBlank() && !targetUser.needsRelink }

        // Priority 1: Stage 1 / pre-Stage-1 shared access_token
        val activatedUserAndDedication: Pair<User, Boolean> = if (legacySharedToken != null) {
            val existingLocal = findMatchingLocalUser(probed.serverId, legacyIds, targetUser)
            val localToken = existingLocal?.accessToken?.takeIf { it.isNotBlank() }
            val probeTimeoutMs = remainingBudgetMs(handoffDeadlineElapsedMs, defaultMs = 3_000L, reserveMs = 500L)
            if (probeTimeoutMs <= 0L) {
                return CompanionImportOutcome.Failed(OnboardingFailureCode.URL_TIMEOUT, "Pairing handoff timed out")
            }
            val localValid = if (localToken != null) {
                probeLocalUserToken(probed.addressUrl, localToken, probeTimeoutMs) == LocalTokenProbeResult.VALID
            } else {
                false
            }
            if (localValid && existingLocal != null) {
                existingLocal.copy(
                    serverId = probed.serverId,
                    preferences = serializedUserPrefs,
                ) to false
            } else {
                // Validate Companion's shared access_token via GET /Users/Me
                val validatedShared = validateTokenAndResolveUser(
                    baseUrl = probed.addressUrl,
                    token = legacySharedToken,
                    serverId = probed.serverId,
                    fallbackName = targetUser.username.ifBlank { targetUser.name.orEmpty() },
                    serializedPrefs = serializedUserPrefs,
                    timeoutMs = probeTimeoutMs,
                )
                when (validatedShared) {
                    is TokenValidationResult.Valid -> validatedShared.user to false
                    TokenValidationResult.Unauthorized ->
                        return fallbackToLogin(OnboardingFailureCode.PAIRING_NO_VERIFIED_USER)
                    is TokenValidationResult.Failed ->
                        return CompanionImportOutcome.Failed(validatedShared.code, validatedShared.message)
                }
            }
        } else if (targetUser.hasVerifiedToken == true && !targetUser.needsRelink && parsedTargetUserId != null) {
            // Priority 2: Stage 2 Quick Connect broker (dedicated per-device token)
            val brokerBudgetMs =
                if (isLanHandoff) {
                    CompanionQuickConnectBroker.LAN_HANDOFF_BROKER_BUDGET_MS
                } else {
                    CompanionQuickConnectBroker.INTERACTIVE_BROKER_BUDGET_MS
                }
            val brokerResult = quickConnectBroker.authorizeAndMint(
                endpoint = endpoint,
                policy = policy,
                companionServerId = targetServer.id,
                expectedServerId = probed.serverId,
                jellyfinBaseUrl = probed.addressUrl,
                selectedUserId = parsedTargetUserId,
                deadlineElapsedMs = handoffDeadlineElapsedMs,
                maxBrokerBudgetMs = brokerBudgetMs,
            )
            when (brokerResult) {
                is BrokerAuthResult.Authenticated -> {
                    val auth = brokerResult.result
                    val authedUser = User(
                        id = auth.user!!.id,
                        name = auth.user?.name ?: targetUser.username.ifBlank { targetUser.name.orEmpty() },
                        serverId = probed.serverId,
                        accessToken = auth.accessToken,
                        preferences = serializedUserPrefs,
                    )
                    authedUser to true
                }
                is BrokerAuthResult.FallbackToLogin -> return fallbackToLogin(brokerResult.code)
                is BrokerAuthResult.Failed ->
                    return CompanionImportOutcome.Failed(
                        brokerResult.code,
                        "Quick Connect broker failed (${brokerResult.code.wireCode})",
                    )
            }
        } else {
            // Priority 3: Unverified user -> BrokerFallbackToLogin
            return fallbackToLogin(OnboardingFailureCode.PAIRING_NO_VERIFIED_USER)
        }

        val (resolvedUser, isDedicated) = activatedUserAndDedication
        if (beforeCommit != null && !beforeCommit.invoke()) {
            return CompanionImportOutcome.Failed(OnboardingFailureCode.PAIRING_CANCELLED, "Pairing cancelled")
        }

        val activationResult = sessionActivationCoordinator.activateServer(
            serverId = probed.serverId,
            addressUrl = probed.addressUrl,
            reason = changeReason,
            user = resolvedUser,
            serverName = targetServer.name.ifBlank { probed.serverName },
            addresses = allNormalizedAddresses,
            legacyServerIds = legacyIds,
            handoffDeadlineElapsedMs = handoffDeadlineElapsedMs,
            isDedicatedToken = isDedicated,
            overwriteUserPreferences = true,
            companionBaseline = baseline,
            deviceOverrodeSeerrUrl = deviceOverrodeSeerrUrl,
            companionCredentials = credentials,
            shares = validatedShares,
        )

        return when (activationResult) {
            is ActivationResult.Activated -> {
                recordSyncTimestampAndSchedulePeriodic()
                val userMaMap = mutableMapOf<UUID, CompanionMusicAssistant>()
                targetUser.musicAssistant?.let { userMaMap[resolvedUser.id] = it }
                launchPostCommitExtras(
                    shareIds = validatedShares.map { it.id },
                    userMa = userMaMap,
                    activeUserId = resolvedUser.id,
                    activeUserPlugins = targetUser.plugins,
                )
                CompanionImportOutcome.Activated(serverUrl = activationResult.serverAddress, standalone = false)
            }
            is ActivationResult.NeedsAuth -> fallbackToLogin(OnboardingFailureCode.AUTH_INVALID_CREDENTIALS)
            is ActivationResult.Failed ->
                CompanionImportOutcome.Failed(activationResult.code, "Activation failed (${activationResult.code.wireCode})")
        }
    }

    private suspend fun applyBackgroundSync(
        config: CompanionConfig,
        baseline: Map<String, String>,
        deviceOverrodeSeerrUrl: Boolean,
        validatedShares: List<NetworkShareDto>,
        handoffDeadlineElapsedMs: Long?,
    ): CompanionImportOutcome {
        val currentSession = sessionActivationCoordinator.ensureInitialized()
        val expectedEpoch = sessionActivationCoordinator.sessionEpoch.get()
        val localServers = serverDatabase.getAllServersSync()
        val serverUpdates = mutableListOf<BackgroundServerSyncUpdate>()
        val importedUserMa = mutableMapOf<UUID, CompanionMusicAssistant>()
        val importedUserPlugins = mutableMapOf<UUID, List<String>>()

        for (companionServer in config.servers) {
            val matchedLocalServer = matchLocalServerForBackgroundSync(companionServer, localServers) ?: continue
            val normalizedAddresses = companionServer.addresses
                .map(::normalizeServerAddress)
                .filter { it.isNotBlank() }
                .distinct()
            val localAddresses = serverDatabase.getServerAddresses(matchedLocalServer.id)
                .map { normalizeServerAddress(it.address) }
                .filter { it.isNotBlank() }
            val probeBaseUrl = localAddresses.firstOrNull() ?: normalizedAddresses.firstOrNull().orEmpty()
            val legacyIds = buildList {
                if (matchedLocalServer.id.startsWith("srv-")) add(matchedLocalServer.id)
                if (companionServer.id.startsWith("srv-")) add(companionServer.id)
                addAll(companionServer.legacyIds)
            }.distinct()

            var canonicalTargetServerId =
                if (!companionServer.id.startsWith("srv-")) {
                    companionServer.id
                } else {
                    matchedLocalServer.id
                }

            if (currentSession.sessionMode == SessionMode.SERVER_NEEDS_AUTH) {
                serverUpdates += BackgroundServerSyncUpdate(
                    serverId = canonicalTargetServerId,
                    serverName = companionServer.name.ifBlank { matchedLocalServer.name },
                    addressUrl = null,
                    addresses = normalizedAddresses,
                    legacyServerIds = legacyIds.filter { it != canonicalTargetServerId },
                    users = emptyList(),
                )
                continue
            }

            // Matched local users only — never insert new household users absent from Room
            val existingUsers = serverDatabase.getServerWithUsers(matchedLocalServer.id)?.users.orEmpty()
            val userUpdates = mutableListOf<BackgroundUserSyncUpdate>()

            for (cu in companionServer.users) {
                val parsedId = parseJellyfinUuid(cu.id)
                val localUser = existingUsers.firstOrNull { local ->
                    (parsedId != null && local.id == parsedId) ||
                        (cu.username.isNotBlank() && local.name.equals(cu.username, ignoreCase = true))
                } ?: continue

                val serializedPrefs = serializeUserPrefs(cu.preferences)
                cu.musicAssistant?.let { importedUserMa[localUser.id] = it }
                if (cu.plugins.isNotEmpty()) importedUserPlugins[localUser.id] = cu.plugins

                val localToken = localUser.accessToken
                if (localToken.isNullOrBlank() || probeBaseUrl.isBlank()) {
                    userUpdates += BackgroundUserSyncUpdate(
                        user = localUser.copy(
                            serverId = canonicalTargetServerId,
                            accessToken = null,
                            preferences = serializedPrefs,
                        ),
                        clearUserTokenOnAuthFailure = false,
                        overwritePreferences = true,
                    )
                    continue
                }

                val probeTimeoutMs = remainingBudgetMs(handoffDeadlineElapsedMs, defaultMs = 3_000L, reserveMs = 500L)
                val probe = if (probeTimeoutMs > 0L) {
                    probeLocalUserToken(probeBaseUrl, localToken, probeTimeoutMs)
                } else {
                    LocalTokenProbeResult.TRANSIENT_ERROR
                }

                when (probe) {
                    LocalTokenProbeResult.VALID,
                    LocalTokenProbeResult.TRANSIENT_ERROR,
                    -> {
                        userUpdates += BackgroundUserSyncUpdate(
                            user = localUser.copy(
                                serverId = canonicalTargetServerId,
                                accessToken = localToken,
                                preferences = serializedPrefs,
                            ),
                            clearUserTokenOnAuthFailure = false,
                            overwritePreferences = true,
                        )
                    }
                    LocalTokenProbeResult.UNAUTHORIZED -> {
                        // Verify GET /System/Info/Public before trusting 401
                        val verifiedInfo = probeSingleAddress(probeBaseUrl, 2_500L)
                        val infoMatches = when {
                            verifiedInfo == null -> false
                            !matchedLocalServer.id.startsWith("srv-") ->
                                verifiedInfo.serverId.isNotBlank() &&
                                    normalizeJellyfinId(verifiedInfo.serverId) == normalizeJellyfinId(matchedLocalServer.id)
                            !companionServer.id.startsWith("srv-") ->
                                verifiedInfo.serverId.isNotBlank() &&
                                    normalizeJellyfinId(verifiedInfo.serverId) == normalizeJellyfinId(companionServer.id)
                            else ->
                                verifiedInfo.serverId.isNotBlank() &&
                                    verifiedInfo.productName == "Jellyfin Server"
                        }
                        if (!infoMatches) {
                            // Unverified 401 — preserve local token
                            userUpdates += BackgroundUserSyncUpdate(
                                user = localUser.copy(
                                    serverId = canonicalTargetServerId,
                                    accessToken = localToken,
                                    preferences = serializedPrefs,
                                ),
                                clearUserTokenOnAuthFailure = false,
                                overwritePreferences = true,
                            )
                        } else {
                            if (canonicalTargetServerId.startsWith("srv-") && verifiedInfo != null && verifiedInfo.serverId.isNotBlank()) {
                                canonicalTargetServerId = verifiedInfo.serverId
                            }
                            val stage1RepairToken = cu.accessToken?.takeIf { it.isNotBlank() && it != localToken }
                            if (stage1RepairToken != null) {
                                userUpdates += BackgroundUserSyncUpdate(
                                    user = localUser.copy(
                                        serverId = canonicalTargetServerId,
                                        accessToken = stage1RepairToken,
                                        preferences = serializedPrefs,
                                    ),
                                    clearUserTokenOnAuthFailure = false,
                                    overwritePreferences = true,
                                )
                            } else {
                                // Confirmed Stage 2 401 -> clear user token under epoch/token guard
                                userUpdates += BackgroundUserSyncUpdate(
                                    user = localUser.copy(
                                        serverId = canonicalTargetServerId,
                                        accessToken = null,
                                        preferences = serializedPrefs,
                                    ),
                                    clearUserTokenOnAuthFailure = true,
                                    expectedPreviousToken = localToken,
                                    overwritePreferences = true,
                                )
                            }
                        }
                    }
                }
            }

            serverUpdates += BackgroundServerSyncUpdate(
                serverId = canonicalTargetServerId,
                serverName = companionServer.name.ifBlank { matchedLocalServer.name },
                addressUrl = null,
                addresses = normalizedAddresses,
                legacyServerIds = legacyIds.filter { it != canonicalTargetServerId },
                users = userUpdates,
            )
        }

        val commitResult = if (serverUpdates.isNotEmpty()) {
            sessionActivationCoordinator.commitLockedBackgroundSync(
                serverUpdates = serverUpdates,
                expectedSessionEpoch = expectedEpoch,
                companionBaseline = baseline,
                deviceOverrodeSeerrUrl = deviceOverrodeSeerrUrl,
                shares = validatedShares,
                reason = SessionChangeReason.COMPANION_BACKGROUND_SYNC,
            )
        } else {
            sessionActivationCoordinator.applyCompanionBaselineOnly(
                companionBaseline = baseline,
                deviceOverrodeSeerrUrl = deviceOverrodeSeerrUrl,
                shares = validatedShares,
                expectedSessionEpoch = expectedEpoch,
            )
        }

        return when (commitResult) {
            is BackgroundSyncResult.Committed -> {
                appPreferences.commitMainBatch { editor ->
                    editor.putLong(appPreferences.lastCompanionSyncTime.backendName, System.currentTimeMillis())
                }
                val activeUserId = sessionActivationCoordinator.sessionState.value.activeUserId
                launchPostCommitExtras(
                    shareIds = validatedShares.map { it.id },
                    userMa = importedUserMa,
                    activeUserId = activeUserId,
                    activeUserPlugins = activeUserId?.let { importedUserPlugins[it] }.orEmpty(),
                )
                CompanionImportOutcome.Activated(
                    serverUrl = sessionActivationCoordinator.sessionState.value.activeServerAddress,
                    standalone = sessionActivationCoordinator.sessionState.value.sessionMode == SessionMode.STANDALONE_SOURCES,
                )
            }
            BackgroundSyncResult.EpochMismatch ->
                CompanionImportOutcome.Failed(
                    OnboardingFailureCode.PAIRING_SESSION_ACTIVE,
                    "Session changed during background sync; retry needed",
                )
            is BackgroundSyncResult.Failed ->
                CompanionImportOutcome.Failed(commitResult.code, "Background sync commit failed")
        }
    }

    private fun resolvePairingTarget(
        config: CompanionConfig,
        selectedUser: SelectedCompanionUser?,
    ): Pair<CompanionServer, CompanionUser?>? {
        if (selectedUser != null) {
            val srv = config.servers.firstOrNull { s ->
                normalizeJellyfinId(s.id) == normalizeJellyfinId(selectedUser.serverId) ||
                    s.legacyIds.any { normalizeJellyfinId(it) == normalizeJellyfinId(selectedUser.serverId) }
            } ?: return null
            val usr =
                selectedUser.userId?.let { targetId ->
                    srv.users.firstOrNull { u -> parseJellyfinUuid(u.id) == targetId }
                } ?: selectedUser.username?.trim()?.takeIf { it.isNotEmpty() }?.let { targetName ->
                    srv.users.firstOrNull { u ->
                        u.username.equals(targetName, ignoreCase = true) ||
                            (u.name != null && u.name.equals(targetName, ignoreCase = true))
                    }
                }
            return srv to usr
        }
        val candidates = buildSelectableCandidates(config)
        if (candidates.size >= 2) {
            return null
        }
        if (candidates.size == 1) {
            val only = candidates.first()
            val srv = config.servers.first { s -> s.id == only.serverId }
            val usr =
                only.userId?.let { targetId ->
                    srv.users.firstOrNull { u -> parseJellyfinUuid(u.id) == targetId }
                } ?: srv.users.firstOrNull { u ->
                    u.username.equals(only.username, ignoreCase = true) ||
                        (u.name != null && u.name.equals(only.username, ignoreCase = true))
                }
            return srv to usr
        }
        // Single server with a legacy tokenless-id user (pre-Stage-1) or unverified user
        val firstServer = config.servers.firstOrNull() ?: return null
        val firstUser = firstServer.users.firstOrNull { !it.accessToken.isNullOrBlank() && !it.needsRelink }
            ?: firstServer.users.firstOrNull()
        return firstServer to firstUser
    }

    private fun buildSelectableCandidates(config: CompanionConfig): List<CompanionCandidate> =
        buildCandidatesForServers(config.servers)

    private fun buildCandidatesForServers(servers: List<CompanionServer>): List<CompanionCandidate> {
        val out = mutableListOf<CompanionCandidate>()
        val seenKeys = mutableSetOf<String>()
        for (srv in servers) {
            val normServerId = normalizeJellyfinId(srv.id)
            for (u in srv.users) {
                val uid = parseJellyfinUuid(u.id)
                val displayName = u.username.ifBlank { u.name.orEmpty() }.trim()
                if (uid == null && displayName.isBlank()) continue
                val dedupeKey = "$normServerId:${uid?.toString() ?: displayName.lowercase()}"
                if (!seenKeys.add(dedupeKey)) continue
                val hasLegacyToken = !u.accessToken.isNullOrBlank()
                val isVerified = (hasLegacyToken || u.hasVerifiedToken == true) && !u.needsRelink
                out += CompanionCandidate(
                    serverId = srv.id,
                    serverName = srv.name.ifBlank { srv.id },
                    userId = uid,
                    username = displayName.ifBlank { uid.toString() },
                    isVerified = isVerified,
                    needsRelink = u.needsRelink,
                    quickConnectBrokerEnabled = u.quickConnectBrokerEnabled,
                )
            }
        }
        return out.sortedBy { it.requiresCredentialUpdate }
    }

    private fun matchLocalServerForBackgroundSync(
        companionServer: CompanionServer,
        localServers: List<Server>,
    ): Server? {
        val idCandidates = (listOf(companionServer.id) + companionServer.legacyIds)
            .filter { it.isNotBlank() }
            .map(::normalizeJellyfinId)
            .toSet()
        localServers.firstOrNull { normalizeJellyfinId(it.id) in idCandidates }?.let { return it }

        val normalizedIncomingAddresses = companionServer.addresses
            .map { normalizeServerAddress(it).lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
        if (normalizedIncomingAddresses.isEmpty()) return null

        // Match srv-* Room rows sharing normalized address
        localServers.firstOrNull { local ->
            local.id.startsWith("srv-") &&
                serverDatabase.getServerAddresses(local.id).any {
                    normalizeServerAddress(it.address).lowercase() in normalizedIncomingAddresses
                }
        }?.let { return it }

        // Stage 1 Companion serving srv-* against canonical local Room server by normalized address
        if (companionServer.id.startsWith("srv-")) {
            localServers.firstOrNull { local ->
                serverDatabase.getServerAddresses(local.id).any {
                    normalizeServerAddress(it.address).lowercase() in normalizedIncomingAddresses
                }
            }?.let { return it }
        }
        return null
    }

    private fun findMatchingLocalUser(
        canonicalServerId: String,
        legacyIds: List<String>,
        u: CompanionUser,
    ): User? {
        val parsedId = parseJellyfinUuid(u.id)
        if (parsedId != null) {
            serverDatabase.getUser(parsedId)?.let { return it }
        }
        for (sid in (listOf(canonicalServerId) + legacyIds).distinct()) {
            val users = serverDatabase.getServerWithUsers(sid)?.users.orEmpty()
            users.firstOrNull { local ->
                (parsedId != null && local.id == parsedId) ||
                    (u.username.isNotBlank() && local.name.equals(u.username, ignoreCase = true))
            }?.let { return it }
        }
        return null
    }

    private sealed interface ProbeServerOutcome {
        data class Success(val probed: ProbedJellyfinServer) : ProbeServerOutcome
        data class Failed(val code: OnboardingFailureCode, val message: String) : ProbeServerOutcome
    }

    private suspend fun probeCompanionServerAddresses(
        companionServer: CompanionServer,
        handoffDeadlineElapsedMs: Long?,
        isV2: Boolean,
    ): ProbeServerOutcome {
        val normalizedAddresses = companionServer.addresses
            .map(::normalizeServerAddress)
            .filter { it.isNotBlank() }
            .distinct()
        if (normalizedAddresses.isEmpty()) {
            return ProbeServerOutcome.Failed(OnboardingFailureCode.URL_DNS_UNRESOLVED, "Server has no addresses")
        }
        val capMs = minOf(3_000L, remainingBudgetMs(handoffDeadlineElapsedMs, defaultMs = 3_000L))
        if (capMs <= 0L) {
            return ProbeServerOutcome.Failed(OnboardingFailureCode.URL_TIMEOUT, "Address probe timed out")
        }
        val expectedCanonicalId =
            companionServer.id.takeIf { it.isNotBlank() && !it.startsWith("srv-") }
        var sawIdentityMismatch = false
        var lastErrorCode: OnboardingFailureCode? = null

        val winner = CompletableDeferred<ProbedJellyfinServer?>()
        return coroutineScope {
            val jobs = normalizedAddresses.mapIndexed { idx, addr ->
                launch(Dispatchers.IO) {
                    if (idx > 0) delay(150L * idx)
                    if (winner.isCompleted) return@launch
                    val callTimeoutMs = if (isV2) 2_500L else 3_000L
                    val probed = probeSingleAddress(addr, minOf(capMs, callTimeoutMs))
                    if (probed == null) {
                        lastErrorCode = OnboardingFailureCode.URL_DNS_UNRESOLVED
                        return@launch
                    }
                    if (expectedCanonicalId != null &&
                        (probed.serverId.isBlank() ||
                            normalizeJellyfinId(probed.serverId) != normalizeJellyfinId(expectedCanonicalId))
                    ) {
                        sawIdentityMismatch = true
                        return@launch
                    }
                    winner.complete(probed)
                }
            }
            launch {
                jobs.forEach { it.join() }
                winner.complete(null)
            }
            val result = withTimeoutOrNull(capMs) { winner.await() }
            jobs.forEach { it.cancel() }
            if (result != null) {
                return@coroutineScope ProbeServerOutcome.Success(result)
            }
            if (sawIdentityMismatch) {
                return@coroutineScope ProbeServerOutcome.Failed(
                    OnboardingFailureCode.SERVER_IDENTITY_MISMATCH,
                    "Probed server ID did not match expected server",
                )
            }
            return@coroutineScope ProbeServerOutcome.Failed(
                lastErrorCode ?: OnboardingFailureCode.URL_TIMEOUT,
                "Could not reach Jellyfin server",
            )
        }
    }

    private fun probeSingleAddress(address: String, timeoutMs: Long): ProbedJellyfinServer? {
        return try {
            val client = okHttpClient
                .newBuilderWithSsrfGuard(
                    policy = UrlTargetPolicy.PUBLIC_OR_LAN,
                    allowLoopbackForTests = allowLoopbackForTests,
                    maxRedirects = 0,
                )
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(minOf(timeoutMs, 2_000L), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
            val sanitized = address.trimEnd('/')
            val request = Request.Builder()
                .url("$sanitized/System/Info/Public")
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code !in 200..299) return null
                val body = response.body.string().trim()
                if (!body.startsWith("{")) return null
                val obj = JSONObject(body)
                val id = obj.optString("Id", "").trim()
                val name = obj.optString("ServerName", "").trim()
                val productName = obj.optString("ProductName", "").trim()
                if (id.isBlank()) return null
                ProbedJellyfinServer(
                    serverId = id,
                    serverName = name.ifBlank { "Jellyfin" },
                    addressUrl = normalizeServerAddress(address),
                    productName = productName,
                )
            }
        } catch (t: Throwable) {
            Timber.d(t, "CompanionConfigImporter: probe failed for %s", address)
            null
        }
    }

    private sealed interface TokenValidationResult {
        data class Valid(val user: User) : TokenValidationResult
        data object Unauthorized : TokenValidationResult
        data class Failed(val code: OnboardingFailureCode, val message: String) : TokenValidationResult
    }

    private suspend fun validateTokenAndResolveUser(
        baseUrl: String,
        token: String,
        serverId: String,
        fallbackName: String,
        serializedPrefs: String?,
        timeoutMs: Long,
    ): TokenValidationResult {
        val probe = probeLocalUserToken(baseUrl, token, timeoutMs)
        if (probe == LocalTokenProbeResult.UNAUTHORIZED) {
            return TokenValidationResult.Unauthorized
        }
        return try {
            val api = jellyfinApi.createApi(baseUrl = baseUrl, accessToken = token, followRedirects = false)
            val userInfo = api.userApi.getCurrentUser().content
            TokenValidationResult.Valid(
                User(
                    id = userInfo.id,
                    name = userInfo.name ?: fallbackName,
                    serverId = serverId,
                    accessToken = token,
                    preferences = serializedPrefs,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: org.jellyfin.sdk.api.client.exception.InvalidStatusException) {
            if (e.status == 401 || e.status == 403) {
                TokenValidationResult.Unauthorized
            } else {
                TokenValidationResult.Failed(e.toOnboardingFailureCode(), e.message ?: "Auth probe failed")
            }
        } catch (e: Throwable) {
            TokenValidationResult.Failed(e.toOnboardingFailureCode(), e.message ?: "Auth probe failed")
        }
    }

    internal fun probeLocalUserToken(
        baseUrl: String,
        accessToken: String,
        timeoutMs: Long = 3_000L,
    ): LocalTokenProbeResult {
        return try {
            val client = okHttpClient
                .newBuilderWithSsrfGuard(
                    policy = UrlTargetPolicy.PUBLIC_OR_LAN,
                    allowLoopbackForTests = allowLoopbackForTests,
                    maxRedirects = 0,
                )
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(minOf(timeoutMs, 2_000L), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
            val sanitized = baseUrl.trimEnd('/')
            val request = Request.Builder()
                .url("$sanitized/Users/Me")
                .header("Accept", "application/json")
                .header("Authorization", """MediaBrowser Token="$accessToken"""")
                .header("X-Emby-Token", accessToken)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    in 200..299 -> LocalTokenProbeResult.VALID
                    401 -> LocalTokenProbeResult.UNAUTHORIZED
                    else -> LocalTokenProbeResult.TRANSIENT_ERROR
                }
            }
        } catch (_: IOException) {
            LocalTokenProbeResult.TRANSIENT_ERROR
        } catch (_: Throwable) {
            LocalTokenProbeResult.TRANSIENT_ERROR
        }
    }

    /**
     * Merges `config.preferences` (`globalPreferences`) with `config.devicePreferences[deviceId]`
     * enforcing Predicate 3 of Secret Target-Binding Predicates and returns `(baseline, deviceOverrodeSeerrUrl)`.
     */
    internal fun computeMergedBaseline(config: CompanionConfig): Pair<Map<String, String>, Boolean> {
        val globalPrefs = config.preferences
        val myDeviceId = DeviceIdentity.deviceId(context)
        val devicePrefs = config.devicePreferences[myDeviceId].orEmpty()

        val globalSeerrUrl = normalizeSeerrUrl(globalPrefs[KEY_SEERR_URL])
        val deviceSeerrUrl = normalizeSeerrUrl(devicePrefs[KEY_SEERR_URL])
        val deviceOverrodeSeerrUrl = deviceSeerrUrl != null && deviceSeerrUrl != globalSeerrUrl

        val merged = linkedMapOf<String, String>()
        for ((k, v) in globalPrefs) {
            if (isSyncableCompanionPreferenceKey(k) && v != null) {
                merged[k] = v
            }
        }
        // Predicate 3: if device overrides pref_seerr_url without its own pref_seerr_api_key, drop global key
        if (deviceOverrodeSeerrUrl && devicePrefs[KEY_SEERR_API_KEY].isNullOrBlank()) {
            merged.remove(KEY_SEERR_API_KEY)
        }
        for ((k, v) in devicePrefs) {
            if (!isSyncableCompanionPreferenceKey(k)) continue
            if (v == null) {
                merged.remove(k)
            } else {
                merged[k] = v
            }
        }
        return merged to deviceOverrodeSeerrUrl
    }

    private fun validateNetworkShares(shares: List<CompanionNetworkShare>): List<NetworkShareDto> {
        val out = mutableListOf<NetworkShareDto>()
        for (share in shares) {
            val target: SmbConnectionTarget? =
                if (share.protocol.equals("smb", ignoreCase = true)) {
                    SmbPathNormalizer.normalizeConnectionTarget(share.host, share.shareName)
                } else {
                    null
                }
            val normalizedHost = (target?.host ?: share.host).trim()
            val normalizedShareName = (target?.shareName ?: share.shareName).trim()
            if (normalizedHost.isBlank()) continue
            if (!allowLoopbackForTests) {
                try {
                    SafeUrlPolicy.resolveLanHost(normalizedHost)
                } catch (e: SafeUrlBlockedException) {
                    Timber.w(e, "CompanionConfigImporter: skipping unsafe share host %s", normalizedHost)
                    continue
                }
            }
            val existingShare = serverDatabase.getNetworkShare(share.id)
            out += NetworkShareDto(
                id = share.id,
                protocol = share.protocol,
                host = normalizedHost,
                shareName = normalizedShareName,
                path = share.path?.takeIf { it.isNotBlank() } ?: "",
                displayName = share.displayName,
                username = share.username,
                password = share.password,
                domain = share.domain,
                addedAtEpochMs = existingShare?.addedAtEpochMs ?: share.addedAtEpochMs ?: System.currentTimeMillis(),
                lastScannedAtEpochMs = existingShare?.lastScannedAtEpochMs,
            )
        }
        return out
    }

    private fun recordSyncTimestampAndSchedulePeriodic() {
        appPreferences.commitMainBatch { editor ->
            editor.putLong(appPreferences.lastCompanionSyncTime.backendName, System.currentTimeMillis())
        }
        runCatching {
            val wm = workManager.get()
            wm.cancelUniqueWork("CompanionSync")
            val periodicRequest =
                PeriodicWorkRequestBuilder<CompanionSyncWorker>(12, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .build()
            wm.enqueueUniquePeriodicWork(
                "companion_sync",
                ExistingPeriodicWorkPolicy.UPDATE,
                periodicRequest,
            )
        }.onFailure {
            Timber.w(it, "CompanionConfigImporter: WorkManager scheduling failed")
        }
    }

    private fun launchPostCommitExtras(
        shareIds: List<String>,
        userMa: Map<UUID, CompanionMusicAssistant>,
        activeUserId: UUID?,
        activeUserPlugins: List<String>,
    ) {
        postCommitExtrasScope.launch {
            if (isAppLocked()) return@launch
            for ((userId, ma) in userMa) {
                if (isAppLocked()) return@launch
                runCatching { extrasApplier.loginMusicAssistant(userId.toString(), ma) }
                    .onFailure { Timber.w(it, "CompanionConfigImporter: MA login failed for %s", userId) }
            }
            if (activeUserId != null && activeUserPlugins.isNotEmpty() && !isAppLocked()) {
                runCatching { extrasApplier.installPlugins(activeUserId.toString(), activeUserPlugins) }
                    .onFailure { Timber.w(it, "CompanionConfigImporter: plugin install failed") }
            }
            for (shareId in shareIds) {
                if (isAppLocked()) return@launch
                runCatching { networkMediaRepository.triggerBackgroundScanAndEnrich(shareId) }
                    .onFailure { Timber.w(it, "CompanionConfigImporter: share scan trigger failed") }
            }
        }
    }

    private fun serializeUserPrefs(prefs: Map<String, String?>): String? {
        val nonNull = prefs.filterValues { it != null }.mapValues { it.value!! }
        if (nonNull.isEmpty()) return null
        return runCatching { json.encodeToString(nonNull) }.getOrNull()
    }

    private fun remainingBudgetMs(
        deadlineElapsedMs: Long?,
        defaultMs: Long,
        reserveMs: Long = 0L,
    ): Long {
        if (deadlineElapsedMs == null) return defaultMs
        return (deadlineElapsedMs - SystemClock.elapsedRealtime() - reserveMs).coerceAtMost(defaultMs)
    }

    private fun normalizeServerAddress(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    private fun normalizeSeerrUrl(raw: String?): String? =
        raw?.trim()?.trimEnd('/')?.lowercase()?.takeIf { it.isNotEmpty() }

    private fun appVersionLabel(): String =
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "unknown"
        }.getOrDefault("unknown")

    companion object {
        const val CAPABILITY_QC_BROKER_V1 = "qc_broker_v1"
        private const val KEY_SEERR_URL = "pref_seerr_url"
        private const val KEY_SEERR_API_KEY = "pref_seerr_api_key"
    }
}
