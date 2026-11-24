package dev.jdtech.jellyfin.setup.lan

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.companion.CompanionConfigImporter
import dev.jdtech.jellyfin.companion.CompanionImportOutcome
import dev.jdtech.jellyfin.companion.ImportMode
import dev.jdtech.jellyfin.companion.SelectedCompanionUser
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.companion.CompanionConfig
import dev.jdtech.jellyfin.models.companion.CompanionEndpoint
import dev.jdtech.jellyfin.models.companion.CompanionTvPairingEnvelope
import dev.jdtech.jellyfin.models.normalizeJellyfinId
import dev.jdtech.jellyfin.models.parseJellyfinUuid
import dev.jdtech.jellyfin.network.SafeUrlBlockedException
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.onboarding.OnboardingFailureException
import dev.jdtech.jellyfin.onboarding.toOnboardingFailureCode
import dev.jdtech.jellyfin.quickconnect.QuickConnectClient
import dev.jdtech.jellyfin.quickconnect.QuickConnectInitiated
import dev.jdtech.jellyfin.session.ActivationResult
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionChangeReason
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import dev.jdtech.jellyfin.setup.quickconnect.QuickConnectController
import dev.jdtech.jellyfin.setup.quickconnect.QuickConnectState
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.jellyfin.sdk.model.api.AuthenticationResult
import timber.log.Timber

@Singleton
class LanSetupController internal constructor(
    private val context: Context,
    private val companionConfigImporter: CompanionConfigImporter,
    private val setupRepository: SetupRepository,
    private val quickConnectClient: QuickConnectClient,
    private val quickConnectControllerFactory: QuickConnectController.Factory,
    private val sessionActivationCoordinator: SessionActivationCoordinator,
    private val appLockObserver: AppLockObserver,
    private val elapsedRealtimeClock: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    private val addressSupplier: (() -> List<InetAddress>)? = null,
    private val preferredPort: Int = LanSetupServer.DEFAULT_PORT,
    private val enableNsdAndOffers: Boolean = true,
    private val setupPageHtmlProvider: (() -> String)? = null,
    private val deviceNameOverride: String? = null,
    private val deviceClassOverride: String? = null,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        companionConfigImporter: CompanionConfigImporter,
        setupRepository: SetupRepository,
        quickConnectClient: QuickConnectClient,
        quickConnectControllerFactory: QuickConnectController.Factory,
        sessionActivationCoordinator: SessionActivationCoordinator,
        appLockObserver: AppLockObserver,
    ) : this(
        context = context,
        companionConfigImporter = companionConfigImporter,
        setupRepository = setupRepository,
        quickConnectClient = quickConnectClient,
        quickConnectControllerFactory = quickConnectControllerFactory,
        sessionActivationCoordinator = sessionActivationCoordinator,
        appLockObserver = appLockObserver,
        elapsedRealtimeClock = { SystemClock.elapsedRealtime() },
        wallClock = { System.currentTimeMillis() },
        addressSupplier = null,
        preferredPort = LanSetupServer.DEFAULT_PORT,
        enableNsdAndOffers = true,
        setupPageHtmlProvider = null,
        deviceNameOverride = null,
        deviceClassOverride = null,
    )

    private data class ActiveSetupSession(
        val sessionId: String,
        val serverId: String,
        val serverName: String,
        val serverAddress: String,
        var stage: SetupStage,
        var quickConnectController: QuickConnectController? = null,
        var quickConnectCollectorJob: Job? = null,
        var quickConnectCode: String? = null,
        var quickConnectRotation: Int? = null,
        var errorMessage: String? = null,
        var errorCode: String? = null,
    )

    private data class ArchivedStatusEntry(
        val response: SetupStatusResponse,
        val archivedElapsedMs: Long,
    )

    private data class PendingUserSelectionState(
        val config: CompanionConfig,
        val endpoint: CompanionEndpoint,
        val policy: UrlTargetPolicy,
        val candidates: List<dev.jdtech.jellyfin.companion.CompanionCandidate>,
        val createdElapsedMs: Long,
    )

    private data class InFlightHandoff(
        val credentialDigest: ByteArray,
        val setupTokenDigest: ByteArray,
        val companionUrl: String,
        val serverId: String?,
        val userId: String?,
        val shareOnly: Boolean,
        val deferred: CompletableDeferred<LanSetupHttpResult<CompanionHandoffResponse>>,
        val job: Job,
    ) {
        fun matches(
            otherCred: ByteArray,
            otherToken: ByteArray,
            otherUrl: String,
            otherServerId: String?,
            otherUserId: String?,
            otherShareOnly: Boolean,
        ): Boolean =
            MessageDigest.isEqual(credentialDigest, otherCred) &&
                MessageDigest.isEqual(setupTokenDigest, otherToken) &&
                companionUrl == otherUrl &&
                serverId == otherServerId &&
                userId == otherUserId &&
                shareOnly == otherShareOnly
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val stateLock = ReentrantLock()
    private val secureRandom = SecureRandom()

    private val deviceName: String =
        deviceNameOverride ?: Build.MODEL?.takeIf { it.isNotBlank() } ?: "SpatialFin"
    private val deviceClass: String =
        deviceClassOverride ?: "android"

    private val _uiState = MutableStateFlow(LanSetupUiState())
    val uiState: StateFlow<LanSetupUiState> = _uiState.asStateFlow()

    private val _pendingHandoffSelection = MutableStateFlow<PendingHandoffUserSelection?>(null)
    val pendingHandoffSelection: StateFlow<PendingHandoffUserSelection?> =
        _pendingHandoffSelection.asStateFlow()

    private val _navigationEvents = Channel<LanSetupNavigationEvent>(Channel.BUFFERED)
    val navigationEvents: Flow<LanSetupNavigationEvent> = _navigationEvents.receiveAsFlow()

    // Guarded by stateLock
    private val activeLeases = LinkedHashMap<UUID, LanSetupMode>()
    private var sessionGeneration: Long = 0L
    private var activeCheckToken: UUID? = null
    private var activeActivationToken: UUID? = null
    private var activeHandoff: InFlightHandoff? = null
    private var activeManualLoginJob: Job? = null
    private var activeSetupSession: ActiveSetupSession? = null
    private var pendingUserSelectionState: PendingUserSelectionState? = null
    private var isCompletingUserSelection: Boolean = false
    private var lastErrorCode: OnboardingFailureCode? = null
    private var lastQuickConnectState: QuickConnectState = QuickConnectState.Idle
    private var undeliveredFallbackNavigation: Boolean = false
    private var pendingFallbackTeardownBump: Boolean = false
    private var standbyExtensionUntilElapsedMs: Long = 0L
    private val statusHistory = LinkedHashMap<String, ArchivedStatusEntry>()

    // Timers & standby locks (guarded by stateLock)
    private var expiryTimerJob: Job? = null
    private var drainTimerJob: Job? = null
    private var graceTeardownJob: Job? = null
    private var pendingSelectionExpiryJob: Job? = null
    private var standbyWakeLock: PowerManager.WakeLock? = null
    private var standbyWifiLock: WifiManager.WifiLock? = null

    internal val server: LanSetupServer =
        LanSetupServer(
            context = context,
            deviceName = deviceName,
            deviceClass = deviceClass,
            callbacks = object : LanSetupServerCallbacks {
                override suspend fun onServerSubmitted(
                    serverUrl: String,
                    capturedSecretsEpoch: Long,
                    usedManualCode: Boolean,
                    deadlineElapsedMs: Long,
                ): LanSetupHttpResult<ServerSubmitResponse> =
                    handleServerSubmitted(serverUrl, capturedSecretsEpoch, usedManualCode, deadlineElapsedMs)

                override suspend fun onCompanionHandoff(
                    request: CompanionHandoffRequest,
                    credentialDigest: ByteArray,
                    capturedSecretsEpoch: Long,
                    usedManualCode: Boolean,
                    deadlineElapsedMs: Long,
                ): LanSetupHttpResult<CompanionHandoffResponse> =
                    handleCompanionHandoff(request, credentialDigest, capturedSecretsEpoch, usedManualCode, deadlineElapsedMs)

                override suspend fun onPairingEnvelope(
                    envelope: CompanionTvPairingEnvelope,
                    credentialDigest: ByteArray,
                    capturedSecretsEpoch: Long,
                    usedManualCode: Boolean,
                    deadlineElapsedMs: Long,
                ): LanSetupHttpResult<CompanionHandoffResponse> =
                    handlePairingEnvelope(envelope, credentialDigest, capturedSecretsEpoch, usedManualCode, deadlineElapsedMs)

                override suspend fun onCancelSession(
                    sessionId: String?,
                    force: Boolean,
                    capturedSecretsEpoch: Long,
                    usedManualCode: Boolean,
                ): LanSetupHttpResult<CancelSessionResponse> =
                    handleCancelSession(sessionId, force, capturedSecretsEpoch, usedManualCode)

                override fun onStatusRequested(sessionId: String): SetupStatusResponse? =
                    handleStatusRequested(sessionId)

                override fun onWatchRejected() = handleWatchRejected()
            },
            stateLock = stateLock,
            isAppLocked = { isAppLocked() },
            addressSupplier = addressSupplier ?: {
                dev.jdtech.jellyfin.network.PhysicalLanAddressResolver.resolvePhysicalLanAddresses(
                    context.getSystemService(android.net.ConnectivityManager::class.java),
                    includeVpn = false,
                )
            },
            elapsedRealtimeClock = elapsedRealtimeClock,
            wallClock = wallClock,
            preferredPort = preferredPort,
            enableNsdAndOffers = enableNsdAndOffers,
            setupPageHtmlProvider = setupPageHtmlProvider ?: { LanSetupServer.loadDefaultSetupHtml(context) },
        )

    init {
        scope.launch {
            server.state.collect { snapshot ->
                publishUiState(snapshot)
            }
        }
        scope.launch {
            appLockObserver.lockState.collect { lockState ->
                if (lockState == AppLockManager.LockState.LOCKED) {
                    handleAppLocked()
                }
            }
        }
    }

    private fun isAppLocked(): Boolean =
        appLockObserver.lockState.value == AppLockManager.LockState.LOCKED

    private fun acquireStandbyLocksLocked(durationMs: Long) {
        releaseStandbyLocksLocked()
        val lockTimeoutMs = (durationMs + 5_000L).coerceIn(1_000L, 120_000L)
        runCatching {
            val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            standbyWakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpatialFin:LanSetupStandby")?.apply {
                setReferenceCounted(false)
                acquire(lockTimeoutMs)
            }
        }
        runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            standbyWifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "SpatialFin:LanSetupStandby")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseStandbyLocksLocked() {
        runCatching {
            if (standbyWakeLock?.isHeld == true) standbyWakeLock?.release()
        }
        standbyWakeLock = null
        runCatching {
            if (standbyWifiLock?.isHeld == true) standbyWifiLock?.release()
        }
        standbyWifiLock = null
    }

    internal fun hasStandbyLocksHeldForTests(): Boolean =
        stateLock.withLock { standbyWakeLock?.isHeld == true || standbyWifiLock?.isHeld == true }

    internal fun hasActiveExclusiveOperationLocked(): Boolean =
        activeCheckToken != null ||
            activeActivationToken != null ||
            activeHandoff != null ||
            activeManualLoginJob != null ||
            pendingUserSelectionState != null ||
            activeSetupSession?.stage == SetupStage.ACTIVATING

    private fun updateExpiryFrozenLocked() {
        val frozen = hasActiveExclusiveOperationLocked() || activeSetupSession != null
        server.setExpiryFrozen(frozen)
        if (!frozen && server.isRunningLocked() && !server.isCompletedDrainActiveLocked()) {
            val now = elapsedRealtimeClock()
            if (server.isActiveSecretsExpiredLocked(now)) {
                handleExpiryTriggeredLocked()
            } else {
                scheduleExpiryTimerLocked()
            }
        } else if (server.isRunningLocked() && !server.isCompletedDrainActiveLocked()) {
            scheduleExpiryTimerLocked()
        }
    }

    fun acquire(mode: LanSetupMode, clearCompletedDrain: Boolean = false): LeaseHandle {
        val handle = LeaseHandle(id = UUID.randomUUID(), mode = mode)
        var shouldStart = false
        var enableReceiverAdvertising = false
        var shouldClearDrain = false
        stateLock.withLock {
            if (isAppLocked()) {
                return handle
            }
            activeLeases[handle.id] = mode
            graceTeardownJob?.cancel()
            graceTeardownJob = null
            releaseStandbyLocksLocked()
            enableReceiverAdvertising = activeLeases.values.any { it == LanSetupMode.COMPANION_RECEIVER }
            if (clearCompletedDrain && server.isCompletedDrainActiveLocked()) {
                shouldClearDrain = true
                drainTimerJob?.cancel()
                drainTimerJob = null
                if (!undeliveredFallbackNavigation && pendingFallbackTeardownBump) {
                    sessionGeneration++
                    pendingFallbackTeardownBump = false
                }
            }
            if (!server.isRunningLocked()) {
                shouldStart = true
            }
        }
        if (shouldStart) {
            server.start(enableReceiverAdvertising = enableReceiverAdvertising)
            stateLock.withLock {
                scheduleExpiryTimerLocked()
            }
        } else {
            if (shouldClearDrain) {
                server.clearCompletedDrain()
                stateLock.withLock { scheduleExpiryTimerLocked() }
            }
            server.setReceiverAdvertisingEnabled(enableReceiverAdvertising)
        }
        publishUiState(server.state.value)
        return handle
    }

    fun release(lease: LeaseHandle, graceMs: Long) {
        var enableReceiverAdvertising = false
        var shouldUpdateAdvertising = false
        var shouldStopNow = false
        var scheduleGraceDelayMs: Long? = null

        stateLock.withLock {
            activeLeases.remove(lease.id)
            enableReceiverAdvertising = activeLeases.values.any { it == LanSetupMode.COMPANION_RECEIVER }
            if (activeLeases.isEmpty()) {
                val now = elapsedRealtimeClock()
                val clampedGrace = graceMs.coerceAtLeast(0L)
                if (clampedGrace == 0L && canTearDownServerLocked()) {
                    shouldStopNow = true
                } else {
                    standbyExtensionUntilElapsedMs = maxOf(standbyExtensionUntilElapsedMs, now + clampedGrace)
                    val delay = (standbyExtensionUntilElapsedMs - now).coerceAtLeast(0L)
                    scheduleGraceDelayMs = delay
                    if (delay > 0L && server.isRunningLocked()) {
                        acquireStandbyLocksLocked(delay)
                    }
                }
            } else {
                shouldUpdateAdvertising = true
            }
        }

        if (shouldStopNow) {
            stopServerAndResetUnactivated()
            return
        }
        if (shouldUpdateAdvertising) {
            server.setReceiverAdvertisingEnabled(enableReceiverAdvertising)
        }
        if (scheduleGraceDelayMs != null) {
            scheduleGraceTeardown(scheduleGraceDelayMs)
        }
    }

    private fun canTearDownServerLocked(): Boolean =
        activeLeases.isEmpty() &&
            !hasActiveExclusiveOperationLocked() &&
            activeSetupSession == null &&
            !server.isCompletedDrainActiveLocked()

    private fun scheduleGraceTeardown(delayMs: Long) {
        stateLock.withLock {
            graceTeardownJob?.cancel()
            graceTeardownJob =
                scope.launch {
                    if (delayMs > 0L) delay(delayMs)
                    val shouldStop =
                        stateLock.withLock {
                            val now = elapsedRealtimeClock()
                            if (now < standbyExtensionUntilElapsedMs && activeLeases.isEmpty()) {
                                false
                            } else {
                                val tearDown = canTearDownServerLocked()
                                if (!tearDown && (!server.isRunningLocked() || activeLeases.isNotEmpty())) {
                                    releaseStandbyLocksLocked()
                                }
                                tearDown
                            }
                        }
                    if (shouldStop) {
                        stopServerAndResetUnactivated()
                    }
                }
        }
    }

    private fun maybeTearDownIfIdle() {
        val shouldStop =
            stateLock.withLock {
                val now = elapsedRealtimeClock()
                canTearDownServerLocked() && now >= standbyExtensionUntilElapsedMs
            }
        if (shouldStop) {
            stopServerAndResetUnactivated()
        }
    }

    private fun stopServerAndResetUnactivated() {
        var qcToCancel: QuickConnectController? = null
        var collectorToCancel: Job? = null
        stateLock.withLock {
            releaseStandbyLocksLocked()
            expiryTimerJob?.cancel()
            expiryTimerJob = null
            drainTimerJob?.cancel()
            drainTimerJob = null
            graceTeardownJob?.cancel()
            graceTeardownJob = null
            pendingSelectionExpiryJob?.cancel()
            pendingSelectionExpiryJob = null
            qcToCancel = activeSetupSession?.quickConnectController
            collectorToCancel = activeSetupSession?.quickConnectCollectorJob
            activeSetupSession = null
            lastQuickConnectState = QuickConnectState.Idle
            statusHistory.clear()
        }
        collectorToCancel?.cancel()
        qcToCancel?.cancel()
        server.stop()
        publishUiState(server.state.value)
    }

    private fun handleAppLocked() {
        var qcToCancel: QuickConnectController? = null
        var collectorToCancel: Job? = null
        var manualJobToCancel: Job? = null
        var handoffToCancel: InFlightHandoff? = null
        val shouldStop: Boolean

        stateLock.withLock {
            releaseStandbyLocksLocked()
            sessionGeneration++
            undeliveredFallbackNavigation = false
            pendingFallbackTeardownBump = false
            expiryTimerJob?.cancel()
            expiryTimerJob = null
            drainTimerJob?.cancel()
            drainTimerJob = null
            graceTeardownJob?.cancel()
            graceTeardownJob = null
            pendingSelectionExpiryJob?.cancel()
            pendingSelectionExpiryJob = null
            activeCheckToken = null
            if (activeActivationToken == null) {
                manualJobToCancel = activeManualLoginJob
                activeManualLoginJob = null
            }
            handoffToCancel = activeHandoff
            activeHandoff = null
            pendingUserSelectionState = null
            isCompletingUserSelection = false
            _pendingHandoffSelection.value = null

            activeSetupSession?.let { sess ->
                sess.stage = SetupStage.FAILED
                sess.errorCode = OnboardingFailureCode.PAIRING_CANCELLED.wireCode
                sess.errorMessage = "Device locked"
                qcToCancel = sess.quickConnectController
                collectorToCancel = sess.quickConnectCollectorJob
                activeSetupSession = null
            }
            lastQuickConnectState = QuickConnectState.Idle
            lastErrorCode = null
            statusHistory.clear()
            server.consumeActiveSecretsLocked()
            shouldStop = server.isRunningLocked()
        }

        collectorToCancel?.cancel()
        qcToCancel?.cancel()
        manualJobToCancel?.cancel()
        handoffToCancel?.let { h ->
            h.deferred.complete(LanSetupHttpResult.Error(409, "pairing_cancelled", "Device is locked"))
            if (stateLock.withLock { activeActivationToken == null }) {
                h.job.cancel()
            }
        }
        if (shouldStop) {
            server.stop()
        }
        publishUiState(server.state.value)
    }

    fun consumeNavigationEventIfCurrent(event: LanSetupNavigationEvent): Boolean =
        stateLock.withLock {
            if (isAppLocked()) return@withLock false
            if (event.sessionGeneration != sessionGeneration) return@withLock false
            if (event is LanSetupNavigationEvent.NavigateToLogin && event.lanSetupSessionId == null) {
                undeliveredFallbackNavigation = false
                if (pendingFallbackTeardownBump && !server.isCompletedDrainActiveLocked()) {
                    sessionGeneration++
                    pendingFallbackTeardownBump = false
                }
            }
            true
        }

    fun isActiveSession(lanSetupSessionId: String): Boolean =
        stateLock.withLock {
            activeSetupSession?.sessionId == lanSetupSessionId
        }

    fun clearCompletedDrainOnFallbackBack() {
        var shouldClear = false
        stateLock.withLock {
            undeliveredFallbackNavigation = false
            pendingFallbackTeardownBump = false
            sessionGeneration++
            if (server.isCompletedDrainActiveLocked()) {
                drainTimerJob?.cancel()
                drainTimerJob = null
                shouldClear = true
            }
        }
        if (shouldClear) {
            server.clearCompletedDrain()
            stateLock.withLock { scheduleExpiryTimerLocked() }
        }
        maybeTearDownIfIdle()
        publishUiState(server.state.value)
    }

    fun regenerateManualCode() {
        stateLock.withLock {
            if (isAppLocked() || !server.isRunningLocked()) return
            drainTimerJob?.cancel()
            drainTimerJob = null
            lastErrorCode = null
        }
        server.regenerateManualCode()
        stateLock.withLock {
            scheduleExpiryTimerLocked()
        }
        publishUiState(server.state.value)
    }

    fun cancelQuickConnect(lanSetupSessionId: String) {
        var qcToCancel: QuickConnectController? = null
        var collectorToCancel: Job? = null
        stateLock.withLock {
            val sess = activeSetupSession ?: return
            if (sess.sessionId != lanSetupSessionId) return
            qcToCancel = sess.quickConnectController
            collectorToCancel = sess.quickConnectCollectorJob
            sess.quickConnectController = null
            sess.quickConnectCollectorJob = null
            sess.quickConnectCode = null
            sess.stage = SetupStage.AWAITING_DEVICE_LOGIN
            lastQuickConnectState = QuickConnectState.Idle
        }
        collectorToCancel?.cancel()
        qcToCancel?.cancel()
        publishUiState(server.state.value)
    }

    fun retryQuickConnect(lanSetupSessionId: String) {
        val sessSnapshot: Triple<String, String, String>?
        var oldQc: QuickConnectController? = null
        var oldCollector: Job? = null
        var newQc: QuickConnectController? = null

        stateLock.withLock {
            val sess = activeSetupSession ?: return
            if (sess.sessionId != lanSetupSessionId || isAppLocked()) return
            if (sess.stage == SetupStage.ACTIVATING || sess.stage == SetupStage.DONE) return
            oldQc = sess.quickConnectController
            oldCollector = sess.quickConnectCollectorJob
            val qc = quickConnectControllerFactory.create(
                savedStateHandle = null,
                scope = scope,
                keyPrefix = "${sess.serverId}|${sess.serverAddress}",
            )
            sess.quickConnectController = qc
            sess.stage = SetupStage.WAITING_QUICK_CONNECT
            sess.errorCode = null
            sess.errorMessage = null
            newQc = qc
            sessSnapshot = Triple(sess.sessionId, sess.serverId, sess.serverAddress)
            sess.quickConnectCollectorJob = launchQuickConnectObserverLocked(sess.sessionId, qc)
        }
        oldCollector?.cancel()
        oldQc?.cancel()
        if (newQc != null && sessSnapshot != null) {
            newQc.start(sessSnapshot.third)
        }
        publishUiState(server.state.value)
    }

    fun cancelSessionFromDevice(lanSetupSessionId: String) {
        var qcToCancel: QuickConnectController? = null
        var collectorToCancel: Job? = null
        stateLock.withLock {
            val sess = activeSetupSession ?: return
            if (sess.sessionId != lanSetupSessionId) return
            if (sess.stage == SetupStage.ACTIVATING) return
            qcToCancel = sess.quickConnectController
            collectorToCancel = sess.quickConnectCollectorJob
            sess.stage = SetupStage.FAILED
            sess.errorCode = OnboardingFailureCode.PAIRING_CANCELLED.wireCode
            sess.errorMessage = "Cancelled on device"
            archiveSessionLocked(sess)
            activeSetupSession = null
            lastQuickConnectState = QuickConnectState.Idle
            sessionGeneration++
            updateExpiryFrozenLocked()
        }
        collectorToCancel?.cancel()
        qcToCancel?.cancel()
        maybeTearDownIfIdle()
        publishUiState(server.state.value)
    }

    suspend fun performManualLogin(
        lanSetupSessionId: String,
        serverId: String,
        serverName: String,
        serverAddress: String,
        username: String,
        password: String,
    ): ActivationResult {
        val currentJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        val preCheckError: OnboardingFailureCode? =
            stateLock.withLock {
                if (isAppLocked()) {
                    OnboardingFailureCode.PAIRING_CANCELLED
                } else if (activeSetupSession?.sessionId != lanSetupSessionId) {
                    OnboardingFailureCode.SESSION_NOT_FOUND
                } else if (activeActivationToken != null || activeManualLoginJob != null) {
                    OnboardingFailureCode.PAIRING_SESSION_ACTIVE
                } else {
                    activeManualLoginJob = currentJob
                    updateExpiryFrozenLocked()
                    null
                }
            }
        if (preCheckError != null) {
            return ActivationResult.Failed(preCheckError)
        }

        val claimedToken = UUID.randomUUID()
        var claimed = false
        try {
            val result =
                setupRepository.login(
                    serverId = serverId,
                    serverName = serverName,
                    serverAddress = serverAddress,
                    username = username,
                    password = password,
                    beforeCommit = {
                        stateLock.withLock {
                            if (isAppLocked() || activeSetupSession?.sessionId != lanSetupSessionId) {
                                false
                            } else {
                                activeActivationToken = claimedToken
                                claimed = true
                                activeSetupSession?.stage = SetupStage.ACTIVATING
                                true
                            }
                        }
                    },
                )
            if (result is ActivationResult.Activated) {
                onLanSessionActivated(lanSetupSessionId)
            } else if (claimed) {
                stateLock.withLock {
                    if (activeSetupSession?.sessionId == lanSetupSessionId) {
                        activeSetupSession?.stage = SetupStage.AWAITING_DEVICE_LOGIN
                    }
                }
            }
            return result
        } finally {
            stateLock.withLock {
                if (activeManualLoginJob === currentJob) {
                    activeManualLoginJob = null
                }
                if (claimed && activeActivationToken == claimedToken) {
                    activeActivationToken = null
                }
                updateExpiryFrozenLocked()
            }
            maybeTearDownIfIdle()
            publishUiState(server.state.value)
        }
    }

    fun completePendingUserSelection(serverId: String, userId: UUID) {
        val pending: PendingUserSelectionState =
            stateLock.withLock {
                if (isAppLocked()) return
                if (isCompletingUserSelection) return
                val current = pendingUserSelectionState ?: return
                isCompletingUserSelection = true
                lastErrorCode = null
                current
            }
        publishUiState(server.state.value)

        scope.launch {
            val token = UUID.randomUUID()
            var claimed = false
            try {
                val outcome =
                    companionConfigImporter.completeUserSelection(
                        config = pending.config,
                        endpoint = pending.endpoint,
                        policy = pending.policy,
                        serverId = serverId,
                        userId = userId,
                        beforeCommit = {
                            stateLock.withLock {
                                if (isAppLocked() || pendingUserSelectionState !== pending) {
                                    false
                                } else {
                                    activeActivationToken = token
                                    claimed = true
                                    true
                                }
                            }
                        },
                    )
                when (outcome) {
                    is CompanionImportOutcome.Activated -> {
                        stateLock.withLock {
                            pendingSelectionExpiryJob?.cancel()
                            pendingSelectionExpiryJob = null
                            pendingUserSelectionState = null
                            isCompletingUserSelection = false
                            _pendingHandoffSelection.value = null
                            enterDrainAndScheduleLocked(DrainOutcome.ACTIVATED, null)
                        }
                        server.clearDiscoveryAndOffersOffLock()
                        server.republishSnapshot(republishNsd = false)
                    }
                    is CompanionImportOutcome.BrokerFallbackToLogin -> {
                        val navGen: Long
                        stateLock.withLock {
                            pendingSelectionExpiryJob?.cancel()
                            pendingSelectionExpiryJob = null
                            pendingUserSelectionState = null
                            isCompletingUserSelection = false
                            _pendingHandoffSelection.value = null
                            sessionGeneration++
                            navGen = sessionGeneration
                            undeliveredFallbackNavigation = true
                            enterDrainAndScheduleLocked(DrainOutcome.FALLBACK_TO_LOGIN, null)
                        }
                        server.clearDiscoveryAndOffersOffLock()
                        server.republishSnapshot(republishNsd = false)
                        _navigationEvents.trySend(
                            LanSetupNavigationEvent.NavigateToLogin(
                                serverId = outcome.serverId,
                                serverName = outcome.serverName,
                                serverAddress = outcome.serverAddress,
                                prefilledUsername = outcome.prefilledUsername,
                                lanSetupSessionId = null,
                                initialFailureCode = outcome.failureCode,
                                sessionGeneration = navGen,
                            ),
                        )
                    }
                    is CompanionImportOutcome.Failed -> {
                        stateLock.withLock {
                            isCompletingUserSelection = false
                            lastErrorCode = outcome.code
                        }
                    }
                    is CompanionImportOutcome.NeedsUserSelection -> {
                        stateLock.withLock {
                            isCompletingUserSelection = false
                        }
                    }
                }
            } finally {
                stateLock.withLock {
                    isCompletingUserSelection = false
                    if (claimed && activeActivationToken == token) {
                        activeActivationToken = null
                    }
                    updateExpiryFrozenLocked()
                }
                maybeTearDownIfIdle()
                publishUiState(server.state.value)
            }
        }
    }

    fun cancelPendingUserSelection() {
        stateLock.withLock {
            pendingSelectionExpiryJob?.cancel()
            pendingSelectionExpiryJob = null
            pendingUserSelectionState = null
            isCompletingUserSelection = false
            _pendingHandoffSelection.value = null
            lastErrorCode = null
            updateExpiryFrozenLocked()
        }
        maybeTearDownIfIdle()
        publishUiState(server.state.value)
    }

    fun clearError() {
        stateLock.withLock { lastErrorCode = null }
        publishUiState(server.state.value)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // LanSetupServerCallbacks implementations
    // ──────────────────────────────────────────────────────────────────────────

    private suspend fun handleServerSubmitted(
        serverUrl: String,
        capturedSecretsEpoch: Long,
        usedManualCode: Boolean,
        deadlineElapsedMs: Long,
    ): LanSetupHttpResult<ServerSubmitResponse> {
        val checkToken = UUID.randomUUID()
        val acquireError: LanSetupHttpResult.Error? =
            stateLock.withLock {
                when (server.checkSecretsValidLocked(capturedSecretsEpoch, usedManualCode, elapsedRealtimeClock())) {
                    SecretCheckResult.REJECTED ->
                        return@withLock LanSetupHttpResult.Error(409, "pairing_cancelled", "Pairing cancelled")
                    SecretCheckResult.EXPIRED ->
                        return@withLock LanSetupHttpResult.Error(409, "pairing_code_expired", "Pairing code has expired")
                    SecretCheckResult.VALID -> Unit
                }
                if (hasActiveExclusiveOperationLocked()) {
                    return@withLock LanSetupHttpResult.Error(409, "pairing_session_active", "Another setup request is in progress")
                }
                activeCheckToken = checkToken
                updateExpiryFrozenLocked()
                null
            }
        if (acquireError != null) return acquireError

        try {
            val remainingForProbe = (deadlineElapsedMs - elapsedRealtimeClock() - 2_500L).coerceIn(1_000L, 8_000L)
            if (deadlineElapsedMs - elapsedRealtimeClock() <= 1_000L) {
                return LanSetupHttpResult.Error(504, "pairing_timeout", "Server probe timed out")
            }
            val probed =
                try {
                    setupRepository.probeServer(serverUrl, timeoutMs = remainingForProbe)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OnboardingFailureException) {
                    return mapFailureCodeToHttpError(e.code, e.message)
                } catch (e: Throwable) {
                    return mapFailureCodeToHttpError(e.toOnboardingFailureCode(), e.message)
                }

            // Phase 2: Quick Connect checkEnabled + initiate within remaining deadline
            val qcRemaining = deadlineElapsedMs - elapsedRealtimeClock()
            if (qcRemaining <= 0L) {
                return LanSetupHttpResult.Error(504, "pairing_timeout", "Timed out checking Quick Connect")
            }
            var initiated: QuickConnectInitiated? = null
            var qcDisabled = false
            var qcFailureCode: OnboardingFailureCode? = null

            try {
                withTimeout(qcRemaining) {
                    val enabled = quickConnectClient.checkEnabled(probed.serverAddress)
                    if (!enabled) {
                        qcDisabled = true
                    } else {
                        initiated = quickConnectClient.initiate(probed.serverAddress)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                return LanSetupHttpResult.Error(504, "pairing_timeout", "Timed out initiating Quick Connect")
            } catch (e: CancellationException) {
                throw e
            } catch (e: OnboardingFailureException) {
                if (e.code == OnboardingFailureCode.QC_DISABLED_ON_SERVER) {
                    qcDisabled = true
                } else {
                    qcFailureCode = e.code
                }
            } catch (e: Throwable) {
                qcFailureCode = e.toOnboardingFailureCode()
            }

            if (elapsedRealtimeClock() >= deadlineElapsedMs) {
                return LanSetupHttpResult.Error(504, "pairing_timeout", "Setup request timed out")
            }

            // Phase 2 commit under shared ReentrantLock
            val newSessionId = generateSessionId()
            var oldQcToCancel: QuickConnectController? = null
            var oldCollectorToCancel: Job? = null
            var createdQc: QuickConnectController? = null
            val navGen: Long

            val commitError: LanSetupHttpResult.Error? =
                stateLock.withLock {
                    if (isAppLocked()) {
                        return@withLock LanSetupHttpResult.Error(409, "pairing_cancelled", "Device is locked")
                    }
                    if (activeCheckToken != checkToken) {
                        return@withLock LanSetupHttpResult.Error(409, "pairing_cancelled", "Setup request superseded")
                    }
                    when (server.checkSecretsValidLocked(capturedSecretsEpoch, usedManualCode, elapsedRealtimeClock())) {
                        SecretCheckResult.REJECTED ->
                            return@withLock LanSetupHttpResult.Error(409, "pairing_cancelled", "Pairing cancelled")
                        SecretCheckResult.EXPIRED ->
                            return@withLock LanSetupHttpResult.Error(409, "pairing_code_expired", "Pairing code has expired")
                        SecretCheckResult.VALID -> Unit
                    }

                    // Archive any superseded unactivated session
                    activeSetupSession?.let { old ->
                        old.stage = SetupStage.FAILED
                        old.errorCode = OnboardingFailureCode.PAIRING_CANCELLED.wireCode
                        old.errorMessage = "Superseded by new setup request"
                        archiveSessionLocked(old)
                        oldQcToCancel = old.quickConnectController
                        oldCollectorToCancel = old.quickConnectCollectorJob
                    }

                    val initialStage =
                        if (initiated != null) SetupStage.WAITING_QUICK_CONNECT else SetupStage.AWAITING_DEVICE_LOGIN
                    val sess =
                        ActiveSetupSession(
                            sessionId = newSessionId,
                            serverId = probed.serverId,
                            serverName = probed.serverName,
                            serverAddress = probed.serverAddress,
                            stage = initialStage,
                            quickConnectCode = initiated?.code,
                            quickConnectRotation = if (initiated != null) 0 else null,
                        )

                    if (initiated != null) {
                        val qc = quickConnectControllerFactory.create(
                            savedStateHandle = null,
                            scope = scope,
                            keyPrefix = "${probed.serverId}|${probed.serverAddress}",
                        )
                        sess.quickConnectController = qc
                        sess.quickConnectCollectorJob = launchQuickConnectObserverLocked(newSessionId, qc)
                        createdQc = qc
                        lastQuickConnectState = QuickConnectState.Active(
                            code = initiated.code,
                            secret = initiated.secret,
                            rotation = 0,
                            startedAtEpochMs = wallClock(),
                        )
                    } else if (qcDisabled) {
                        lastQuickConnectState = QuickConnectState.DisabledOnServer
                    } else {
                        lastQuickConnectState = QuickConnectState.Failed(
                            qcFailureCode ?: OnboardingFailureCode.SERVER_HTTP_ERROR,
                        )
                    }

                    activeSetupSession = sess
                    sessionGeneration++
                    navGen = sessionGeneration
                    return@withLock null
                }

            oldCollectorToCancel?.cancel()
            oldQcToCancel?.cancel()

            if (commitError != null) {
                return commitError
            }

            // Re-check under ReentrantLock right before calling startWithInitiated
            if (createdQc != null && initiated != null) {
                val stillOwned =
                    stateLock.withLock {
                        !isAppLocked() && activeSetupSession?.sessionId == newSessionId
                    }
                if (stillOwned) {
                    createdQc.startWithInitiated(probed.serverAddress, initiated)
                } else {
                    createdQc.cancel()
                }
            }

            _navigationEvents.trySend(
                LanSetupNavigationEvent.NavigateToLogin(
                    serverId = probed.serverId,
                    serverName = probed.serverName,
                    serverAddress = probed.serverAddress,
                    prefilledUsername = null,
                    lanSetupSessionId = newSessionId,
                    initialFailureCode = null,
                    sessionGeneration = stateLock.withLock { sessionGeneration },
                ),
            )

            publishUiState(server.state.value)

            val responseStatus =
                when {
                    initiated != null -> "waiting_quick_connect"
                    qcDisabled -> "quick_connect_disabled"
                    else -> "awaiting_device_login"
                }
            return LanSetupHttpResult.Ok(
                ServerSubmitResponse(
                    status = responseStatus,
                    sessionId = newSessionId,
                    serverName = probed.serverName,
                    serverUrl = probed.serverAddress,
                    quickConnect = initiated != null,
                    quickConnectCode = initiated?.code,
                ),
            )
        } finally {
            stateLock.withLock {
                if (activeCheckToken == checkToken) {
                    activeCheckToken = null
                }
                updateExpiryFrozenLocked()
            }
            maybeTearDownIfIdle()
            publishUiState(server.state.value)
        }
    }

    private fun launchQuickConnectObserverLocked(
        sessionId: String,
        qc: QuickConnectController,
    ): Job =
        scope.launch {
            qc.state.collect { qcState ->
                var authToActivate: Triple<String, String, String>? = null
                var authResult: AuthenticationResult? = null
                stateLock.withLock {
                    val sess = activeSetupSession ?: return@collect
                    if (sess.sessionId != sessionId) return@collect
                    lastQuickConnectState = qcState
                    when (qcState) {
                        is QuickConnectState.Active -> {
                            sess.stage = SetupStage.WAITING_QUICK_CONNECT
                            sess.quickConnectCode = qcState.code
                            sess.quickConnectRotation = qcState.rotation
                        }
                        QuickConnectState.DisabledOnServer,
                        QuickConnectState.Expired,
                        is QuickConnectState.Failed,
                        -> {
                            if (sess.stage != SetupStage.ACTIVATING && sess.stage != SetupStage.DONE) {
                                sess.stage = SetupStage.AWAITING_DEVICE_LOGIN
                                sess.quickConnectCode = null
                            }
                        }
                        is QuickConnectState.Authenticated -> {
                            if (!isAppLocked() && activeActivationToken == null) {
                                sess.stage = SetupStage.ACTIVATING
                                authToActivate = Triple(sess.serverId, sess.serverName, sess.serverAddress)
                                authResult = qcState.result
                            }
                        }
                        QuickConnectState.Idle,
                        QuickConnectState.Checking,
                        -> Unit
                    }
                }
                publishUiState(server.state.value)
                if (authToActivate != null && authResult != null) {
                    activateFromLanQuickConnect(
                        sessionId = sessionId,
                        serverId = authToActivate.first,
                        serverName = authToActivate.second,
                        serverAddress = authToActivate.third,
                        authResult = authResult,
                    )
                }
            }
        }

    private suspend fun activateFromLanQuickConnect(
        sessionId: String,
        serverId: String,
        serverName: String,
        serverAddress: String,
        authResult: AuthenticationResult,
    ) {
        val token = UUID.randomUUID()
        val claimed =
            stateLock.withLock {
                if (isAppLocked() || activeSetupSession?.sessionId != sessionId || activeActivationToken != null) {
                    false
                } else {
                    activeActivationToken = token
                    activeSetupSession?.stage = SetupStage.ACTIVATING
                    updateExpiryFrozenLocked()
                    true
                }
            }
        if (!claimed) return

        try {
            val returnedServerId = authResult.serverId?.trim()?.takeIf { it.isNotEmpty() }
            val userDto = authResult.user
            val accessToken = authResult.accessToken?.takeIf { it.isNotBlank() }
            val isLegacy = serverId.startsWith("srv-")

            if (returnedServerId == null ||
                (!isLegacy && normalizeJellyfinId(returnedServerId) != normalizeJellyfinId(serverId))
            ) {
                failLanQuickConnectActivation(sessionId, OnboardingFailureCode.SERVER_IDENTITY_MISMATCH)
                return
            }
            if (userDto == null || accessToken == null) {
                failLanQuickConnectActivation(sessionId, OnboardingFailureCode.AUTH_INVALID_CREDENTIALS)
                return
            }

            val canonicalServerId = if (isLegacy) returnedServerId else serverId
            val user =
                User(
                    id = userDto.id,
                    name = userDto.name?.takeIf { it.isNotBlank() } ?: userDto.id.toString(),
                    serverId = canonicalServerId,
                    accessToken = accessToken,
                )
            val activation =
                sessionActivationCoordinator.activateServer(
                    serverId = canonicalServerId,
                    addressUrl = serverAddress,
                    reason = SessionChangeReason.LAN_SETUP_PAGE,
                    user = user,
                    serverName = serverName,
                    legacyServerIds = if (isLegacy) listOf(serverId) else emptyList(),
                    isDedicatedToken = true,
                    overwriteUserPreferences = false,
                )
            when (activation) {
                is ActivationResult.Activated -> onLanSessionActivated(sessionId)
                is ActivationResult.Failed -> failLanQuickConnectActivation(sessionId, activation.code)
                is ActivationResult.NeedsAuth ->
                    failLanQuickConnectActivation(sessionId, OnboardingFailureCode.AUTH_INVALID_CREDENTIALS)
            }
        } finally {
            stateLock.withLock {
                if (activeActivationToken == token) {
                    activeActivationToken = null
                }
                updateExpiryFrozenLocked()
            }
            maybeTearDownIfIdle()
            publishUiState(server.state.value)
        }
    }

    private fun failLanQuickConnectActivation(sessionId: String, code: OnboardingFailureCode) {
        stateLock.withLock {
            val sess = activeSetupSession
            if (sess != null && sess.sessionId == sessionId) {
                sess.stage = SetupStage.AWAITING_DEVICE_LOGIN
                sess.quickConnectCode = null
                sess.errorCode = code.wireCode
                lastQuickConnectState = QuickConnectState.Failed(code)
            }
            lastErrorCode = code
        }
    }

    private fun onLanSessionActivated(sessionId: String) {
        var qcToCancel: QuickConnectController? = null
        var collectorToCancel: Job? = null
        stateLock.withLock {
            val sess = activeSetupSession
            if (sess != null && sess.sessionId == sessionId) {
                sess.stage = SetupStage.DONE
                sess.quickConnectCode = null
                archiveSessionLocked(sess)
                qcToCancel = sess.quickConnectController
                collectorToCancel = sess.quickConnectCollectorJob
                activeSetupSession = null
            }
            lastQuickConnectState = QuickConnectState.Idle
            sessionGeneration++
            enterDrainAndScheduleLocked(DrainOutcome.ACTIVATED, null)
        }
        collectorToCancel?.cancel()
        qcToCancel?.cancel()
        server.clearDiscoveryAndOffersOffLock()
        server.republishSnapshot(republishNsd = false)
        publishUiState(server.state.value)
    }

    private suspend fun handleCompanionHandoff(
        request: CompanionHandoffRequest,
        credentialDigest: ByteArray,
        capturedSecretsEpoch: Long,
        usedManualCode: Boolean,
        deadlineElapsedMs: Long,
    ): LanSetupHttpResult<CompanionHandoffResponse> {
        val normalizedUrl = request.companionUrl.trim().trimEnd('/')
        val serverId = request.serverId?.trim()?.takeIf { it.isNotEmpty() }
        val userIdStr = request.userId?.trim()?.takeIf { it.isNotEmpty() }
        val parsedUserId = if (userIdStr != null) parseJellyfinUuid(userIdStr) else null
        if (userIdStr != null && parsedUserId == null) {
            return LanSetupHttpResult.Error(400, "invalid_request", "Invalid user_id format")
        }
        val endpoint =
            try {
                CompanionEndpoint.parse(normalizedUrl, request.setupToken)
            } catch (e: SafeUrlBlockedException) {
                return LanSetupHttpResult.Error(400, "ssrf_blocked", e.message ?: "Companion URL blocked")
            } catch (e: IllegalArgumentException) {
                return LanSetupHttpResult.Error(400, "ssrf_blocked", e.message ?: "Invalid companion URL")
            }

        return dispatchCoalescedHandoff(
            credentialDigest = credentialDigest,
            setupTokenDigest = LanSetupServer.sha256(request.setupToken),
            companionUrl = normalizedUrl,
            serverId = serverId,
            userId = userIdStr,
            shareOnly = request.shareOnly,
            capturedSecretsEpoch = capturedSecretsEpoch,
            usedManualCode = usedManualCode,
        ) { beforeCommit ->
            val selectedUser =
                if (serverId != null && parsedUserId != null) {
                    SelectedCompanionUser(serverId = serverId, userId = parsedUserId)
                } else {
                    null
                }
            companionConfigImporter.importFromEndpoint(
                endpoint = endpoint,
                mode = ImportMode.PAIRING,
                policy = UrlTargetPolicy.PRIVATE_LAN_ONLY,
                selectedUser = selectedUser,
                shareOnly = request.shareOnly,
                handoffDeadlineElapsedMs = deadlineElapsedMs,
                beforeCommit = beforeCommit,
            )
        }
    }

    private suspend fun handlePairingEnvelope(
        envelope: CompanionTvPairingEnvelope,
        credentialDigest: ByteArray,
        capturedSecretsEpoch: Long,
        usedManualCode: Boolean,
        deadlineElapsedMs: Long,
    ): LanSetupHttpResult<CompanionHandoffResponse> {
        val normalizedUrl = envelope.companion_url.trim().trimEnd('/')
        val serverId = envelope.server_id?.trim()?.takeIf { it.isNotEmpty() }
        val userIdStr = envelope.user_id?.trim()?.takeIf { it.isNotEmpty() }
        if (userIdStr != null && parseJellyfinUuid(userIdStr) == null) {
            return LanSetupHttpResult.Error(400, "invalid_request", "Invalid user_id format")
        }
        val shareOnly = envelope.config.servers.isEmpty()

        return dispatchCoalescedHandoff(
            credentialDigest = credentialDigest,
            setupTokenDigest = LanSetupServer.sha256(envelope.config.setup_token),
            companionUrl = normalizedUrl,
            serverId = serverId,
            userId = userIdStr,
            shareOnly = shareOnly,
            capturedSecretsEpoch = capturedSecretsEpoch,
            usedManualCode = usedManualCode,
        ) { beforeCommit ->
            companionConfigImporter.importFromEnvelope(
                envelope = envelope,
                handoffDeadlineElapsedMs = deadlineElapsedMs,
                beforeCommit = beforeCommit,
            )
        }
    }

    private suspend fun dispatchCoalescedHandoff(
        credentialDigest: ByteArray,
        setupTokenDigest: ByteArray,
        companionUrl: String,
        serverId: String?,
        userId: String?,
        shareOnly: Boolean,
        capturedSecretsEpoch: Long,
        usedManualCode: Boolean,
        block: suspend (beforeCommit: suspend () -> Boolean) -> CompanionImportOutcome,
    ): LanSetupHttpResult<CompanionHandoffResponse> {
        var existingDeferred: CompletableDeferred<LanSetupHttpResult<CompanionHandoffResponse>>? = null
        var createdDeferred: CompletableDeferred<LanSetupHttpResult<CompanionHandoffResponse>>? = null
        var supersededQc: QuickConnectController? = null
        var supersededCollector: Job? = null
        var immediateError: LanSetupHttpResult.Error? = null

        stateLock.withLock {
            when (server.checkSecretsValidLocked(capturedSecretsEpoch, usedManualCode, elapsedRealtimeClock())) {
                SecretCheckResult.REJECTED -> {
                    immediateError = LanSetupHttpResult.Error(409, "pairing_cancelled", "Pairing cancelled")
                    return@withLock
                }
                SecretCheckResult.EXPIRED -> {
                    immediateError = LanSetupHttpResult.Error(409, "pairing_code_expired", "Pairing code has expired")
                    return@withLock
                }
                SecretCheckResult.VALID -> Unit
            }

            // Coalesce identical in-flight handoff
            val current = activeHandoff
            if (current != null) {
                if (current.matches(credentialDigest, setupTokenDigest, companionUrl, serverId, userId, shareOnly)) {
                    existingDeferred = current.deferred
                } else {
                    immediateError = LanSetupHttpResult.Error(409, "pairing_session_active", "Another handoff is in progress")
                }
                return@withLock
            }

            if (hasActiveExclusiveOperationLocked()) {
                immediateError = LanSetupHttpResult.Error(409, "pairing_session_active", "Another setup request is in progress")
                return@withLock
            }

            // Supersede any unactivated /setup session
            activeSetupSession?.let { old ->
                old.stage = SetupStage.FAILED
                old.errorCode = OnboardingFailureCode.PAIRING_CANCELLED.wireCode
                old.errorMessage = "Superseded by Companion handoff"
                archiveSessionLocked(old)
                supersededQc = old.quickConnectController
                supersededCollector = old.quickConnectCollectorJob
                activeSetupSession = null
                lastQuickConnectState = QuickConnectState.Idle
            }

            val deferred = CompletableDeferred<LanSetupHttpResult<CompanionHandoffResponse>>()
            val activationToken = UUID.randomUUID()
            val job =
                scope.launch {
                    var claimedActivation = false
                    val httpResult: LanSetupHttpResult<CompanionHandoffResponse> =
                        try {
                            val outcome =
                                block {
                                    stateLock.withLock {
                                        val check = server.checkSecretsValidLocked(
                                            capturedSecretsEpoch,
                                            usedManualCode,
                                            elapsedRealtimeClock(),
                                        )
                                        if (check != SecretCheckResult.VALID || isAppLocked()) {
                                            false
                                        } else {
                                            activeActivationToken = activationToken
                                            claimedActivation = true
                                            true
                                        }
                                    }
                                }
                            handleCompanionImportOutcome(
                                outcome = outcome,
                                credentialDigest = credentialDigest,
                                setupTokenDigest = setupTokenDigest,
                                companionUrl = companionUrl,
                                serverId = serverId,
                                userId = userId,
                                shareOnly = shareOnly,
                            )
                        } catch (e: CancellationException) {
                            LanSetupHttpResult.Error(409, "pairing_cancelled", "Pairing cancelled")
                        } catch (e: Throwable) {
                            mapFailureCodeToHttpError(e.toOnboardingFailureCode(), e.message)
                        } finally {
                            stateLock.withLock {
                                if (activeHandoff?.deferred === deferred) {
                                    activeHandoff = null
                                }
                                if (claimedActivation && activeActivationToken == activationToken) {
                                    activeActivationToken = null
                                }
                                updateExpiryFrozenLocked()
                            }
                            maybeTearDownIfIdle()
                            publishUiState(server.state.value)
                        }
                    deferred.complete(httpResult)
                }

            activeHandoff =
                InFlightHandoff(
                    credentialDigest = credentialDigest,
                    setupTokenDigest = setupTokenDigest,
                    companionUrl = companionUrl,
                    serverId = serverId,
                    userId = userId,
                    shareOnly = shareOnly,
                    deferred = deferred,
                    job = job,
                )
            createdDeferred = deferred
            updateExpiryFrozenLocked()
        }

        supersededCollector?.cancel()
        supersededQc?.cancel()

        if (immediateError != null) return immediateError
        val deferredToAwait = existingDeferred ?: createdDeferred
            ?: error("Handoff deferred missing without immediate error")
        return deferredToAwait.await()
    }

    private fun handleCompanionImportOutcome(
        outcome: CompanionImportOutcome,
        credentialDigest: ByteArray,
        setupTokenDigest: ByteArray,
        companionUrl: String,
        serverId: String?,
        userId: String?,
        shareOnly: Boolean,
    ): LanSetupHttpResult<CompanionHandoffResponse> {
        return when (outcome) {
            is CompanionImportOutcome.Activated -> {
                val body =
                    CompanionHandoffResponse(
                        ok = true,
                        status = "done",
                        deviceName = deviceName,
                        serverUrl = outcome.serverUrl,
                    )
                val replay =
                    HandoffReplayRecord(
                        credentialDigest = credentialDigest,
                        setupTokenDigest = setupTokenDigest,
                        companionUrl = companionUrl,
                        serverId = serverId,
                        userId = userId,
                        shareOnly = shareOnly,
                        response = body,
                    )
                stateLock.withLock {
                    sessionGeneration++
                    enterDrainAndScheduleLocked(DrainOutcome.ACTIVATED, replay)
                }
                server.clearDiscoveryAndOffersOffLock()
                server.republishSnapshot(republishNsd = false)
                LanSetupHttpResult.Ok(body)
            }

            is CompanionImportOutcome.BrokerFallbackToLogin -> {
                val body =
                    CompanionHandoffResponse(
                        ok = true,
                        status = "fallback_to_login",
                        deviceName = deviceName,
                        serverUrl = outcome.serverAddress,
                        prefilledUsername = outcome.prefilledUsername,
                        errorCode = outcome.failureCode.wireCode,
                    )
                val replay =
                    HandoffReplayRecord(
                        credentialDigest = credentialDigest,
                        setupTokenDigest = setupTokenDigest,
                        companionUrl = companionUrl,
                        serverId = serverId,
                        userId = userId,
                        shareOnly = shareOnly,
                        response = body,
                    )
                val navGen: Long
                stateLock.withLock {
                    sessionGeneration++
                    navGen = sessionGeneration
                    undeliveredFallbackNavigation = true
                    enterDrainAndScheduleLocked(DrainOutcome.FALLBACK_TO_LOGIN, replay)
                }
                server.clearDiscoveryAndOffersOffLock()
                server.republishSnapshot(republishNsd = false)
                _navigationEvents.trySend(
                    LanSetupNavigationEvent.NavigateToLogin(
                        serverId = outcome.serverId,
                        serverName = outcome.serverName,
                        serverAddress = outcome.serverAddress,
                        prefilledUsername = outcome.prefilledUsername,
                        lanSetupSessionId = null,
                        initialFailureCode = outcome.failureCode,
                        sessionGeneration = navGen,
                    ),
                )
                LanSetupHttpResult.Ok(body)
            }

            is CompanionImportOutcome.NeedsUserSelection -> {
                val navGen: Long
                val shouldEmitNav: Boolean
                stateLock.withLock {
                    val now = elapsedRealtimeClock()
                    pendingUserSelectionState =
                        PendingUserSelectionState(
                            config = outcome.config,
                            endpoint = outcome.endpoint,
                            policy = outcome.policy,
                            candidates = outcome.candidates,
                            createdElapsedMs = now,
                        )
                    _pendingHandoffSelection.value = PendingHandoffUserSelection(outcome.candidates)
                    schedulePendingSelectionExpiryLocked()
                    sessionGeneration++
                    navGen = sessionGeneration
                    shouldEmitNav = activeLeases.values.none { it == LanSetupMode.COMPANION_RECEIVER }
                }
                if (shouldEmitNav) {
                    _navigationEvents.trySend(LanSetupNavigationEvent.NavigateToSetUpFromDevice(navGen))
                }
                LanSetupHttpResult.Ok(
                    CompanionHandoffResponse(
                        ok = true,
                        status = "awaiting_user_selection",
                        deviceName = deviceName,
                    ),
                )
            }

            is CompanionImportOutcome.Failed -> {
                stateLock.withLock { lastErrorCode = outcome.code }
                mapFailureCodeToHttpError(outcome.code, outcome.message)
            }
        }
    }

    private fun handleCancelSession(
        sessionId: String?,
        force: Boolean,
        capturedSecretsEpoch: Long,
        usedManualCode: Boolean,
    ): LanSetupHttpResult<CancelSessionResponse> {
        var qcToCancel: QuickConnectController? = null
        var collectorToCancel: Job? = null
        var navGenToReturn: Long? = null

        val result: LanSetupHttpResult<CancelSessionResponse> =
            stateLock.withLock {
                when (server.checkSecretsValidLocked(capturedSecretsEpoch, usedManualCode, elapsedRealtimeClock())) {
                    SecretCheckResult.REJECTED ->
                        return@withLock LanSetupHttpResult.Error(409, "pairing_cancelled", "Pairing cancelled")
                    SecretCheckResult.EXPIRED ->
                        return@withLock LanSetupHttpResult.Error(409, "pairing_code_expired", "Pairing code has expired")
                    SecretCheckResult.VALID -> Unit
                }

                if (force) {
                    if (activeActivationToken != null || activeHandoff != null || activeManualLoginJob != null ||
                        activeSetupSession?.stage == SetupStage.ACTIVATING
                    ) {
                        return@withLock LanSetupHttpResult.Error(409, "pairing_session_active", "Activation is in progress")
                    }
                    // Fence any in-flight probe checkToken
                    activeCheckToken = null
                    activeSetupSession?.let { sess ->
                        sess.stage = SetupStage.FAILED
                        sess.errorCode = OnboardingFailureCode.PAIRING_CANCELLED.wireCode
                        sess.errorMessage = "Cancelled from setup page"
                        archiveSessionLocked(sess)
                        qcToCancel = sess.quickConnectController
                        collectorToCancel = sess.quickConnectCollectorJob
                        activeSetupSession = null
                        lastQuickConnectState = QuickConnectState.Idle
                        sessionGeneration++
                        navGenToReturn = sessionGeneration
                    }
                    updateExpiryFrozenLocked()
                    return@withLock LanSetupHttpResult.Ok(CancelSessionResponse(cancelled = true))
                }

                if (hasActiveExclusiveOperationLocked()) {
                    return@withLock LanSetupHttpResult.Error(409, "pairing_session_active", "Another setup operation is active")
                }

                val current = activeSetupSession
                if (current == null || current.sessionId != sessionId) {
                    return@withLock LanSetupHttpResult.Error(404, "session_not_found", "Setup session not found")
                }

                current.stage = SetupStage.FAILED
                current.errorCode = OnboardingFailureCode.PAIRING_CANCELLED.wireCode
                current.errorMessage = "Cancelled from setup page"
                archiveSessionLocked(current)
                qcToCancel = current.quickConnectController
                collectorToCancel = current.quickConnectCollectorJob
                activeSetupSession = null
                lastQuickConnectState = QuickConnectState.Idle
                sessionGeneration++
                navGenToReturn = sessionGeneration
                updateExpiryFrozenLocked()
                LanSetupHttpResult.Ok(CancelSessionResponse(cancelled = true))
            }

        collectorToCancel?.cancel()
        qcToCancel?.cancel()
        navGenToReturn?.let { gen ->
            _navigationEvents.trySend(LanSetupNavigationEvent.ReturnToAddServer(gen))
        }
        maybeTearDownIfIdle()
        publishUiState(server.state.value)
        return result
    }

    private fun handleStatusRequested(sessionId: String): SetupStatusResponse? =
        stateLock.withLock {
            val active = activeSetupSession
            if (active != null && active.sessionId == sessionId) {
                return@withLock SetupStatusResponse(
                    sessionId = active.sessionId,
                    stage = active.stage,
                    serverUrl = active.serverAddress,
                    quickConnectCode = active.quickConnectCode,
                    quickConnectRotation = active.quickConnectRotation,
                    error = active.errorMessage,
                    errorCode = active.errorCode,
                )
            }
            pruneStatusHistoryLocked(elapsedRealtimeClock())
            statusHistory[sessionId]?.response
        }

    private fun handleWatchRejected() {
        var qcToCancel: QuickConnectController? = null
        var collectorToCancel: Job? = null
        stateLock.withLock {
            expiryTimerJob?.cancel()
            expiryTimerJob = null
            activeSetupSession?.let { sess ->
                if (sess.stage != SetupStage.ACTIVATING && sess.stage != SetupStage.DONE) {
                    sess.stage = SetupStage.FAILED
                    sess.errorCode = OnboardingFailureCode.PAIRING_CANCELLED.wireCode
                    sess.errorMessage = "Rejected on device"
                    archiveSessionLocked(sess)
                    qcToCancel = sess.quickConnectController
                    collectorToCancel = sess.quickConnectCollectorJob
                    activeSetupSession = null
                    lastQuickConnectState = QuickConnectState.Idle
                }
            }
            lastErrorCode = OnboardingFailureCode.PAIRING_CANCELLED
        }
        collectorToCancel?.cancel()
        qcToCancel?.cancel()
        publishUiState(server.state.value)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Timers & helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun enterDrainAndScheduleLocked(outcome: DrainOutcome, handoffReplay: HandoffReplayRecord?) {
        expiryTimerJob?.cancel()
        expiryTimerJob = null
        server.enterCompletedDrainLocked(outcome, handoffReplay)
        drainTimerJob?.cancel()
        drainTimerJob =
            scope.launch {
                delay(DRAIN_WINDOW_MS)
                var shouldStop = false
                stateLock.withLock {
                    if (!server.isCompletedDrainActiveLocked()) return@withLock
                    val drainOutcome = server.drainOutcomeLocked()
                    server.consumeActiveSecretsLocked()
                    if (drainOutcome == DrainOutcome.FALLBACK_TO_LOGIN) {
                        if (undeliveredFallbackNavigation) {
                            pendingFallbackTeardownBump = true
                        } else {
                            sessionGeneration++
                        }
                    } else {
                        sessionGeneration++
                    }
                    shouldStop = canTearDownServerLocked()
                }
                if (shouldStop) {
                    stopServerAndResetUnactivated()
                } else {
                    server.republishSnapshot(republishNsd = false)
                    publishUiState(server.state.value)
                }
            }
    }

    private fun scheduleExpiryTimerLocked() {
        expiryTimerJob?.cancel()
        expiryTimerJob = null
        if (!server.isRunningLocked() || server.isCompletedDrainActiveLocked()) return
        val (idleDeadline, hardCapDeadline) = server.currentExpiryDeadlinesLocked()
        val targetDeadline = if (server.isExpiryFrozenLocked()) hardCapDeadline else idleDeadline
        val delayMs = (targetDeadline - elapsedRealtimeClock()).coerceAtLeast(0L)
        expiryTimerJob =
            scope.launch {
                if (delayMs > 0L) delay(delayMs)
                var qcToCancel: QuickConnectController? = null
                var collectorToCancel: Job? = null
                val expired =
                    stateLock.withLock {
                        if (!server.isRunningLocked() || server.isCompletedDrainActiveLocked()) {
                            return@withLock false
                        }
                        val now = elapsedRealtimeClock()
                        if (!server.isActiveSecretsExpiredLocked(now)) {
                            scheduleExpiryTimerLocked()
                            return@withLock false
                        }
                        val (_, hardCap) = server.currentExpiryDeadlinesLocked()
                        server.markManualCodeExpiredLocked()
                        if (now >= hardCap && activeActivationToken == null) {
                            activeSetupSession?.let { sess ->
                                sess.stage = SetupStage.FAILED
                                sess.errorCode = OnboardingFailureCode.PAIRING_CODE_EXPIRED.wireCode
                                sess.errorMessage = "Setup code expired"
                                archiveSessionLocked(sess)
                                qcToCancel = sess.quickConnectController
                                collectorToCancel = sess.quickConnectCollectorJob
                                activeSetupSession = null
                                lastQuickConnectState = QuickConnectState.Idle
                            }
                        }
                        true
                    }
                collectorToCancel?.cancel()
                qcToCancel?.cancel()
                if (expired) {
                    server.clearDiscoveryAndOffersOffLock()
                    server.republishSnapshot(republishNsd = false)
                    publishUiState(server.state.value)
                }
            }
    }

    private fun handleExpiryTriggeredLocked() {
        server.markManualCodeExpiredLocked()
        scope.launch {
            server.clearDiscoveryAndOffersOffLock()
            server.republishSnapshot(republishNsd = false)
            publishUiState(server.state.value)
        }
    }

    private fun schedulePendingSelectionExpiryLocked() {
        pendingSelectionExpiryJob?.cancel()
        pendingSelectionExpiryJob =
            scope.launch {
                delay(PENDING_SELECTION_TTL_MS)
                val expired =
                    stateLock.withLock {
                        val pending = pendingUserSelectionState ?: return@withLock false
                        if (isCompletingUserSelection) return@withLock false
                        if (elapsedRealtimeClock() - pending.createdElapsedMs < PENDING_SELECTION_TTL_MS) {
                            return@withLock false
                        }
                        pendingUserSelectionState = null
                        _pendingHandoffSelection.value = null
                        updateExpiryFrozenLocked()
                        true
                    }
                if (expired) {
                    maybeTearDownIfIdle()
                    publishUiState(server.state.value)
                }
            }
    }

    private fun archiveSessionLocked(sess: ActiveSetupSession) {
        val now = elapsedRealtimeClock()
        pruneStatusHistoryLocked(now)
        while (statusHistory.size >= MAX_STATUS_HISTORY) {
            val oldestKey = statusHistory.entries.firstOrNull()?.key ?: break
            statusHistory.remove(oldestKey)
        }
        statusHistory[sess.sessionId] =
            ArchivedStatusEntry(
                response =
                    SetupStatusResponse(
                        sessionId = sess.sessionId,
                        stage = sess.stage,
                        serverUrl = sess.serverAddress,
                        quickConnectCode = sess.quickConnectCode,
                        quickConnectRotation = sess.quickConnectRotation,
                        error = sess.errorMessage,
                        errorCode = sess.errorCode,
                    ),
                archivedElapsedMs = now,
            )
    }

    private fun pruneStatusHistoryLocked(nowElapsedMs: Long) {
        val iter = statusHistory.entries.iterator()
        while (iter.hasNext()) {
            if (nowElapsedMs - iter.next().value.archivedElapsedMs >= STATUS_HISTORY_TTL_MS) {
                iter.remove()
            }
        }
    }

    private fun generateSessionId(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun mapFailureCodeToHttpError(
        code: OnboardingFailureCode,
        detail: String?,
    ): LanSetupHttpResult.Error {
        val msg = detail?.takeIf { it.isNotBlank() } ?: code.wireCode
        return when (code) {
            OnboardingFailureCode.URL_TIMEOUT -> LanSetupHttpResult.Error(504, "pairing_timeout", msg)
            OnboardingFailureCode.URL_SSRF_BLOCKED -> LanSetupHttpResult.Error(400, "ssrf_blocked", msg)
            OnboardingFailureCode.PAIRING_CANCELLED -> LanSetupHttpResult.Error(409, "pairing_cancelled", msg)
            OnboardingFailureCode.PAIRING_SESSION_ACTIVE -> LanSetupHttpResult.Error(409, "pairing_session_active", msg)
            OnboardingFailureCode.STORAGE_ERROR -> LanSetupHttpResult.Error(500, "storage_error", msg)
            else -> LanSetupHttpResult.Error(422, code.wireCode, msg)
        }
    }

    private fun publishUiState(snapshot: LanSetupServerSnapshot) {
        val ui =
            stateLock.withLock {
                LanSetupUiState(
                    isRunning = snapshot.isRunning,
                    localUrls = snapshot.localUrls,
                    setupUrl = snapshot.setupUrl,
                    setupQrUrl = snapshot.setupQrUrl,
                    receiverPairingQrJson = snapshot.receiverPairingQrJson,
                    formattedManualCode = snapshot.formattedManualCode,
                    expiresAtEpochMs = snapshot.expiresAtEpochMs,
                    manualCodeExpired = snapshot.manualCodeExpired,
                    nsdWarning = snapshot.nsdWarning,
                    activeSessionId = activeSetupSession?.sessionId,
                    stage = activeSetupSession?.stage,
                    quickConnectState = lastQuickConnectState,
                    isCompletingUserSelection = isCompletingUserSelection,
                    errorCode = lastErrorCode,
                )
            }
        _uiState.value = ui
    }

    internal fun shutdownForTests() {
        stopServerAndResetUnactivated()
    }

    companion object {
        const val DRAIN_WINDOW_MS: Long = 60_000L
        const val PENDING_SELECTION_TTL_MS: Long = 15L * 60_000L
        const val STATUS_HISTORY_TTL_MS: Long = 5L * 60_000L
        const val MAX_STATUS_HISTORY: Int = 32
        const val DEFAULT_RELEASE_GRACE_MS: Long = 3_000L
    }
}
