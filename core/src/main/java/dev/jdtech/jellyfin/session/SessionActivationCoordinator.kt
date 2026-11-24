package dev.jdtech.jellyfin.session

import android.content.Context
import android.database.sqlite.SQLiteException
import android.os.SystemClock
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.companion.CompanionLiveSyncClient
import dev.jdtech.jellyfin.core.util.CompanionLogUploader
import dev.jdtech.jellyfin.core.util.XrPlayerSessionCoordinator
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.database.UserMigrationUpdate
import dev.jdtech.jellyfin.downloads.ActiveDownloadRegistry
import dev.jdtech.jellyfin.downloads.DownloadStagingPaths
import dev.jdtech.jellyfin.downloads.DownloadStorageManager
import dev.jdtech.jellyfin.models.NetworkShareDto
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.normalizeJellyfinId
import dev.jdtech.jellyfin.offline.ServerConnectionMonitor
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.CompanionPreferenceStaging
import dev.jdtech.jellyfin.settings.domain.isSyncableCompanionPreferenceKey
import dev.jdtech.jellyfin.utils.Downloader
import dev.jdtech.jellyfin.watchnext.WatchNextScheduler
import dev.jdtech.jellyfin.watchnext.WatchNextSync
import dev.jdtech.jellyfin.work.CompanionSyncWorker
import dev.jdtech.jellyfin.work.CompanionUserExtrasApplier
import dev.jdtech.jellyfin.work.DownloadIntegrityWorker
import dev.jdtech.jellyfin.work.MusicAssistantUserScope
import dev.jdtech.jellyfin.work.ResumableDownloadWorker
import dev.jdtech.jellyfin.work.SyncWorker
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.extensions.sessionApi
import timber.log.Timber

/**
 * Sole Room-backed owner of active session state, `JellyfinApi` mutations,
 * preference baseline/override reconciliation (Step `A1f`), legacy `srv-*`
 * migration (Step `A1`), background-sync batch commits, and App Lock lifecycle
 * reactions (Steps `A2`–`A4`).
 */
@Singleton
class SessionActivationCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
    private val jellyfinApi: JellyfinApi,
    private val activeSessionBus: ActiveSessionBus,
    private val extrasApplier: dagger.Lazy<CompanionUserExtrasApplier>,
    private val serverConnectionMonitor: dagger.Lazy<ServerConnectionMonitor>,
    private val downloader: dagger.Lazy<Downloader>,
    private val downloadStorageManager: dagger.Lazy<DownloadStorageManager>,
    private val activeDownloadRegistry: ActiveDownloadRegistry,
    private val watchNextScheduler: dagger.Lazy<WatchNextScheduler>,
    private val appLockObserver: AppLockObserver,
    private val workManager: dagger.Lazy<WorkManager>,
    private val xrPlayerSessionCoordinator: dagger.Lazy<XrPlayerSessionCoordinator>,
    private val companionLiveSyncClient: dagger.Lazy<CompanionLiveSyncClient>,
) {
    private val isMainProcess: Boolean = appPreferences.isMainProcess
    private val coordinatorInstanceId: String = UUID.randomUUID().toString()
    private val mutex = Mutex()

    val isActivating: AtomicBoolean = AtomicBoolean(false)
    val sessionEpoch: AtomicLong = AtomicLong(0L)
    val activationEpoch: AtomicLong = AtomicLong(0L)
    val connectionEpoch: AtomicLong = AtomicLong(0L)

    private val _sessionState = MutableStateFlow(
        SessionState(
            isLoading = true,
            coordinatorInstanceId = coordinatorInstanceId,
        ),
    )
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _sessionMode = MutableStateFlow(SessionMode.UNCONFIGURED)
    val sessionMode: StateFlow<SessionMode> = _sessionMode.asStateFlow()

    private val _activeSessionState = MutableStateFlow(ActiveSessionSnapshot(isLoading = true))
    val activeSessionState: StateFlow<ActiveSessionSnapshot> = _activeSessionState.asStateFlow()

    private val initialized = AtomicBoolean(false)
    private val unlockIntegrityCheckedOnce = AtomicBoolean(false)

    // For !isMainProcess (:xrplayer): authoritative values pushed by main process over Binder
    // so ensureInitialized() never clobbers synced epoch or roamed base URL.
    @Volatile
    private var authoritativeXrEpoch: Long? = null

    @Volatile
    private var authoritativeXrBaseUrl: String? = null

    internal val pendingServerDeleteJobs = ConcurrentHashMap<String, Job>()

    private data class ConnectionIdentity(
        val mode: SessionMode,
        val serverId: String?,
        val serverAddress: String?,
        val userId: UUID?,
        val accessToken: String?,
    )

    @Volatile
    private var lastConnectionIdentity: ConnectionIdentity? = null

    val postCommitScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
            Timber.w(t, "SessionActivationCoordinator: post-commit side effect failed")
        },
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        if (isMainProcess) {
            postCommitScope.launch {
                runCatching {
                    database.observeNetworkShareCount().collect { count ->
                        val hasShares = count > 0
                        if (!initialized.get()) return@collect
                        mutex.withLock {
                            val current = _sessionState.value
                            if (current.hasNetworkShares == hasShares) return@withLock
                            val nextMode = deriveSessionMode(
                                hasCurrentServer = current.activeServerId != null,
                                hasCurrentServerAddress = !current.activeServerAddress.isNullOrBlank(),
                                hasCurrentUserWithToken = current.activeUserId != null && current.sessionMode == SessionMode.SERVER_AUTHENTICATED,
                                hasNetworkShares = hasShares,
                                standaloneModeChosen = current.standaloneModeChosen,
                            )
                            val updated = current.copy(
                                hasNetworkShares = hasShares,
                                sessionMode = nextMode,
                            )
                            publishState(updated)
                            if (current.sessionMode != nextMode && !isAppLocked()) {
                                dispatchRefreshMusicAssistant(updated)
                            }
                        }
                    }
                }.onFailure { if (it !is CancellationException) Timber.w(it, "observeNetworkShareCount failed") }
            }
            postCommitScope.launch {
                runCatching {
                    database.observeServerIds().collect { ids ->
                        val idSet = ids.toSet()
                        val updated = _sessionState.updateAndGet { current ->
                            current.copy(
                                savedServerIds = idSet,
                                hasServers = idSet.isNotEmpty(),
                            )
                        }
                        if (!updated.isLoading) {
                            _activeSessionState.value = updated.toActiveSessionSnapshot()
                        }
                    }
                }.onFailure { if (it !is CancellationException) Timber.w(it, "observeServerIds failed") }
            }
            postCommitScope.launch {
                var previousLockState: AppLockManager.LockState? = null
                appLockObserver.lockState.collect { state ->
                    val prev = previousLockState
                    previousLockState = state
                    when (state) {
                        AppLockManager.LockState.LOCKED -> {
                            onAppLocked(isColdStart = prev == null)
                        }
                        AppLockManager.LockState.UNLOCKED -> {
                            if (prev == AppLockManager.LockState.LOCKED) {
                                onAppUnlocked()
                            }
                        }
                        AppLockManager.LockState.DISABLED -> Unit
                    }
                }
            }
            postCommitScope.launch {
                appLockObserver.enrollmentVersion.drop(1).collect {
                    onAppLockEnrollmentChanged()
                }
            }
        }
    }

    private fun isAppLocked(): Boolean =
        appLockObserver.lockState.value == AppLockManager.LockState.LOCKED

    private fun requireMainProcess(caller: String) {
        if (!isMainProcess) {
            throw IllegalStateException("SessionActivationCoordinator.$caller is main-process-only")
        }
    }

    private fun SessionState.toActiveSessionSnapshot(): ActiveSessionSnapshot =
        ActiveSessionSnapshot(
            isLoading = isLoading,
            isAuthenticated = sessionMode == SessionMode.SERVER_AUTHENTICATED,
            serverId = activeServerId,
            userId = activeUserId,
            sessionEpoch = sessionEpoch,
            serverAddress = activeServerAddress,
        )

    private fun SessionState.toMusicAssistantScope(): MusicAssistantUserScope =
        when (sessionMode) {
            SessionMode.SERVER_AUTHENTICATED ->
                activeUserId?.let { MusicAssistantUserScope.Authenticated(it.toString()) }
                    ?: MusicAssistantUserScope.Disabled
            SessionMode.STANDALONE_SOURCES -> MusicAssistantUserScope.Standalone
            SessionMode.UNCONFIGURED, SessionMode.SERVER_NEEDS_AUTH -> MusicAssistantUserScope.Disabled
        }

    private fun publishState(state: SessionState) {
        _sessionState.value = state
        _sessionMode.value = state.sessionMode
        _activeSessionState.value = state.toActiveSessionSnapshot()
    }

    private fun <T> MutableStateFlow<T>.updateAndGet(transform: (T) -> T): T {
        while (true) {
            val prev = value
            val next = transform(prev)
            if (compareAndSet(prev, next)) return next
        }
    }

    /**
     * Initializes [sessionState] from Room + [AppPreferences]. Idempotent.
     * In `:xrplayer` (`!isMainProcess`), performs a strict Room-only read merging any
     * [syncAuthoritativeXrSession] values with zero main-process side effects.
     */
    suspend fun ensureInitialized(): SessionState =
        withContext(Dispatchers.IO) {
            if (initialized.get() && !_sessionState.value.isLoading) {
                return@withContext _sessionState.value
            }
            mutex.withLock {
                if (initialized.get() && !_sessionState.value.isLoading) {
                    return@withContext _sessionState.value
                }
                if (!isMainProcess) {
                    val state = loadReadOnlyXrSessionState()
                    publishState(state)
                    initialized.set(true)
                    return@withContext state
                }

                // Main process startup cleanup + migration
                sweepTempPlaybackCaches()

                var currentServerId = appPreferences.getValue(appPreferences.currentServer)
                var server = currentServerId?.let { database.get(it) }
                if (currentServerId != null && server == null) {
                    val staleId = currentServerId
                    val pendingMigrations = decodePendingServerIdMigrations().toMutableMap()
                    val remappedId = pendingMigrations[staleId]?.takeIf {
                        it.isNotBlank() && it != staleId
                    }
                    val remappedServer = remappedId?.let { database.get(it) }
                    if (remappedServer != null) {
                        pendingMigrations.remove(staleId)
                        appPreferences.withAppLockPolicyLock {
                            appPreferences.commitMainBatch { editor ->
                                editor.putString(appPreferences.currentServer.backendName, remappedServer.id)
                                editor.putString(
                                    appPreferences.pendingServerIdMigrationsJson.backendName,
                                    encodeStringMap(pendingMigrations),
                                )
                            }
                        }
                        Timber.i("Recovered interrupted server migration %s -> %s", staleId, remappedServer.id)
                        currentServerId = remappedServer.id
                        server = remappedServer
                    } else {
                        val hadPendingEntry = pendingMigrations.remove(staleId) != null
                        appPreferences.withAppLockPolicyLock {
                            appPreferences.commitMainBatch { editor ->
                                editor.remove(appPreferences.currentServer.backendName)
                                if (hadPendingEntry) {
                                    editor.putString(
                                        appPreferences.pendingServerIdMigrationsJson.backendName,
                                        encodeStringMap(pendingMigrations),
                                    )
                                }
                            }
                        }
                        currentServerId = null
                    }
                }

                var standaloneChosen = appPreferences.getValue(appPreferences.standaloneModeChosen)
                if (server == null && !standaloneChosen && appPreferences.hasLegacyOnboardingCompleted()) {
                    standaloneChosen = true
                    appPreferences.commitMainBatch { editor ->
                        editor.putBoolean(appPreferences.standaloneModeChosen.backendName, true)
                    }
                }

                var serverAddress: ServerAddress? = null
                var activeUser: User? = null
                if (server != null) {
                    val withAddrAndUser = database.getServerWithAddressAndUser(server.id)
                    serverAddress = withAddrAndUser?.address
                    if (serverAddress == null) {
                        val fallbackAddr = database.getServerCurrentAddress(server.id)
                        if (fallbackAddr != null) {
                            database.updateServerCurrentAddress(server.id, fallbackAddr.id)
                            serverAddress = fallbackAddr
                        }
                    }
                    activeUser = withAddrAndUser?.user
                }

                val hasValidToken = !activeUser?.accessToken.isNullOrBlank()
                val savedIds = database.getAllServersSync().map { it.id }.toSet()
                val shareCount = database.getNetworkShares().size
                val mode = deriveSessionMode(
                    hasCurrentServer = server != null,
                    hasCurrentServerAddress = !serverAddress?.address.isNullOrBlank(),
                    hasCurrentUserWithToken = hasValidToken,
                    hasNetworkShares = shareCount > 0,
                    standaloneModeChosen = standaloneChosen,
                )

                val effectiveUser = if (mode == SessionMode.SERVER_AUTHENTICATED) activeUser else null
                val effectiveBaseUrl = if (server != null) serverAddress?.address else null
                val effectiveToken = if (mode == SessionMode.SERVER_AUTHENTICATED) effectiveUser?.accessToken else null

                applyJellyfinApiState(
                    mode = mode,
                    baseUrl = effectiveBaseUrl,
                    accessToken = effectiveToken,
                    userId = effectiveUser?.id,
                )

                val epoch = sessionEpoch.incrementAndGet()
                val connEpoch = connectionEpoch.incrementAndGet()
                lastConnectionIdentity = ConnectionIdentity(
                    mode = mode,
                    serverId = server?.id,
                    serverAddress = effectiveBaseUrl,
                    userId = effectiveUser?.id,
                    accessToken = effectiveToken,
                )

                val state = SessionState(
                    isLoading = false,
                    coordinatorInstanceId = coordinatorInstanceId,
                    sessionMode = mode,
                    sessionEpoch = epoch,
                    activationEpoch = activationEpoch.get(),
                    connectionEpoch = connEpoch,
                    activeServerId = server?.id,
                    activeServerName = server?.name,
                    activeServerAddress = effectiveBaseUrl,
                    activeUserId = effectiveUser?.id,
                    activeUser = effectiveUser?.copy(accessToken = null),
                    hasServers = savedIds.isNotEmpty(),
                    savedServerIds = savedIds,
                    hasNetworkShares = shareCount > 0,
                    standaloneModeChosen = standaloneChosen,
                )
                publishState(state)
                initialized.set(true)

                Timber.i(
                    "SessionActivationCoordinator initialized: mode=%s server=%s epoch=%d",
                    mode,
                    server?.id,
                    epoch,
                )

                if (isAppLocked() && appPreferences.getValue(appPreferences.contentEncryptionEnabled)) {
                    postCommitScope.launch {
                        runLockedEncryptedDownloadSweep()
                    }
                }
                if (!isAppLocked()) {
                    dispatchRefreshMusicAssistant(state)
                }
                xrPlayerSessionCoordinator.get().notifyMainSessionUpdated(epoch, effectiveBaseUrl)
                state
            }
        }

    private fun loadReadOnlyXrSessionState(): SessionState {
        val currentServerId = appPreferences.getValue(appPreferences.currentServer)
        val server = currentServerId?.let { database.get(it) }
        val withAddrAndUser = server?.let { database.getServerWithAddressAndUser(it.id) }
        val serverAddress = withAddrAndUser?.address
            ?: server?.let { database.getServerCurrentAddress(it.id) }
        val activeUser = withAddrAndUser?.user
        val hasValidToken = !activeUser?.accessToken.isNullOrBlank()
        val savedIds = database.getAllServersSync().map { it.id }.toSet()
        val shareCount = database.getNetworkShares().size
        val standaloneChosen = appPreferences.getValue(appPreferences.standaloneModeChosen)

        val resolvedBaseUrl = authoritativeXrBaseUrl ?: serverAddress?.address
        val mode = deriveSessionMode(
            hasCurrentServer = server != null,
            hasCurrentServerAddress = !resolvedBaseUrl.isNullOrBlank(),
            hasCurrentUserWithToken = hasValidToken,
            hasNetworkShares = shareCount > 0,
            standaloneModeChosen = standaloneChosen,
        )
        val effectiveUser = if (mode == SessionMode.SERVER_AUTHENTICATED) activeUser else null
        val effectiveToken = if (mode == SessionMode.SERVER_AUTHENTICATED) effectiveUser?.accessToken else null
        applyJellyfinApiState(
            mode = mode,
            baseUrl = resolvedBaseUrl,
            accessToken = effectiveToken,
            userId = effectiveUser?.id,
        )
        val epoch = authoritativeXrEpoch ?: sessionEpoch.get()
        sessionEpoch.set(epoch)
        return SessionState(
            isLoading = false,
            coordinatorInstanceId = coordinatorInstanceId,
            sessionMode = mode,
            sessionEpoch = epoch,
            activationEpoch = activationEpoch.get(),
            connectionEpoch = connectionEpoch.get(),
            activeServerId = server?.id,
            activeServerName = server?.name,
            activeServerAddress = resolvedBaseUrl,
            activeUserId = effectiveUser?.id,
            activeUser = effectiveUser?.copy(accessToken = null),
            hasServers = savedIds.isNotEmpty(),
            savedServerIds = savedIds,
            hasNetworkShares = shareCount > 0,
            standaloneModeChosen = standaloneChosen,
        )
    }

    /**
     * `:xrplayer`-only hook called on `registerSession` and `onMainSessionUpdated` Binder IPC.
     * Atomically updates [sessionEpoch], [SessionState.activeServerAddress], [connectionEpoch],
     * and [jellyfinApi] baseUrl under [mutex].
     */
    suspend fun syncAuthoritativeXrSession(
        newSessionEpoch: Long,
        newBaseUrl: String? = null,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            authoritativeXrEpoch = newSessionEpoch
            if (!newBaseUrl.isNullOrBlank()) {
                authoritativeXrBaseUrl = newBaseUrl
            }
            sessionEpoch.set(newSessionEpoch)
            val current = _sessionState.value
            val baseUrlChanged = !newBaseUrl.isNullOrBlank() && newBaseUrl != current.activeServerAddress
            if (baseUrlChanged) {
                jellyfinApi.api.update(baseUrl = newBaseUrl)
            }
            val nextConnEpoch = if (baseUrlChanged) connectionEpoch.incrementAndGet() else current.connectionEpoch
            if (!current.isLoading) {
                val updated = current.copy(
                    sessionEpoch = newSessionEpoch,
                    activeServerAddress = newBaseUrl?.takeIf { it.isNotBlank() } ?: current.activeServerAddress,
                    connectionEpoch = nextConnEpoch,
                )
                publishState(updated)
            }
        }
    }

    suspend fun awaitPendingServerDeleteCleanup(
        serverId: String,
        timeoutMs: Long = SERVER_DELETE_CLEANUP_WAIT_TIMEOUT_MS,
    ) {
        pendingServerDeleteJobs[serverId]?.let { job ->
            val completed =
                try {
                    withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
                        job.join()
                        true
                    } ?: false
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Timber.w(t, "Pending server delete cleanup join failed for %s", serverId)
                    true
                }
            if (!completed) {
                Timber.w(
                    "Timed out after %d ms waiting for pending server delete cleanup for %s",
                    timeoutMs,
                    serverId,
                )
            }
        }
    }

    suspend fun activateServer(
        serverId: String,
        addressUrl: String,
        reason: SessionChangeReason,
        user: User,
        serverName: String? = null,
        addresses: List<String> = emptyList(),
        legacyServerIds: List<String> = emptyList(),
        handoffDeadlineElapsedMs: Long? = null,
        isDedicatedToken: Boolean = reason in setOf(
            SessionChangeReason.MANUAL_LOGIN,
            SessionChangeReason.QUICK_CONNECT,
            SessionChangeReason.LAN_SETUP_PAGE,
        ),
        overwriteUserPreferences: Boolean = false,
        companionBaseline: Map<String, String>? = null,
        deviceOverrodeSeerrUrl: Boolean? = null,
        companionCredentials: CompanionCredentials? = null,
        shares: List<NetworkShareDto> = emptyList(),
    ): ActivationResult {
        requireMainProcess("activateServer")
        awaitPendingServerDeleteCleanup(serverId)
        val normalizedTargetAddress = normalizeTrailingSlash(addressUrl)
        val token = user.accessToken?.takeIf { it.isNotBlank() }
        if (token == null) {
            return ActivationResult.NeedsAuth(
                serverId = serverId,
                serverName = serverName ?: database.get(serverId)?.name ?: serverId,
                serverAddress = normalizedTargetAddress,
            )
        }

        return try {
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    if (handoffDeadlineElapsedMs != null &&
                        handoffDeadlineElapsedMs - SystemClock.elapsedRealtime() < MIN_COMMIT_REMAINING_MS
                    ) {
                        return@withLock ActivationResult.Failed(OnboardingFailureCode.URL_TIMEOUT)
                    }
                    withContext(NonCancellable) {
                        isActivating.set(true)
                        try {
                            val previousState = _sessionState.value
                            val previousServerId = previousState.activeServerId
                            val previousUserId = previousState.activeUserId

                            val allAddresses = buildList {
                                add(normalizedTargetAddress)
                                addresses.forEach { addr ->
                                    val n = normalizeTrailingSlash(addr)
                                    if (n.isNotBlank() && n !in this) add(n)
                                }
                            }
                            val shellIds = collectShellIds(
                                canonicalServerId = serverId,
                                explicitLegacyIds = legacyServerIds,
                                candidateAddresses = allAddresses,
                                userIds = listOf(user.id),
                            )
                            val existingCanonical = database.get(serverId)
                            val canonicalServer = Server(
                                id = serverId,
                                name = serverName?.takeIf { it.isNotBlank() }
                                    ?: existingCanonical?.name
                                    ?: shellIds.firstNotNullOfOrNull { database.get(it)?.name }
                                    ?: serverId,
                                currentServerAddressId = existingCanonical?.currentServerAddressId,
                                currentUserId = user.id,
                            )

                            val revokedDigestsMap = decodeRevokedDownloadTokenDigests().toMutableMap()
                            val candidateRevokedDigests = collectRevokedDigestsForScopes(
                                revokedDigestsMap,
                                shellIds + serverId,
                                setOf(user.id),
                            )

                            stagePendingServerIdMigrationsLocked(shellIds, serverId)

                            val mergedUserPairs = database.migrateLegacyServer(
                                shellIds = shellIds,
                                canonicalServer = canonicalServer,
                                targetAddress = normalizedTargetAddress,
                                addresses = allAddresses,
                                users = listOf(
                                    UserMigrationUpdate(
                                        user = user.copy(serverId = serverId),
                                        clearToken = false,
                                        overwritePreferences = overwriteUserPreferences,
                                    ),
                                ),
                                revokedDownloadTokenDigests = candidateRevokedDigests,
                            )

                            // Ensure currentUserId points to the newly activated user
                            database.updateServerCurrentUser(serverId, user.id)
                            val savedAddresses = database.getServerWithAddresses(serverId)?.addresses.orEmpty()
                            val matchedAddress = savedAddresses.firstOrNull {
                                normalizeTrailingSlash(it.address).equals(normalizedTargetAddress, ignoreCase = true)
                            } ?: savedAddresses.firstOrNull()
                            if (matchedAddress != null) {
                                database.updateServerCurrentAddress(serverId, matchedAddress.id)
                            }
                            val effectiveAddress = matchedAddress?.address ?: normalizedTargetAddress

                            // Upsert validated LAN shares if provided
                            upsertValidatedShares(shares)

                            // Prune consumed revoked digests and update dedicated token digests
                            val migratedLegacyUserIds = mergedUserPairs.map { it.first }.toSet()
                            pruneRevokedDigests(revokedDigestsMap, shellIds + serverId, migratedLegacyUserIds + user.id)
                            val dedicatedDigests = updateDedicatedTokenDigests(
                                newToken = token,
                                isDedicatedToken = isDedicatedToken,
                            )

                            val persistedUser = database.getUser(user.id) ?: user.copy(serverId = serverId)

                            // Step A1f: atomic preference reconciliation + session prefs commit
                            reconcileAndCommitPreferencesLocked(
                                resultingMode = SessionMode.SERVER_AUTHENTICATED,
                                activeUser = persistedUser,
                                updatedBaseline = companionBaseline,
                                deviceOverrodeSeerrUrl = deviceOverrodeSeerrUrl,
                                companionCredentials = companionCredentials,
                                currentServerIdUpdate = CurrentServerUpdate.Set(serverId),
                                standaloneModeUpdate = false,
                                dedicatedDigestsJson = encodeStringSet(dedicatedDigests),
                                revokedDigestsJson = encodeStringMap(revokedDigestsMap),
                                clearedPendingMigrationShellIds = shellIds,
                            )

                            // Step A2: bump epoch BEFORE mutating jellyfinApi
                            val nextEpoch = sessionEpoch.incrementAndGet()
                            val nextActivationEpoch =
                                if (reason in EXPLICIT_ACTIVATION_REASONS) {
                                    activationEpoch.incrementAndGet()
                                } else {
                                    activationEpoch.get()
                                }
                            val newIdentity = ConnectionIdentity(
                                mode = SessionMode.SERVER_AUTHENTICATED,
                                serverId = serverId,
                                serverAddress = effectiveAddress,
                                userId = user.id,
                                accessToken = token,
                            )
                            val identityChanged = lastConnectionIdentity != newIdentity
                            val nextConnEpoch = if (identityChanged) {
                                connectionEpoch.incrementAndGet()
                            } else {
                                connectionEpoch.get()
                            }
                            lastConnectionIdentity = newIdentity

                            applyJellyfinApiState(
                                mode = SessionMode.SERVER_AUTHENTICATED,
                                baseUrl = effectiveAddress,
                                accessToken = token,
                                userId = user.id,
                            )

                            val savedIds = database.getAllServersSync().map { it.id }.toSet()
                            val shareCount = database.getNetworkShares().size
                            val resolvedServerName = database.get(serverId)?.name ?: canonicalServer.name

                            val nextState = SessionState(
                                isLoading = false,
                                coordinatorInstanceId = coordinatorInstanceId,
                                sessionMode = SessionMode.SERVER_AUTHENTICATED,
                                sessionEpoch = nextEpoch,
                                activationEpoch = nextActivationEpoch,
                                connectionEpoch = nextConnEpoch,
                                activeServerId = serverId,
                                activeServerName = resolvedServerName,
                                activeServerAddress = effectiveAddress,
                                activeUserId = user.id,
                                activeUser = persistedUser.copy(accessToken = null),
                                hasServers = savedIds.isNotEmpty(),
                                savedServerIds = savedIds,
                                hasNetworkShares = shareCount > 0,
                                standaloneModeChosen = false,
                            )
                            publishState(nextState)
                            initialized.set(true)

                            // Step A3: connectivity notify
                            serverConnectionMonitor.get().onSessionActivated(serverId, reachable = true)

                            // Step A4: bus & post-commit side effects
                            Timber.i(
                                "Session activated: reason=%s server=%s user=%s epoch=%d activationEpoch=%d",
                                reason,
                                serverId,
                                user.id,
                                nextEpoch,
                                nextActivationEpoch,
                            )

                            val switchedAccount =
                                previousState.sessionMode == SessionMode.SERVER_AUTHENTICATED &&
                                    (previousServerId != serverId || previousUserId != user.id)

                            postCommitScope.launch {
                                for ((oldUid, newUid) in mergedUserPairs) {
                                    runCatching {
                                        extrasApplier.get().migrateUserScope(oldUid.toString(), newUid.toString())
                                    }.onFailure { Timber.w(it, "migrateUserScope failed") }
                                }
                                if (switchedAccount) {
                                    cancelIntegrityWork()
                                    runCatching { extrasApplier.get().clearPluginRuntimeCaches() }
                                    xrPlayerSessionCoordinator.get().forceCloseAllXrClients(nextEpoch)
                                } else {
                                    xrPlayerSessionCoordinator.get().notifyMainSessionUpdated(nextEpoch, effectiveAddress)
                                }
                                if (!isAppLocked()) {
                                    if (identityChanged) {
                                        activeSessionBus.notifyChanged()
                                    }
                                    extrasApplier.get().refreshMusicAssistant(nextState.toMusicAssistantScope())
                                    watchNextScheduler.get().syncNow(context)
                                }
                            }

                            ActivationResult.Activated(
                                serverId = serverId,
                                userId = user.id,
                                serverAddress = effectiveAddress,
                                sessionEpoch = nextEpoch,
                                activationEpoch = nextActivationEpoch,
                            )
                        } finally {
                            isActivating.set(false)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SQLiteException) {
            Timber.e(e, "activateServer failed with SQLiteException")
            ActivationResult.Failed(OnboardingFailureCode.STORAGE_ERROR)
        } catch (e: IOException) {
            Timber.e(e, "activateServer failed with IOException")
            ActivationResult.Failed(OnboardingFailureCode.STORAGE_ERROR)
        }
    }

    suspend fun commitLockedBackgroundSync(
        serverUpdates: List<BackgroundServerSyncUpdate>,
        expectedSessionEpoch: Long,
        companionBaseline: Map<String, String>? = null,
        deviceOverrodeSeerrUrl: Boolean? = null,
        shares: List<NetworkShareDto> = emptyList(),
        reason: SessionChangeReason = SessionChangeReason.COMPANION_BACKGROUND_SYNC,
    ): BackgroundSyncResult {
        requireMainProcess("commitLockedBackgroundSync")
        return try {
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    if (sessionEpoch.get() != expectedSessionEpoch) {
                        return@withLock BackgroundSyncResult.EpochMismatch
                    }
                    withContext(NonCancellable) {
                        isActivating.set(true)
                        try {
                            val previousState = _sessionState.value
                            val wasAuthenticated = previousState.sessionMode == SessionMode.SERVER_AUTHENTICATED
                            var currentServerId = appPreferences.getValue(appPreferences.currentServer)
                            var currentServerUpdate: CurrentServerUpdate = CurrentServerUpdate.Unchanged
                            val revokedDigestsMap = decodeRevokedDownloadTokenDigests().toMutableMap()
                            val allMergedUserPairs = mutableListOf<Pair<UUID, UUID>>()
                            val allMigratedShellIds = mutableSetOf<String>()
                            val revokedUserIds = mutableSetOf<UUID>()

                            for (update in serverUpdates) {
                                val shellIds = collectShellIds(
                                    canonicalServerId = update.serverId,
                                    explicitLegacyIds = update.legacyServerIds,
                                    candidateAddresses = update.addresses,
                                    userIds = update.users.map { it.user.id },
                                )
                                val existingCanonical = database.get(update.serverId)
                                val canonicalServer = Server(
                                    id = update.serverId,
                                    name = update.serverName?.takeIf { it.isNotBlank() }
                                        ?: existingCanonical?.name
                                        ?: shellIds.firstNotNullOfOrNull { database.get(it)?.name }
                                        ?: update.serverId,
                                    currentServerAddressId = existingCanonical?.currentServerAddressId,
                                    currentUserId = existingCanonical?.currentUserId,
                                )

                                val migrationUsers = if (previousState.sessionMode == SessionMode.SERVER_NEEDS_AUTH) {
                                    // Strip users[].user.accessToken across all serverUpdates while SERVER_NEEDS_AUTH
                                    update.users.map { u ->
                                        UserMigrationUpdate(
                                            user = u.user.copy(serverId = update.serverId, accessToken = null),
                                            clearToken = u.clearUserTokenOnAuthFailure,
                                            overwritePreferences = u.overwritePreferences,
                                        )
                                    }
                                } else {
                                    update.users.map { u ->
                                        val existingLocal = database.getUser(u.user.id)
                                        val shouldClear = u.clearUserTokenOnAuthFailure &&
                                            (u.expectedPreviousToken == null ||
                                                existingLocal?.accessToken == u.expectedPreviousToken)
                                        if (shouldClear) {
                                            val prevTok = existingLocal?.accessToken ?: u.expectedPreviousToken
                                            if (!prevTok.isNullOrBlank()) {
                                                revokedDigestsMap["${update.serverId}:${u.user.id}"] = sha256Hex(prevTok)
                                            }
                                            revokedUserIds.add(u.user.id)
                                        }
                                        UserMigrationUpdate(
                                            user = u.user.copy(serverId = update.serverId),
                                            clearToken = shouldClear,
                                            overwritePreferences = u.overwritePreferences,
                                        )
                                    }
                                }

                                val candidateRevoked = collectRevokedDigestsForScopes(
                                    revokedDigestsMap,
                                    shellIds + update.serverId,
                                    migrationUsers.map { it.user.id }.toSet(),
                                )
                                stagePendingServerIdMigrationsLocked(shellIds, update.serverId)
                                allMigratedShellIds.addAll(shellIds)
                                val merged = database.migrateLegacyServer(
                                    shellIds = shellIds,
                                    canonicalServer = canonicalServer,
                                    targetAddress = update.addressUrl?.let(::normalizeTrailingSlash),
                                    addresses = update.addresses.map(::normalizeTrailingSlash),
                                    users = migrationUsers,
                                    revokedDownloadTokenDigests = candidateRevoked,
                                )
                                allMergedUserPairs.addAll(merged)

                                if (currentServerId != null && currentServerId in shellIds) {
                                    currentServerId = update.serverId
                                    currentServerUpdate = CurrentServerUpdate.Set(update.serverId)
                                }
                            }

                            upsertValidatedShares(shares)

                            // Re-read active server/user state after batch commit
                            val server = currentServerId?.let { database.get(it) }
                            val withAddrAndUser = server?.let { database.getServerWithAddressAndUser(it.id) }
                            val resolvedAddress = withAddrAndUser?.address
                                ?: server?.let { database.getServerCurrentAddress(it.id) }
                            val activeUser = withAddrAndUser?.user
                            val hasValidToken = !activeUser?.accessToken.isNullOrBlank()
                            val savedIds = database.getAllServersSync().map { it.id }.toSet()
                            val shareCount = database.getNetworkShares().size
                            val standaloneChosen = appPreferences.getValue(appPreferences.standaloneModeChosen)

                            val nextMode = deriveSessionMode(
                                hasCurrentServer = server != null,
                                hasCurrentServerAddress = !resolvedAddress?.address.isNullOrBlank(),
                                hasCurrentUserWithToken = hasValidToken,
                                hasNetworkShares = shareCount > 0,
                                standaloneModeChosen = standaloneChosen,
                            )

                            val effectiveUser = if (nextMode == SessionMode.SERVER_AUTHENTICATED) activeUser else null
                            val effectiveBaseUrl = if (server != null) resolvedAddress?.address else null
                            val effectiveToken = if (nextMode == SessionMode.SERVER_AUTHENTICATED) effectiveUser?.accessToken else null

                            val dedicatedDigests = pruneDedicatedTokenDigests()
                            reconcileAndCommitPreferencesLocked(
                                resultingMode = nextMode,
                                activeUser = effectiveUser,
                                updatedBaseline = companionBaseline,
                                deviceOverrodeSeerrUrl = deviceOverrodeSeerrUrl,
                                companionCredentials = null,
                                currentServerIdUpdate = currentServerUpdate,
                                standaloneModeUpdate = null,
                                dedicatedDigestsJson = encodeStringSet(dedicatedDigests),
                                revokedDigestsJson = encodeStringMap(revokedDigestsMap),
                                clearedPendingMigrationShellIds = allMigratedShellIds,
                            )

                            val nextEpoch = sessionEpoch.incrementAndGet()
                            val newIdentity = ConnectionIdentity(
                                mode = nextMode,
                                serverId = server?.id,
                                serverAddress = effectiveBaseUrl,
                                userId = effectiveUser?.id,
                                accessToken = effectiveToken,
                            )
                            val identityChanged = lastConnectionIdentity != newIdentity
                            val nextConnEpoch = if (identityChanged) {
                                connectionEpoch.incrementAndGet()
                            } else {
                                connectionEpoch.get()
                            }
                            lastConnectionIdentity = newIdentity

                            applyJellyfinApiState(
                                mode = nextMode,
                                baseUrl = effectiveBaseUrl,
                                accessToken = effectiveToken,
                                userId = effectiveUser?.id,
                            )

                            val nextState = SessionState(
                                isLoading = false,
                                coordinatorInstanceId = coordinatorInstanceId,
                                sessionMode = nextMode,
                                sessionEpoch = nextEpoch,
                                activationEpoch = activationEpoch.get(),
                                connectionEpoch = nextConnEpoch,
                                activeServerId = server?.id,
                                activeServerName = server?.name,
                                activeServerAddress = effectiveBaseUrl,
                                activeUserId = effectiveUser?.id,
                                activeUser = effectiveUser?.copy(accessToken = null),
                                hasServers = savedIds.isNotEmpty(),
                                savedServerIds = savedIds,
                                hasNetworkShares = shareCount > 0,
                                standaloneModeChosen = standaloneChosen,
                            )
                            publishState(nextState)

                            Timber.i(
                                "commitLockedBackgroundSync committed: reason=%s servers=%d mode=%s epoch=%d",
                                reason,
                                serverUpdates.size,
                                nextMode,
                                nextEpoch,
                            )

                            val activeRevoked = wasAuthenticated && nextMode != SessionMode.SERVER_AUTHENTICATED
                            postCommitScope.launch {
                                for (uid in revokedUserIds) {
                                    runCatching { workManager.get().cancelAllWorkByTag("download-user:$uid") }
                                    activeDownloadRegistry.cancelByUser(uid)
                                }
                                for ((oldUid, newUid) in allMergedUserPairs) {
                                    runCatching {
                                        extrasApplier.get().migrateUserScope(oldUid.toString(), newUid.toString())
                                    }.onFailure { Timber.w(it, "migrateUserScope failed") }
                                }
                                if (activeRevoked) {
                                    xrPlayerSessionCoordinator.get().forceCloseAllXrClients(nextEpoch)
                                    extrasApplier.get().refreshMusicAssistant(MusicAssistantUserScope.Disabled)
                                    runCatching { extrasApplier.get().clearPluginRuntimeCaches() }
                                    runCatching { WatchNextSync.sync(context, emptyList(), emptyList()) }
                                    if (!isAppLocked()) {
                                        activeSessionBus.notifyChanged()
                                    }
                                } else {
                                    xrPlayerSessionCoordinator.get().notifyMainSessionUpdated(nextEpoch, effectiveBaseUrl)
                                    if (!isAppLocked() && identityChanged) {
                                        activeSessionBus.notifyChanged()
                                        extrasApplier.get().refreshMusicAssistant(nextState.toMusicAssistantScope())
                                    }
                                }
                            }

                            BackgroundSyncResult.Committed(nextEpoch)
                        } finally {
                            isActivating.set(false)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SQLiteException) {
            Timber.e(e, "commitLockedBackgroundSync failed with SQLiteException")
            BackgroundSyncResult.Failed(OnboardingFailureCode.STORAGE_ERROR)
        } catch (e: IOException) {
            Timber.e(e, "commitLockedBackgroundSync failed with IOException")
            BackgroundSyncResult.Failed(OnboardingFailureCode.STORAGE_ERROR)
        }
    }

    suspend fun activateStandalone(
        reason: SessionChangeReason,
        companionBaseline: Map<String, String>? = null,
        deviceOverrodeSeerrUrl: Boolean? = null,
        companionCredentials: CompanionCredentials? = null,
        shares: List<NetworkShareDto> = emptyList(),
    ): SessionState {
        requireMainProcess("activateStandalone")
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                withContext(NonCancellable) {
                    isActivating.set(true)
                    try {
                        upsertValidatedShares(shares)
                        reconcileAndCommitPreferencesLocked(
                            resultingMode = SessionMode.STANDALONE_SOURCES,
                            activeUser = null,
                            updatedBaseline = companionBaseline,
                            deviceOverrodeSeerrUrl = deviceOverrodeSeerrUrl,
                            companionCredentials = companionCredentials,
                            currentServerIdUpdate = CurrentServerUpdate.Clear,
                            standaloneModeUpdate = true,
                            dedicatedDigestsJson = null,
                            revokedDigestsJson = null,
                        )

                        val nextEpoch = sessionEpoch.incrementAndGet()
                        val nextActivationEpoch =
                            if (reason in STANDALONE_ACTIVATION_REASONS) {
                                activationEpoch.incrementAndGet()
                            } else {
                                activationEpoch.get()
                            }
                        val newIdentity = ConnectionIdentity(
                            mode = SessionMode.STANDALONE_SOURCES,
                            serverId = null,
                            serverAddress = null,
                            userId = null,
                            accessToken = null,
                        )
                        val identityChanged = lastConnectionIdentity != newIdentity
                        val nextConnEpoch = if (identityChanged) {
                            connectionEpoch.incrementAndGet()
                        } else {
                            connectionEpoch.get()
                        }
                        lastConnectionIdentity = newIdentity

                        applyJellyfinApiState(
                            mode = SessionMode.STANDALONE_SOURCES,
                            baseUrl = null,
                            accessToken = null,
                            userId = null,
                        )

                        val savedIds = database.getAllServersSync().map { it.id }.toSet()
                        val shareCount = database.getNetworkShares().size
                        val nextState = SessionState(
                            isLoading = false,
                            coordinatorInstanceId = coordinatorInstanceId,
                            sessionMode = SessionMode.STANDALONE_SOURCES,
                            sessionEpoch = nextEpoch,
                            activationEpoch = nextActivationEpoch,
                            connectionEpoch = nextConnEpoch,
                            activeServerId = null,
                            activeServerName = null,
                            activeServerAddress = null,
                            activeUserId = null,
                            activeUser = null,
                            hasServers = savedIds.isNotEmpty(),
                            savedServerIds = savedIds,
                            hasNetworkShares = shareCount > 0,
                            standaloneModeChosen = true,
                        )
                        publishState(nextState)
                        initialized.set(true)

                        Timber.i("Activated standalone mode: reason=%s epoch=%d", reason, nextEpoch)

                        postCommitScope.launch {
                            xrPlayerSessionCoordinator.get().forceCloseAllXrClients(nextEpoch)
                            runCatching { WatchNextSync.sync(context, emptyList(), emptyList()) }
                            if (!isAppLocked()) {
                                if (identityChanged) activeSessionBus.notifyChanged()
                                extrasApplier.get().refreshMusicAssistant(MusicAssistantUserScope.Standalone)
                            }
                        }
                        nextState
                    } finally {
                        isActivating.set(false)
                    }
                }
            }
        }
    }

    suspend fun applyCompanionBaselineOnly(
        companionBaseline: Map<String, String>,
        deviceOverrodeSeerrUrl: Boolean? = null,
        companionCredentials: CompanionCredentials? = null,
        shares: List<NetworkShareDto> = emptyList(),
        expectedSessionEpoch: Long? = null,
    ): BackgroundSyncResult {
        requireMainProcess("applyCompanionBaselineOnly")
        return try {
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    if (expectedSessionEpoch != null && sessionEpoch.get() != expectedSessionEpoch) {
                        return@withLock BackgroundSyncResult.EpochMismatch
                    }
                    withContext(NonCancellable) {
                        val current = _sessionState.value
                        val activeUser = current.activeUserId?.let { database.getUser(it) }
                        upsertValidatedShares(shares)
                        reconcileAndCommitPreferencesLocked(
                            resultingMode = current.sessionMode,
                            activeUser = if (current.sessionMode == SessionMode.SERVER_AUTHENTICATED) activeUser else null,
                            updatedBaseline = companionBaseline,
                            deviceOverrodeSeerrUrl = deviceOverrodeSeerrUrl,
                            companionCredentials = companionCredentials,
                            currentServerIdUpdate = CurrentServerUpdate.Unchanged,
                            standaloneModeUpdate = null,
                            dedicatedDigestsJson = null,
                            revokedDigestsJson = null,
                        )
                        val shareCount = database.getNetworkShares().size
                        val nextMode = deriveSessionMode(
                            hasCurrentServer = current.activeServerId != null,
                            hasCurrentServerAddress = !current.activeServerAddress.isNullOrBlank(),
                            hasCurrentUserWithToken = current.sessionMode == SessionMode.SERVER_AUTHENTICATED,
                            hasNetworkShares = shareCount > 0,
                            standaloneModeChosen = current.standaloneModeChosen,
                        )
                        val nextState = current.copy(
                            hasNetworkShares = shareCount > 0,
                            sessionMode = nextMode,
                        )
                        publishState(nextState)
                        BackgroundSyncResult.Committed(sessionEpoch.get())
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SQLiteException) {
            BackgroundSyncResult.Failed(OnboardingFailureCode.STORAGE_ERROR)
        } catch (e: IOException) {
            BackgroundSyncResult.Failed(OnboardingFailureCode.STORAGE_ERROR)
        }
    }

    suspend fun clearCompanionPreferencesOnDisconnect(): SessionState {
        requireMainProcess("clearCompanionPreferencesOnDisconnect")
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                withContext(NonCancellable) {
                    database.clearAllUserPreferences()
                    val current = _sessionState.value
                    reconcileAndCommitPreferencesLocked(
                        resultingMode = current.sessionMode,
                        activeUser = null,
                        updatedBaseline = emptyMap(),
                        deviceOverrodeSeerrUrl = false,
                        companionCredentials = null,
                        currentServerIdUpdate = CurrentServerUpdate.Unchanged,
                        standaloneModeUpdate = null,
                        dedicatedDigestsJson = null,
                        revokedDigestsJson = null,
                    )
                    val refreshed = current.copy(
                        activeUser = current.activeUser?.copy(preferences = null),
                    )
                    publishState(refreshed)
                    refreshed
                }
            }
        }
    }

    suspend fun commitCompanionFallbackMetadata(
        companionCredentials: CompanionCredentials,
        companionBaseline: Map<String, String>,
        shares: List<NetworkShareDto>,
        server: Server,
        addresses: List<String>,
        legacyServerIds: List<String>,
        deviceOverrodeSeerrUrl: Boolean? = null,
        handoffDeadlineElapsedMs: Long? = null,
    ): Boolean {
        requireMainProcess("commitCompanionFallbackMetadata")
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (handoffDeadlineElapsedMs != null &&
                    handoffDeadlineElapsedMs - SystemClock.elapsedRealtime() < MIN_COMMIT_REMAINING_MS
                ) {
                    return@withLock false
                }
                withContext(NonCancellable) {
                    val current = _sessionState.value
                    val isAlreadyConfigured =
                        current.activeServerId != null || current.standaloneModeChosen || current.hasNetworkShares
                    if (isAlreadyConfigured) {
                        upsertValidatedShares(shares)
                    }
                    val normalizedAddresses = addresses.map(::normalizeTrailingSlash).filter { it.isNotBlank() }.distinct()
                    val shellIds = collectShellIds(
                        canonicalServerId = server.id,
                        explicitLegacyIds = legacyServerIds,
                        candidateAddresses = normalizedAddresses,
                        userIds = emptyList(),
                    )
                    stagePendingServerIdMigrationsLocked(shellIds, server.id)
                    database.migrateLegacyServer(
                        shellIds = shellIds,
                        canonicalServer = server,
                        targetAddress = normalizedAddresses.firstOrNull(),
                        addresses = normalizedAddresses,
                        users = emptyList(),
                        revokedDownloadTokenDigests = emptySet(),
                    )
                    val currentServerPref = appPreferences.getValue(appPreferences.currentServer)
                    val fallbackServerUpdate =
                        if (currentServerPref != null && currentServerPref in shellIds) {
                            CurrentServerUpdate.Set(server.id)
                        } else {
                            CurrentServerUpdate.Unchanged
                        }
                    val activeUser = current.activeUserId?.let { database.getUser(it) }
                    reconcileAndCommitPreferencesLocked(
                        resultingMode = current.sessionMode,
                        activeUser = if (current.sessionMode == SessionMode.SERVER_AUTHENTICATED) activeUser else null,
                        updatedBaseline = companionBaseline,
                        deviceOverrodeSeerrUrl = deviceOverrodeSeerrUrl,
                        companionCredentials = companionCredentials,
                        currentServerIdUpdate = fallbackServerUpdate,
                        standaloneModeUpdate = null,
                        dedicatedDigestsJson = null,
                        revokedDigestsJson = null,
                        clearedPendingMigrationShellIds = shellIds,
                    )
                    val savedIds = database.getAllServersSync().map { it.id }.toSet()
                    val shareCount = database.getNetworkShares().size
                    val updated = current.copy(
                        activeServerId = if (current.activeServerId in shellIds) server.id else current.activeServerId,
                        savedServerIds = savedIds,
                        hasServers = savedIds.isNotEmpty(),
                        hasNetworkShares = shareCount > 0,
                    )
                    publishState(updated)
                    true
                }
            }
        }
    }

    suspend fun refreshActiveAddress(
        serverId: String,
        addressId: UUID,
        expectedEpoch: Long? = null,
    ): ActivationResult {
        requireMainProcess("refreshActiveAddress")
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = _sessionState.value
                if (current.activeServerId != serverId ||
                    (expectedEpoch != null && sessionEpoch.get() != expectedEpoch)
                ) {
                    return@withLock ActivationResult.Failed(OnboardingFailureCode.PAIRING_SESSION_ACTIVE)
                }
                val address = database.getAddress(addressId)
                if (address == null || address.serverId != serverId) {
                    return@withLock ActivationResult.Failed(OnboardingFailureCode.STORAGE_ERROR)
                }
                withContext(NonCancellable) {
                    isActivating.set(true)
                    try {
                        val nextEpoch = sessionEpoch.incrementAndGet()
                        database.updateServerCurrentAddress(serverId, address.id)
                        database.rewriteIncompleteDownloadRequestUrls(setOf(serverId), address.address)

                        val withAddrAndUser = database.getServerWithAddressAndUser(serverId)
                        val activeUser = withAddrAndUser?.user
                        val hasToken = !activeUser?.accessToken.isNullOrBlank()
                        val nextMode =
                            if (hasToken) SessionMode.SERVER_AUTHENTICATED else SessionMode.SERVER_NEEDS_AUTH
                        val effectiveToken = if (nextMode == SessionMode.SERVER_AUTHENTICATED) activeUser?.accessToken else null
                        val effectiveUser = if (nextMode == SessionMode.SERVER_AUTHENTICATED) activeUser else null

                        applyJellyfinApiState(
                            mode = nextMode,
                            baseUrl = address.address,
                            accessToken = effectiveToken,
                            userId = effectiveUser?.id,
                        )

                        val nextConnEpoch = connectionEpoch.incrementAndGet()
                        lastConnectionIdentity = ConnectionIdentity(
                            mode = nextMode,
                            serverId = serverId,
                            serverAddress = address.address,
                            userId = effectiveUser?.id,
                            accessToken = effectiveToken,
                        )
                        val resolvedName = database.get(serverId)?.name ?: current.activeServerName ?: serverId
                        val nextState = current.copy(
                            isLoading = false,
                            sessionMode = nextMode,
                            sessionEpoch = nextEpoch,
                            connectionEpoch = nextConnEpoch,
                            activeServerName = resolvedName,
                            activeServerAddress = address.address,
                            activeUserId = effectiveUser?.id,
                            activeUser = effectiveUser?.copy(accessToken = null),
                        )
                        publishState(nextState)

                        postCommitScope.launch {
                            xrPlayerSessionCoordinator.get().notifyMainSessionUpdated(nextEpoch, address.address)
                            if (!isAppLocked()) {
                                activeSessionBus.notifyChanged()
                            }
                        }

                        if (nextMode == SessionMode.SERVER_AUTHENTICATED && effectiveUser != null) {
                            ActivationResult.Activated(
                                serverId = serverId,
                                userId = effectiveUser.id,
                                serverAddress = address.address,
                                sessionEpoch = nextEpoch,
                                activationEpoch = current.activationEpoch,
                            )
                        } else {
                            ActivationResult.NeedsAuth(
                                serverId = serverId,
                                serverName = resolvedName,
                                serverAddress = address.address,
                            )
                        }
                    } finally {
                        isActivating.set(false)
                    }
                }
            }
        }
    }

    suspend fun saveProbedServer(
        serverId: String,
        serverName: String,
        addressUrl: String,
    ): Pair<Server, ServerAddress> {
        requireMainProcess("saveProbedServer")
        awaitPendingServerDeleteCleanup(serverId)
        val normalizedUrl = normalizeTrailingSlash(addressUrl)
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                withContext(NonCancellable) {
                    val existing = database.get(serverId)
                    val mergedServer = Server(
                        id = serverId,
                        name = serverName.takeIf { it.isNotBlank() } ?: existing?.name ?: serverId,
                        currentServerAddressId = existing?.currentServerAddressId,
                        currentUserId = existing?.currentUserId,
                    )
                    database.insertServerIgnore(mergedServer)
                    database.update(mergedServer)

                    val existingAddresses = database.getServerWithAddresses(serverId)?.addresses.orEmpty()
                    val matched = existingAddresses.firstOrNull {
                        normalizeTrailingSlash(it.address).equals(normalizedUrl, ignoreCase = true)
                    }
                    val serverAddress = matched ?: ServerAddress(
                        id = UUID.randomUUID(),
                        serverId = serverId,
                        address = normalizedUrl,
                    ).also { addr ->
                        database.insertServerAddress(addr)
                        if (mergedServer.currentServerAddressId == null) {
                            database.updateServerCurrentAddress(serverId, addr.id)
                        }
                    }

                    val updatedServer = database.get(serverId) ?: mergedServer.copy(
                        currentServerAddressId = mergedServer.currentServerAddressId ?: serverAddress.id,
                    )
                    val savedIds = database.getAllServersSync().map { it.id }.toSet()
                    val current = _sessionState.value
                    publishState(
                        current.copy(
                            savedServerIds = savedIds,
                            hasServers = savedIds.isNotEmpty(),
                        ),
                    )
                    updatedServer to serverAddress
                }
            }
        }
    }

    suspend fun logout(
        reason: SessionChangeReason = SessionChangeReason.LOGOUT,
    ): SessionState {
        requireMainProcess("logout")
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                withContext(NonCancellable) {
                    isActivating.set(true)
                    try {
                        val current = _sessionState.value
                        val outgoingServerId = current.activeServerId
                        val outgoingAddress = current.activeServerAddress
                        val outgoingUserId = current.activeUserId
                        val outgoingUser = outgoingUserId?.let { database.getUser(it) }
                        val outgoingToken = outgoingUser?.accessToken?.takeIf { it.isNotBlank() }

                        val dedicatedDigests = decodeDedicatedTokenDigests()
                        val shouldLogoutRemote = outgoingToken != null &&
                            sha256Hex(outgoingToken) in dedicatedDigests

                        val revokedDigestsMap = decodeRevokedDownloadTokenDigests().toMutableMap()
                        if (outgoingServerId != null && outgoingUserId != null && outgoingToken != null) {
                            revokedDigestsMap["$outgoingServerId:$outgoingUserId"] = sha256Hex(outgoingToken)
                        }

                        if (outgoingServerId != null) {
                            database.updateServerCurrentUser(outgoingServerId, null)
                        }
                        if (outgoingUserId != null) {
                            database.clearUserAccessToken(outgoingUserId)
                        }

                        val prunedDedicated = pruneDedicatedTokenDigests()
                        val nextMode =
                            if (outgoingServerId != null) {
                                SessionMode.SERVER_NEEDS_AUTH
                            } else if (current.hasNetworkShares || current.standaloneModeChosen) {
                                SessionMode.STANDALONE_SOURCES
                            } else {
                                SessionMode.UNCONFIGURED
                            }

                        reconcileAndCommitPreferencesLocked(
                            resultingMode = nextMode,
                            activeUser = null,
                            updatedBaseline = null,
                            deviceOverrodeSeerrUrl = null,
                            companionCredentials = null,
                            currentServerIdUpdate = CurrentServerUpdate.Unchanged,
                            standaloneModeUpdate = null,
                            dedicatedDigestsJson = encodeStringSet(prunedDedicated),
                            revokedDigestsJson = encodeStringMap(revokedDigestsMap),
                            clearPendingSyncPlayForUser = outgoingUserId,
                        )

                        val nextEpoch = sessionEpoch.incrementAndGet()
                        val nextConnEpoch = connectionEpoch.incrementAndGet()
                        val retainedBaseUrl = if (nextMode == SessionMode.SERVER_NEEDS_AUTH) outgoingAddress else null
                        lastConnectionIdentity = ConnectionIdentity(
                            mode = nextMode,
                            serverId = outgoingServerId,
                            serverAddress = retainedBaseUrl,
                            userId = null,
                            accessToken = null,
                        )

                        applyJellyfinApiState(
                            mode = nextMode,
                            baseUrl = retainedBaseUrl,
                            accessToken = null,
                            userId = null,
                        )

                        val nextState = current.copy(
                            isLoading = false,
                            sessionMode = nextMode,
                            sessionEpoch = nextEpoch,
                            connectionEpoch = nextConnEpoch,
                            activeServerAddress = retainedBaseUrl,
                            activeUserId = null,
                            activeUser = null,
                        )
                        publishState(nextState)

                        Timber.i("Session logged out: reason=%s server=%s epoch=%d", reason, outgoingServerId, nextEpoch)

                        val tokenForLogout = if (shouldLogoutRemote) outgoingToken else null
                        postCommitScope.launch {
                            cancelIntegrityWork()
                            if (outgoingUserId != null) {
                                runCatching { workManager.get().cancelAllWorkByTag("download-user:$outgoingUserId") }
                                activeDownloadRegistry.cancelByUser(outgoingUserId)
                            }
                            xrPlayerSessionCoordinator.get().forceCloseAllXrClients(nextEpoch)
                            extrasApplier.get().refreshMusicAssistant(MusicAssistantUserScope.Disabled)
                            runCatching { extrasApplier.get().clearPluginRuntimeCaches() }
                            runCatching { WatchNextSync.sync(context, emptyList(), emptyList()) }
                            sweepTempPlaybackCaches()
                            if (tokenForLogout != null && !outgoingAddress.isNullOrBlank()) {
                                bestEffortRemoteLogout(outgoingAddress, tokenForLogout)
                            }
                            if (!isAppLocked()) {
                                activeSessionBus.notifyChanged()
                            }
                        }

                        nextState
                    } finally {
                        isActivating.set(false)
                    }
                }
            }
        }
    }

    suspend fun deleteUserAndNotify(userId: UUID): SessionState {
        requireMainProcess("deleteUserAndNotify")
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                withContext(NonCancellable) {
                    isActivating.set(true)
                    try {
                        val current = _sessionState.value
                        val user = database.getUser(userId)
                        val userServerId = user?.serverId
                        val userToken = user?.accessToken?.takeIf { it.isNotBlank() }
                        val serverAddress = userServerId?.let { database.getServerCurrentAddress(it)?.address }

                        val dedicatedDigests = decodeDedicatedTokenDigests()
                        val shouldLogoutRemote = userToken != null && sha256Hex(userToken) in dedicatedDigests

                        database.deleteUserDataByUserIds(listOf(userId))
                        database.clearServersCurrentUser(userId)
                        database.deleteUser(userId)

                        val revokedDigestsMap = decodeRevokedDownloadTokenDigests().toMutableMap()
                        if (userServerId != null) {
                            revokedDigestsMap.remove("$userServerId:$userId")
                        }
                        val prunedDedicated = pruneDedicatedTokenDigests()

                        val wasActiveUser = current.activeUserId == userId
                        val nextEpoch = sessionEpoch.incrementAndGet()

                        val nextState = if (wasActiveUser) {
                            val nextMode =
                                if (current.activeServerId != null) {
                                    SessionMode.SERVER_NEEDS_AUTH
                                } else if (current.hasNetworkShares || current.standaloneModeChosen) {
                                    SessionMode.STANDALONE_SOURCES
                                } else {
                                    SessionMode.UNCONFIGURED
                                }
                            reconcileAndCommitPreferencesLocked(
                                resultingMode = nextMode,
                                activeUser = null,
                                updatedBaseline = null,
                                deviceOverrodeSeerrUrl = null,
                                companionCredentials = null,
                                currentServerIdUpdate = CurrentServerUpdate.Unchanged,
                                standaloneModeUpdate = null,
                                dedicatedDigestsJson = encodeStringSet(prunedDedicated),
                                revokedDigestsJson = encodeStringMap(revokedDigestsMap),
                                clearPendingSyncPlayForUser = userId,
                            )
                            val nextConnEpoch = connectionEpoch.incrementAndGet()
                            val retainedBaseUrl = if (nextMode == SessionMode.SERVER_NEEDS_AUTH) current.activeServerAddress else null
                            lastConnectionIdentity = ConnectionIdentity(
                                mode = nextMode,
                                serverId = current.activeServerId,
                                serverAddress = retainedBaseUrl,
                                userId = null,
                                accessToken = null,
                            )
                            applyJellyfinApiState(
                                mode = nextMode,
                                baseUrl = retainedBaseUrl,
                                accessToken = null,
                                userId = null,
                            )
                            current.copy(
                                isLoading = false,
                                sessionMode = nextMode,
                                sessionEpoch = nextEpoch,
                                connectionEpoch = nextConnEpoch,
                                activeServerAddress = retainedBaseUrl,
                                activeUserId = null,
                                activeUser = null,
                            )
                        } else {
                            appPreferences.commitMainBatch { editor ->
                                editor.putString(appPreferences.dedicatedAccessTokenDigestsJson.backendName, encodeStringSet(prunedDedicated))
                                editor.putString(appPreferences.revokedDownloadTokenDigestsJson.backendName, encodeStringMap(revokedDigestsMap))
                                if (appPreferences.getValue(appPreferences.pendingSyncPlayLeaveUserId) == userId.toString()) {
                                    editor.remove(appPreferences.pendingSyncPlayLeaveServerId.backendName)
                                    editor.remove(appPreferences.pendingSyncPlayLeaveUserId.backendName)
                                }
                            }
                            current.copy(sessionEpoch = nextEpoch)
                        }
                        publishState(nextState)

                        val tokenForLogout = if (shouldLogoutRemote) userToken else null
                        postCommitScope.launch {
                            cancelSyncAndIntegrityWork()
                            runCatching { workManager.get().cancelAllWorkByTag("download-user:$userId") }
                            activeDownloadRegistry.cancelByUser(userId)
                            runCatching { extrasApplier.get().clearUserScope(userId.toString()) }
                            if (wasActiveUser) {
                                xrPlayerSessionCoordinator.get().forceCloseAllXrClients(nextEpoch)
                                extrasApplier.get().refreshMusicAssistant(MusicAssistantUserScope.Disabled)
                                runCatching { WatchNextSync.sync(context, emptyList(), emptyList()) }
                                sweepTempPlaybackCaches()
                                if (!isAppLocked()) activeSessionBus.notifyChanged()
                            } else {
                                xrPlayerSessionCoordinator.get().notifyMainSessionUpdated(nextEpoch, nextState.activeServerAddress)
                            }
                            if (tokenForLogout != null && !serverAddress.isNullOrBlank()) {
                                bestEffortRemoteLogout(serverAddress, tokenForLogout)
                            }
                        }

                        nextState
                    } finally {
                        isActivating.set(false)
                    }
                }
            }
        }
    }

    suspend fun deleteServerAndNotify(serverId: String): SessionState {
        requireMainProcess("deleteServerAndNotify")
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                withContext(NonCancellable) {
                    isActivating.set(true)
                    try {
                        val current = _sessionState.value
                        val wasActiveServer = current.activeServerId == serverId
                        val dedicatedDigests = decodeDedicatedTokenDigests()

                        val snapshot = database.deleteServerAndOfflineMedia(serverId)
                        val logoutTokens = snapshot.snapshottedUsers
                            .mapNotNull { u -> u.accessToken?.takeIf { it.isNotBlank() } }
                            .filter { sha256Hex(it) in dedicatedDigests }

                        val revokedDigestsMap = decodeRevokedDownloadTokenDigests().toMutableMap()
                        revokedDigestsMap.keys.removeAll { it.startsWith("$serverId:") }
                        val prunedDedicated = pruneDedicatedTokenDigests()

                        val savedIds = database.getAllServersSync().map { it.id }.toSet()
                        val shareCount = database.getNetworkShares().size
                        val standaloneChosen = appPreferences.getValue(appPreferences.standaloneModeChosen)
                        val nextEpoch = sessionEpoch.incrementAndGet()

                        val nextState = if (wasActiveServer) {
                            val nextMode = deriveSessionMode(
                                hasCurrentServer = false,
                                hasCurrentServerAddress = false,
                                hasCurrentUserWithToken = false,
                                hasNetworkShares = shareCount > 0,
                                standaloneModeChosen = standaloneChosen,
                            )
                            reconcileAndCommitPreferencesLocked(
                                resultingMode = nextMode,
                                activeUser = null,
                                updatedBaseline = null,
                                deviceOverrodeSeerrUrl = null,
                                companionCredentials = null,
                                currentServerIdUpdate = CurrentServerUpdate.Clear,
                                standaloneModeUpdate = null,
                                dedicatedDigestsJson = encodeStringSet(prunedDedicated),
                                revokedDigestsJson = encodeStringMap(revokedDigestsMap),
                                clearPendingSyncPlayForServer = serverId,
                            )
                            val nextConnEpoch = connectionEpoch.incrementAndGet()
                            lastConnectionIdentity = ConnectionIdentity(
                                mode = nextMode,
                                serverId = null,
                                serverAddress = null,
                                userId = null,
                                accessToken = null,
                            )
                            applyJellyfinApiState(
                                mode = nextMode,
                                baseUrl = null,
                                accessToken = null,
                                userId = null,
                            )
                            current.copy(
                                isLoading = false,
                                sessionMode = nextMode,
                                sessionEpoch = nextEpoch,
                                connectionEpoch = nextConnEpoch,
                                activeServerId = null,
                                activeServerName = null,
                                activeServerAddress = null,
                                activeUserId = null,
                                activeUser = null,
                                hasServers = savedIds.isNotEmpty(),
                                savedServerIds = savedIds,
                                hasNetworkShares = shareCount > 0,
                            )
                        } else {
                            appPreferences.commitMainBatch { editor ->
                                editor.putString(appPreferences.dedicatedAccessTokenDigestsJson.backendName, encodeStringSet(prunedDedicated))
                                editor.putString(appPreferences.revokedDownloadTokenDigestsJson.backendName, encodeStringMap(revokedDigestsMap))
                                if (appPreferences.getValue(appPreferences.pendingSyncPlayLeaveServerId) == serverId) {
                                    editor.remove(appPreferences.pendingSyncPlayLeaveServerId.backendName)
                                    editor.remove(appPreferences.pendingSyncPlayLeaveUserId.backendName)
                                }
                            }
                            current.copy(
                                sessionEpoch = nextEpoch,
                                hasServers = savedIds.isNotEmpty(),
                                savedServerIds = savedIds,
                                hasNetworkShares = shareCount > 0,
                            )
                        }
                        publishState(nextState)

                        val cleanupJob = postCommitScope.launch {
                            try {
                                cancelSyncAndIntegrityWork()
                                runCatching { workManager.get().cancelAllWorkByTag("download-server:$serverId") }
                                activeDownloadRegistry.cancelByServer(serverId)
                                activeDownloadRegistry.commitMutex.withLock {
                                    val storage = downloadStorageManager.get()
                                    for (itemId in snapshot.itemIds) {
                                        val hasLiveRow =
                                            database.getMovieOrNull(itemId) != null || database.getEpisodeOrNull(itemId) != null
                                        if (!hasLiveRow) {
                                            runCatching { storage.markDownloadDeleted(itemId) }
                                            runCatching { File(context.filesDir, "images/$itemId").deleteRecursively() }
                                            runCatching { File(context.filesDir, "trickplay/$itemId").deleteRecursively() }
                                        }
                                    }
                                    for (taskId in snapshot.taskIds) {
                                        val liveTask = database.getDownloadTaskById(taskId)
                                        if (liveTask == null) {
                                            runCatching { DownloadStagingPaths.privateStagingFile(context, taskId).delete() }
                                        }
                                    }
                                    for (path in snapshot.filePaths) {
                                        val liveByPath = database.getDownloadTaskByFinalPath(path)
                                        if (liveByPath == null) {
                                            runCatching { storage.deletePhysicalFileAndMediaStore(path) }
                                        }
                                    }
                                    sweepTempPlaybackCaches()
                                }
                                for (u in snapshot.snapshottedUsers) {
                                    runCatching { extrasApplier.get().clearUserScope(u.id.toString()) }
                                }
                                if (wasActiveServer) {
                                    xrPlayerSessionCoordinator.get().forceCloseAllXrClients(nextEpoch)
                                    runCatching { WatchNextSync.sync(context, emptyList(), emptyList()) }
                                    if (!isAppLocked()) {
                                        extrasApplier.get().refreshMusicAssistant(nextState.toMusicAssistantScope())
                                        activeSessionBus.notifyChanged()
                                    }
                                } else {
                                    xrPlayerSessionCoordinator.get().notifyMainSessionUpdated(nextEpoch, nextState.activeServerAddress)
                                }
                                val baseUrl = snapshot.activeBaseUrl
                                if (!baseUrl.isNullOrBlank()) {
                                    for (tok in logoutTokens) {
                                        bestEffortRemoteLogout(baseUrl, tok)
                                    }
                                }
                            } finally {
                                pendingServerDeleteJobs.remove(serverId)
                            }
                        }
                        pendingServerDeleteJobs[serverId] = cleanupJob

                        nextState
                    } finally {
                        isActivating.set(false)
                    }
                }
            }
        }
    }

    private suspend fun onAppLockEnrollmentChanged() {
        if (!isMainProcess) return
        val enrolled = appPreferences.getValue(appPreferences.appLockMode) in setOf("biometric", "pin")
        mutex.withLock {
            withContext(NonCancellable) {
                val current = _sessionState.value
                val currentBaseline = decodeStringMap(
                    appPreferences.getValue(appPreferences.companionPreferenceBaselineJson),
                )
                val activeUser = current.activeUserId?.let { database.getUser(it) }
                reconcileAndCommitPreferencesLocked(
                    resultingMode = current.sessionMode,
                    activeUser = if (current.sessionMode == SessionMode.SERVER_AUTHENTICATED) activeUser else null,
                    updatedBaseline = currentBaseline,
                    deviceOverrodeSeerrUrl = null,
                    companionCredentials = null,
                    currentServerIdUpdate = CurrentServerUpdate.Unchanged,
                    standaloneModeUpdate = null,
                    dedicatedDigestsJson = null,
                    revokedDigestsJson = null,
                )
            }
        }
        val hasSavedCompanion =
            appPreferences.getValue(appPreferences.companionUrl).isNotBlank() &&
                appPreferences.getValue(appPreferences.companionToken).isNotBlank()
        if (enrolled && hasSavedCompanion && !isAppLocked()) {
            val request = OneTimeWorkRequestBuilder<CompanionSyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            runCatching {
                workManager.get().enqueueUniqueWork(
                    "CompanionSyncPush",
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
            }
        }
    }

    private suspend fun onAppLocked(isColdStart: Boolean) {
        if (!isMainProcess) return
        Timber.i("SessionActivationCoordinator: App Lock LOCKED (coldStart=%s)", isColdStart)
        runCatching { extrasApplier.get().onAppLocked() }
        runCatching { WatchNextSync.sync(context, emptyList(), emptyList()) }
        sweepTempPlaybackCaches()
        xrPlayerSessionCoordinator.get().forceCloseAllXrClients(sessionEpoch.get())
        if (appPreferences.getValue(appPreferences.contentEncryptionEnabled)) {
            runLockedEncryptedDownloadSweep()
        }
    }

    private suspend fun runLockedEncryptedDownloadSweep() {
        runCatching { workManager.get().cancelAllWorkByTag("download-primary") }
        activeDownloadRegistry.cancelPrimary()
        activeDownloadRegistry.commitMutex.withLock {
            val storage = downloadStorageManager.get()
            val tasks = database.getAllDownloadTasks().filter {
                it.kind == dev.jdtech.jellyfin.models.DownloadTaskKind.PRIMARY && it.status != 8
            }
            val now = System.currentTimeMillis()
            for (task in tasks) {
                if (task.tempPath.isNotBlank()) {
                    runCatching { storage.deletePhysicalFileAndMediaStore(task.tempPath) }
                }
                if (task.finalPath.isNotBlank()) {
                    runCatching { File("${task.finalPath}.enc.tmp").delete() }
                }
                runCatching { DownloadStagingPaths.privateStagingFile(context, task.id).delete() }
                database.updateDownloadTask(
                    id = task.id,
                    downloadId = task.downloadId,
                    bytesDownloaded = 0L,
                    totalBytes = task.totalBytes,
                    eTag = null,
                    lastModified = null,
                    status = 4, // STATUS_PAUSED
                    progress = 0,
                    errorMessage = ResumableDownloadWorker.DEK_LOCKED_PAUSE_MESSAGE,
                    updatedAt = now,
                )
                database.setDownloadTaskEncryption(
                    id = task.id,
                    isEncrypted = false,
                    encryptionIv = null,
                    updatedAt = now,
                )
            }
        }
    }

    private suspend fun onAppUnlocked() {
        if (!isMainProcess) return
        Timber.i("SessionActivationCoordinator: App Lock UNLOCKED")
        val state = ensureInitialized()
        if (state.sessionMode == SessionMode.SERVER_AUTHENTICATED) {
            val activeServerId = state.activeServerId
            val pausedForLock = database.getDownloadTasksByErrorMessage(
                ResumableDownloadWorker.DEK_LOCKED_PAUSE_MESSAGE,
            )
            for (task in pausedForLock) {
                if (activeServerId != null && database.resolveDownloadTaskServerId(task) == activeServerId) {
                    runCatching { downloader.get().resumeDownloadById(task.itemId) }
                }
            }
            if (unlockIntegrityCheckedOnce.compareAndSet(false, true)) {
                runCatching { DownloadIntegrityWorker.enqueue(context) }
            }
            val pendingSync = runCatching {
                state.activeUserId?.let { uid -> database.getUserDataToBeSynced(uid).isNotEmpty() } == true
            }.getOrDefault(false)
            if (pendingSync) {
                val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                runCatching {
                    workManager.get().enqueueUniqueWork(
                        "syncUserData",
                        ExistingWorkPolicy.KEEP,
                        syncRequest,
                    )
                }
            }
            runCatching { serverConnectionMonitor.get().refreshServerAccessibility() }
            runCatching { watchNextScheduler.get().syncNow(context) }
        }
        val maScope = if (SessionNavigationPolicy.isMaOverlayAllowed(state.sessionMode)) {
            state.toMusicAssistantScope()
        } else {
            MusicAssistantUserScope.Disabled
        }
        runCatching { extrasApplier.get().onAppUnlocked(maScope) }
        runCatching { CompanionLogUploader.onAppUnlocked() }
        runCatching { companionLiveSyncClient.get().onAppUnlocked() }
    }

    private fun dispatchRefreshMusicAssistant(state: SessionState) {
        postCommitScope.launch {
            if (!isAppLocked()) {
                extrasApplier.get().refreshMusicAssistant(state.toMusicAssistantScope())
            }
        }
    }

    private fun applyJellyfinApiState(
        mode: SessionMode,
        baseUrl: String?,
        accessToken: String?,
        userId: UUID?,
    ) {
        if (mode == SessionMode.SERVER_AUTHENTICATED && !baseUrl.isNullOrBlank() && !accessToken.isNullOrBlank() && userId != null) {
            // Ordered activation: api.update BEFORE userId
            jellyfinApi.api.update(baseUrl = baseUrl, accessToken = accessToken)
            jellyfinApi.userId = userId
        } else {
            // Ordered teardown: userId = null BEFORE api.update
            jellyfinApi.userId = null
            jellyfinApi.api.update(
                baseUrl = if (mode == SessionMode.SERVER_NEEDS_AUTH) baseUrl else null,
                accessToken = null,
            )
        }
    }

    private sealed interface CurrentServerUpdate {
        data object Unchanged : CurrentServerUpdate
        data object Clear : CurrentServerUpdate
        data class Set(val serverId: String) : CurrentServerUpdate
    }

    /**
     * Step `A1f`: Centralized 3-pass preference baseline and per-user override reconciliation.
     * Flushes the complete net diff + metadata + session keys in ONE synchronous `Editor.commit()`
     * wrapped inside `appPreferences.withAppLockPolicyLock { ... }`.
     */
    private fun reconcileAndCommitPreferencesLocked(
        resultingMode: SessionMode,
        activeUser: User?,
        updatedBaseline: Map<String, String>?,
        deviceOverrodeSeerrUrl: Boolean?,
        companionCredentials: CompanionCredentials?,
        currentServerIdUpdate: CurrentServerUpdate,
        standaloneModeUpdate: Boolean?,
        dedicatedDigestsJson: String?,
        revokedDigestsJson: String?,
        clearPendingSyncPlayForUser: UUID? = null,
        clearPendingSyncPlayForServer: String? = null,
        clearedPendingMigrationShellIds: Set<String> = emptySet(),
    ) {
        appPreferences.withAppLockPolicyLock {
            val companionBaseline = decodeStringMap(
                appPreferences.getValue(appPreferences.companionPreferenceBaselineJson),
            ).toMutableMap()
            val preBaselineLocalSnapshots = decodeNullableStringMap(
                appPreferences.getValue(appPreferences.preBaselineLocalSnapshotsJson),
            ).toMutableMap()
            val preOverrideSnapshot = decodeNullableStringMap(
                appPreferences.getValue(appPreferences.appliedUserOverrideSnapshotsJson),
            )
            val effectiveDeviceOverrodeSeerr = deviceOverrodeSeerrUrl
                ?: appPreferences.getValue(appPreferences.companionDeviceOverrodeSeerrUrl)

            val staging = CompanionPreferenceStaging(appPreferences)
            val revertedInPass1a = mutableSetOf<String>()

            // Pass 1a & 1b: revert prior user overrides
            for ((k, priorOverrideVal) in preOverrideSnapshot) {
                if (!isSyncableCompanionPreferenceKey(k)) continue
                val inBaseline = k in companionBaseline
                val remainsInUpdatedBaseline = updatedBaseline == null || k in updatedBaseline
                if (inBaseline && remainsInUpdatedBaseline) {
                    val baseVal = companionBaseline[k]
                    val applied = baseVal != null && staging.applyCompanionPreference(k, baseVal)
                    if (!applied) {
                        companionBaseline.remove(k)
                        val localSnap = if (k in preBaselineLocalSnapshots) {
                            preBaselineLocalSnapshots.remove(k)
                        } else {
                            priorOverrideVal
                        }
                        staging.revertCompanionPreference(k, localSnap)
                        revertedInPass1a.add(k)
                    }
                } else {
                    if (k in preBaselineLocalSnapshots && updatedBaseline != null && k !in updatedBaseline) {
                        staging.revertCompanionPreference(k, preBaselineLocalSnapshots[k])
                    } else {
                        staging.revertCompanionPreference(k, priorOverrideVal)
                    }
                }
            }

            // Passes 2a–2c: apply updated baseline (only when updatedBaseline != null)
            val nextBaseline: MutableMap<String, String> =
                if (updatedBaseline == null) {
                    companionBaseline
                } else {
                    val localSeerrUrl =
                        if (KEY_SEERR_URL in preBaselineLocalSnapshots) {
                            preBaselineLocalSnapshots[KEY_SEERR_URL]
                        } else {
                            staging.readRaw(KEY_SEERR_URL)
                        }
                    val built = mutableMapOf<String, String>()
                    for ((k, v) in updatedBaseline) {
                        if (!isSyncableCompanionPreferenceKey(k)) continue
                        val priorLocal = if (k !in preBaselineLocalSnapshots) staging.readRaw(k) else null
                        if (staging.applyCompanionPreference(k, v)) {
                            built[k] = v
                            if (k !in preBaselineLocalSnapshots) {
                                preBaselineLocalSnapshots[k] = priorLocal
                            }
                        }
                    }

                    // Pass 2b: Local -> baseline Predicate 3 & removed baseline keys
                    val baselineSeerrUrl = built[KEY_SEERR_URL]
                    val seerrApiKeySuppressed =
                        baselineSeerrUrl != null &&
                            normalizeUrlForComparison(baselineSeerrUrl) != normalizeUrlForComparison(localSeerrUrl) &&
                            KEY_SEERR_API_KEY !in built
                    if (seerrApiKeySuppressed) {
                        if (KEY_SEERR_API_KEY !in preBaselineLocalSnapshots) {
                            preBaselineLocalSnapshots[KEY_SEERR_API_KEY] = staging.readRaw(KEY_SEERR_API_KEY)
                        }
                        staging.revertCompanionPreference(KEY_SEERR_API_KEY, null)
                    }

                    val survivingBaselineKeys = companionBaseline.keys.filter(::isSyncableCompanionPreferenceKey)
                    val removedKeys = survivingBaselineKeys - built.keys
                    for (k in removedKeys) {
                        if (k in revertedInPass1a) continue
                        if (k == KEY_SEERR_API_KEY && seerrApiKeySuppressed) continue
                        val localSnap = preBaselineLocalSnapshots.remove(k)
                        staging.revertCompanionPreference(k, localSnap)
                    }

                    // Pass 2c: restore unsuppressed local Seerr key
                    if (!seerrApiKeySuppressed &&
                        KEY_SEERR_API_KEY !in built &&
                        KEY_SEERR_API_KEY in preBaselineLocalSnapshots
                    ) {
                        val localSnap = preBaselineLocalSnapshots.remove(KEY_SEERR_API_KEY)
                        staging.revertCompanionPreference(KEY_SEERR_API_KEY, localSnap)
                    }
                    built
                }

            // Pass 3: active-user overrides (ONLY when SERVER_AUTHENTICATED)
            val nextUserOverrideSnapshots = mutableMapOf<String, String?>()
            if (resultingMode == SessionMode.SERVER_AUTHENTICATED) {
                val userPrefs = decodeStringMap(activeUser?.preferences)
                if (userPrefs.isNotEmpty()) {
                    val currentEffectiveSeerrUrl = staging.readRaw(KEY_SEERR_URL)
                    val userSeerrUrl = userPrefs[KEY_SEERR_URL]?.takeIf { it.isNotBlank() }
                    val userHasOwnSeerrKey = !userPrefs[KEY_SEERR_API_KEY].isNullOrBlank()

                    // Direction 1: user overrides pref_seerr_url to a different URL without its own key
                    if (userSeerrUrl != null &&
                        normalizeUrlForComparison(userSeerrUrl) != normalizeUrlForComparison(currentEffectiveSeerrUrl) &&
                        !userHasOwnSeerrKey
                    ) {
                        val priorKey = staging.readRaw(KEY_SEERR_API_KEY)
                        staging.revertCompanionPreference(KEY_SEERR_API_KEY, null)
                        nextUserOverrideSnapshots[KEY_SEERR_API_KEY] = priorKey
                    }

                    for ((k, v) in userPrefs) {
                        if (!isSyncableCompanionPreferenceKey(k)) continue
                        // Direction 2: suppress user pref_seerr_api_key lacking its own pref_seerr_url
                        // when devicePreferences overrode pref_seerr_url
                        if (k == KEY_SEERR_API_KEY && userSeerrUrl == null && effectiveDeviceOverrodeSeerr) {
                            continue
                        }
                        val prior = staging.readRaw(k)
                        if (staging.applyCompanionPreference(k, v)) {
                            nextUserOverrideSnapshots[k] = prior
                        }
                    }
                }
            }

            // Unconditionally commit staged diff + metadata + session keys in ONE synchronous commit
            appPreferences.commitMainBatch { editor ->
                staging.writeTo(editor)
                editor.putString(
                    appPreferences.companionPreferenceBaselineJson.backendName,
                    encodeStringMap(nextBaseline),
                )
                editor.putString(
                    appPreferences.preBaselineLocalSnapshotsJson.backendName,
                    encodeNullableStringMap(preBaselineLocalSnapshots),
                )
                editor.putString(
                    appPreferences.appliedUserOverrideSnapshotsJson.backendName,
                    encodeNullableStringMap(nextUserOverrideSnapshots),
                )
                editor.putBoolean(
                    appPreferences.companionDeviceOverrodeSeerrUrl.backendName,
                    effectiveDeviceOverrodeSeerr,
                )
                when (currentServerIdUpdate) {
                    CurrentServerUpdate.Unchanged -> Unit
                    CurrentServerUpdate.Clear -> editor.remove(appPreferences.currentServer.backendName)
                    is CurrentServerUpdate.Set -> editor.putString(
                        appPreferences.currentServer.backendName,
                        currentServerIdUpdate.serverId,
                    )
                }
                if (standaloneModeUpdate != null) {
                    editor.putBoolean(appPreferences.standaloneModeChosen.backendName, standaloneModeUpdate)
                }
                if (companionCredentials != null) {
                    editor.putString(appPreferences.companionUrl.backendName, companionCredentials.url)
                    editor.putString(appPreferences.companionToken.backendName, companionCredentials.token)
                }
                if (dedicatedDigestsJson != null) {
                    editor.putString(appPreferences.dedicatedAccessTokenDigestsJson.backendName, dedicatedDigestsJson)
                }
                if (revokedDigestsJson != null) {
                    editor.putString(appPreferences.revokedDownloadTokenDigestsJson.backendName, revokedDigestsJson)
                }
                if (clearPendingSyncPlayForUser != null &&
                    appPreferences.getValue(appPreferences.pendingSyncPlayLeaveUserId) == clearPendingSyncPlayForUser.toString()
                ) {
                    editor.remove(appPreferences.pendingSyncPlayLeaveServerId.backendName)
                    editor.remove(appPreferences.pendingSyncPlayLeaveUserId.backendName)
                }
                if (clearPendingSyncPlayForServer != null &&
                    appPreferences.getValue(appPreferences.pendingSyncPlayLeaveServerId) == clearPendingSyncPlayForServer
                ) {
                    editor.remove(appPreferences.pendingSyncPlayLeaveServerId.backendName)
                    editor.remove(appPreferences.pendingSyncPlayLeaveUserId.backendName)
                }
                if (clearedPendingMigrationShellIds.isNotEmpty()) {
                    val remaining = decodePendingServerIdMigrations().toMutableMap()
                    var removedAny = false
                    for (shellId in clearedPendingMigrationShellIds) {
                        if (remaining.remove(shellId) != null) {
                            removedAny = true
                        }
                    }
                    if (removedAny) {
                        editor.putString(
                            appPreferences.pendingServerIdMigrationsJson.backendName,
                            encodeStringMap(remaining),
                        )
                    }
                }
            }
        }
    }

    private fun collectShellIds(
        canonicalServerId: String,
        explicitLegacyIds: List<String>,
        candidateAddresses: List<String>,
        userIds: List<UUID>,
    ): Set<String> {
        val shells = linkedSetOf<String>()
        explicitLegacyIds.forEach { id ->
            if (id.isNotBlank() && id != canonicalServerId) {
                if (id.startsWith("srv-") || database.get(id) != null) {
                    if (id.startsWith("srv-")) shells.add(id)
                }
            }
        }
        val normalizedCandidates = candidateAddresses.map { normalizeTrailingSlash(it).lowercase() }.toSet()
        for (server in database.getAllServersSync()) {
            if (server.id == canonicalServerId || !server.id.startsWith("srv-")) continue
            val serverAddresses = database.getServerWithAddresses(server.id)?.addresses.orEmpty()
            if (serverAddresses.any { normalizeTrailingSlash(it.address).lowercase() in normalizedCandidates }) {
                shells.add(server.id)
            }
        }
        for (uid in userIds) {
            val existingUserServerId = database.getUser(uid)?.serverId
            if (existingUserServerId != null &&
                existingUserServerId.startsWith("srv-") &&
                existingUserServerId != canonicalServerId
            ) {
                shells.add(existingUserServerId)
            }
        }
        return shells
    }

    private fun upsertValidatedShares(shares: List<NetworkShareDto>) {
        if (shares.isEmpty()) return
        val existingShares = database.getNetworkShares()
        for (incoming in shares) {
            val existing = existingShares.firstOrNull {
                it.protocol.equals(incoming.protocol, ignoreCase = true) &&
                    it.host.equals(incoming.host, ignoreCase = true) &&
                    it.shareName.equals(incoming.shareName, ignoreCase = true)
            }
            if (existing != null) {
                database.updateNetworkShare(
                    existing.copy(
                        username = incoming.username ?: existing.username,
                        password = incoming.password ?: existing.password,
                        domain = incoming.domain ?: existing.domain,
                        displayName = incoming.displayName ?: existing.displayName,
                    ),
                )
            } else {
                database.insertNetworkShare(incoming)
            }
        }
    }

    private fun cancelIntegrityWork() {
        runCatching { workManager.get().cancelUniqueWork(DownloadIntegrityWorker.UNIQUE_WORK_NAME) }
    }

    private fun cancelSyncAndIntegrityWork() {
        val wm = runCatching { workManager.get() }.getOrNull() ?: return
        runCatching { wm.cancelUniqueWork("syncUserData") }
        runCatching { wm.cancelUniqueWork("sync-on-reconnect") }
        runCatching { wm.cancelUniqueWork("metadata-refresh-on-reconnect") }
        runCatching { wm.cancelUniqueWork(DownloadIntegrityWorker.UNIQUE_WORK_NAME) }
    }

    private suspend fun bestEffortRemoteLogout(baseUrl: String, accessToken: String) {
        runCatching {
            val api = jellyfinApi.createApi(baseUrl = baseUrl, accessToken = accessToken)
            api.sessionApi.reportSessionEnded()
        }.onFailure {
            if (it is CancellationException) throw it
            Timber.d(it, "Best-effort remote session logout failed")
        }
    }

    private fun sweepTempPlaybackCaches() {
        runCatching {
            val cacheDir = context.cacheDir
            cacheDir.listFiles()?.forEach { file ->
                if (file.isFile && TRANSCODE_OFFLINE_REGEX.matches(file.name)) {
                    file.delete()
                }
            }
            File(cacheDir, "subtitle_ai").takeIf { it.exists() }?.deleteRecursively()
        }.onFailure { Timber.w(it, "Failed to sweep temp playback caches") }
    }

    private fun decodeDedicatedTokenDigests(): MutableSet<String> {
        val raw = appPreferences.getValue(appPreferences.dedicatedAccessTokenDigestsJson) ?: return mutableSetOf()
        return runCatching {
            json.decodeFromString(SetSerializer(String.serializer()), raw).toMutableSet()
        }.getOrDefault(mutableSetOf())
    }

    private fun updateDedicatedTokenDigests(
        newToken: String,
        isDedicatedToken: Boolean,
    ): Set<String> {
        val digests = decodeDedicatedTokenDigests()
        if (isDedicatedToken) {
            digests.add(sha256Hex(newToken))
        }
        return pruneDedicatedTokenDigests(digests)
    }

    private fun pruneDedicatedTokenDigests(
        source: MutableSet<String> = decodeDedicatedTokenDigests(),
    ): Set<String> {
        val activeTokenDigests = database.getAllServersSync()
            .flatMap { srv -> database.getServerWithUsers(srv.id)?.users.orEmpty() }
            .mapNotNull { u -> u.accessToken?.takeIf { it.isNotBlank() } }
            .map(::sha256Hex)
            .toSet()
        return source.filter { it in activeTokenDigests }.toSet()
    }

    private fun decodePendingServerIdMigrations(): Map<String, String> =
        decodeStringMap(appPreferences.getValue(appPreferences.pendingServerIdMigrationsJson))

    private fun stagePendingServerIdMigrationsLocked(
        shellIds: Set<String>,
        canonicalServerId: String,
    ) {
        if (shellIds.isEmpty()) return
        appPreferences.withAppLockPolicyLock {
            val merged = decodePendingServerIdMigrations().toMutableMap()
            var changed = false
            for (shellId in shellIds) {
                if (shellId.isNotBlank() && shellId != canonicalServerId && merged[shellId] != canonicalServerId) {
                    merged[shellId] = canonicalServerId
                    changed = true
                }
            }
            if (changed) {
                appPreferences.commitMainBatch { editor ->
                    editor.putString(
                        appPreferences.pendingServerIdMigrationsJson.backendName,
                        encodeStringMap(merged),
                    )
                }
            }
        }
    }

    private fun decodeRevokedDownloadTokenDigests(): Map<String, String> =
        decodeStringMap(appPreferences.getValue(appPreferences.revokedDownloadTokenDigestsJson))

    private fun collectRevokedDigestsForScopes(
        map: Map<String, String>,
        serverIds: Set<String>,
        userIds: Set<UUID>,
    ): Set<String> {
        val out = mutableSetOf<String>()
        for (sid in serverIds) {
            for (uid in userIds) {
                map["$sid:$uid"]?.let(out::add)
            }
        }
        return out
    }

    private fun pruneRevokedDigests(
        map: MutableMap<String, String>,
        serverIds: Set<String>,
        userIds: Set<UUID>,
    ) {
        for (sid in serverIds) {
            for (uid in userIds) {
                map.remove("$sid:$uid")
            }
        }
    }

    private fun decodeStringMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), raw)
        }.getOrDefault(emptyMap())
    }

    private fun encodeStringMap(map: Map<String, String>): String =
        json.encodeToString(MapSerializer(String.serializer(), String.serializer()), map)

    private fun decodeNullableStringMap(raw: String?): Map<String, String?> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString(MapSerializer(String.serializer(), String.serializer().nullable), raw)
        }.getOrDefault(emptyMap())
    }

    private fun encodeNullableStringMap(map: Map<String, String?>): String =
        json.encodeToString(MapSerializer(String.serializer(), String.serializer().nullable), map)

    private fun encodeStringSet(set: Set<String>): String =
        json.encodeToString(ListSerializer(String.serializer()), set.toList())

    companion object {
        private const val MIN_COMMIT_REMAINING_MS = 500L
        private const val SERVER_DELETE_CLEANUP_WAIT_TIMEOUT_MS = 10_000L
        private const val KEY_SEERR_URL = "pref_seerr_url"
        private const val KEY_SEERR_API_KEY = "pref_seerr_api_key"
        private val TRANSCODE_OFFLINE_REGEX = Regex("^transcode_offline_\\d+\\.mp4$")

        private val EXPLICIT_ACTIVATION_REASONS = setOf(
            SessionChangeReason.MANUAL_LOGIN,
            SessionChangeReason.QUICK_CONNECT,
            SessionChangeReason.LAN_SETUP_PAGE,
            SessionChangeReason.COMPANION_HANDOFF,
            SessionChangeReason.COMPANION_QR,
            SessionChangeReason.SERVER_SWITCH,
            SessionChangeReason.USER_SWITCH,
            SessionChangeReason.STANDALONE_ONBOARDING,
        )

        private val STANDALONE_ACTIVATION_REASONS = setOf(
            SessionChangeReason.STANDALONE_ONBOARDING,
            SessionChangeReason.COMPANION_HANDOFF,
            SessionChangeReason.COMPANION_QR,
        )

        internal fun normalizeTrailingSlash(url: String): String {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return trimmed
            return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        }

        internal fun normalizeUrlForComparison(url: String?): String =
            url?.trim()?.trimEnd('/')?.lowercase().orEmpty()

        internal fun sha256Hex(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
