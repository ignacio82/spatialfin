package dev.jdtech.jellyfin.core.util

import android.app.Activity
import android.app.ActivityManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.security.ContentKeyManager
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.XrProcessPreferenceBridge
import dev.jdtech.jellyfin.settings.language.SeriesLanguageOverride
import dev.jdtech.jellyfin.settings.voice.VoiceTelemetryEntry
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.SystemModalGraceState
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber

sealed interface RegisterSessionResult {
    data class Registered(
        val authoritativeSessionEpoch: Long,
        val activeBaseUrl: String?,
    ) : RegisterSessionResult

    data object Rejected : RegisterSessionResult
}

sealed interface EphemeralDekResult {
    data object LockedRejected : EphemeralDekResult

    data class DekGranted(val dek: ByteArray) : EphemeralDekResult

    data object NotNeeded : EphemeralDekResult

    data object Unavailable : EphemeralDekResult
}

@Singleton
class XrPlayerSessionCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLockManager: dagger.Lazy<AppLockManager>,
    private val sessionActivationCoordinator: dagger.Lazy<SessionActivationCoordinator>,
    private val contentKeyManager: dagger.Lazy<ContentKeyManager>,
    private val systemModalGraceState: SystemModalGraceState,
    private val appPreferences: AppPreferences,
    private val xrProcessPreferenceBridge: dagger.Lazy<XrProcessPreferenceBridge>,
) {
    private data class ClientRegistration(
        val token: IBinder,
        val deathRecipient: IBinder.DeathRecipient,
        val pid: Int,
        var permissionPromptPending: Boolean = false,
    )

    private val lock = Any()
    private val startedMainActivities: MutableSet<Activity> =
        Collections.newSetFromMap(WeakHashMap())
    private val activeClients = LinkedHashMap<IBinder, ClientRegistration>()

    @Volatile
    private var pendingXrLaunchUntilElapsedMs: Long = 0L

    @Volatile
    private var modalWatchdogDeadlineElapsedMs: Long = 0L

    @Volatile
    private var pendingLockJob: Job? = null

    @Volatile
    private var launchTimeoutJob: Job? = null

    @Volatile
    private var modalWatchdogJob: Job? = null

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t ->
            Timber.w(t, "XrPlayerSessionCoordinator background job failed")
        },
    )

    val xrPlayerActive: Boolean
        get() = synchronized(lock) { activeClients.isNotEmpty() }

    init {
        if (appPreferences.isMainProcess) {
            scope.launch {
                var previousCount = systemModalGraceState.activeCount.value
                systemModalGraceState.activeCount.collectLatest { count ->
                    if (count > 0 && previousCount == 0) {
                        armModalWatchdog(MODAL_GRACE_MAX_MS)
                    } else if (count == 0 && previousCount > 0) {
                        // Shorten remaining watchdog deadline to a 2 s handoff window so
                        // onActivityResult arriving before onStart() never self-locks.
                        armModalWatchdog(HANDOFF_GRACE_MS)
                    }
                    previousCount = count
                }
            }
        }
    }

    fun onMainActivityStarted(activity: Activity) {
        if (!appPreferences.isMainProcess) return
        synchronized(lock) {
            startedMainActivities.add(activity)
            pendingLockJob?.cancel()
            pendingLockJob = null
        }
    }

    fun onMainActivityStopped(activity: Activity) {
        if (!appPreferences.isMainProcess) return
        if (activity.isChangingConfigurations) return
        val shouldEvaluate = synchronized(lock) {
            startedMainActivities.remove(activity)
            startedMainActivities.isEmpty()
        }
        if (shouldEvaluate) {
            evaluateAppBackgroundedLockedOrDeferred()
        }
    }

    fun notifyPendingLaunch(intent: Intent?): Boolean {
        if (intent == null) return false
        val component = intent.component ?: return false
        if (component.packageName != context.packageName) return false
        if (component.className !in XR_PLAYER_ACTIVITY_CLASSES) return false
        if (!isDeviceInteractive()) return false
        val deadline = SystemClock.elapsedRealtime() + PENDING_LAUNCH_GRACE_MS
        synchronized(lock) {
            pendingXrLaunchUntilElapsedMs = maxOf(pendingXrLaunchUntilElapsedMs, deadline)
            launchTimeoutJob?.cancel()
            launchTimeoutJob = scope.launch {
                delay(PENDING_LAUNCH_GRACE_MS)
                synchronized(lock) {
                    if (SystemClock.elapsedRealtime() >= pendingXrLaunchUntilElapsedMs) {
                        pendingXrLaunchUntilElapsedMs = 0L
                    }
                }
                evaluateAppBackgroundedLockedOrDeferred()
            }
        }
        return true
    }

    fun registerSession(clientToken: IBinder, callingPid: Int): RegisterSessionResult {
        if (!appPreferences.isMainProcess) return RegisterSessionResult.Rejected
        val lockManager = appLockManager.get()
        lockManager.refreshState()
        if (lockManager.lockState.value == AppLockManager.LockState.LOCKED) {
            return RegisterSessionResult.Rejected
        }
        val snapshot = runBlocking(Dispatchers.IO) {
            sessionActivationCoordinator.get().ensureInitialized()
        }
        val deathRecipient = IBinder.DeathRecipient {
            Timber.i("XrPlayerSessionCoordinator: :xrplayer client binder died (pid=%d)", callingPid)
            unregisterSession(clientToken, isFinishing = true)
        }
        val linked = runCatching {
            clientToken.linkToDeath(deathRecipient, 0)
            true
        }.getOrDefault(false)
        if (!linked) {
            return RegisterSessionResult.Rejected
        }
        synchronized(lock) {
            activeClients.remove(clientToken)?.let { prior ->
                runCatching { prior.token.unlinkToDeath(prior.deathRecipient, 0) }
            }
            activeClients[clientToken] = ClientRegistration(
                token = clientToken,
                deathRecipient = deathRecipient,
                pid = callingPid,
                permissionPromptPending = false,
            )
            pendingXrLaunchUntilElapsedMs = 0L
            pendingLockJob?.cancel()
            pendingLockJob = null
        }
        return RegisterSessionResult.Registered(
            authoritativeSessionEpoch = snapshot.sessionEpoch,
            activeBaseUrl = snapshot.activeServerAddress,
        )
    }

    fun unregisterSession(clientToken: IBinder, isFinishing: Boolean) {
        if (!appPreferences.isMainProcess) return
        val shouldScheduleGraceLock = synchronized(lock) {
            val removed = activeClients.remove(clientToken)
            if (removed != null) {
                runCatching { removed.token.unlinkToDeath(removed.deathRecipient, 0) }
            }
            activeClients.isEmpty() && startedMainActivities.isEmpty()
        }
        if (shouldScheduleGraceLock) {
            if (isFinishing) {
                scheduleDeferredLock(HANDOFF_GRACE_MS)
            } else {
                evaluateAppBackgroundedLockedOrDeferred()
            }
        }
    }

    fun requestEphemeralDek(clientToken: IBinder): EphemeralDekResult {
        if (!appPreferences.isMainProcess) return EphemeralDekResult.LockedRejected
        val lockManager = appLockManager.get()
        lockManager.refreshState()
        if (lockManager.lockState.value == AppLockManager.LockState.LOCKED) {
            return EphemeralDekResult.LockedRejected
        }
        val isRegistered = synchronized(lock) { activeClients.containsKey(clientToken) }
        if (!isRegistered) {
            return EphemeralDekResult.LockedRejected
        }
        val ckm = contentKeyManager.get()
        val storedMode = ckm.storedMode()
        if (!lockManager.isEnabled() &&
            !appPreferences.getValue(appPreferences.contentEncryptionEnabled) &&
            storedMode == ContentKeyManager.StoredDekMode.NONE
        ) {
            return EphemeralDekResult.NotNeeded
        }
        val dek = ckm.getDekOrNull()
        return if (dek != null) {
            EphemeralDekResult.DekGranted(dek)
        } else if (!lockManager.isEnabled() && storedMode == ContentKeyManager.StoredDekMode.NONE) {
            EphemeralDekResult.NotNeeded
        } else {
            EphemeralDekResult.Unavailable
        }
    }

    fun setPermissionPromptPending(clientToken: IBinder, pending: Boolean) {
        if (!appPreferences.isMainProcess) return
        synchronized(lock) {
            activeClients[clientToken]?.permissionPromptPending = pending
        }
        if (pending) {
            armModalWatchdog(MODAL_GRACE_MAX_MS)
        } else {
            armModalWatchdog(HANDOFF_GRACE_MS)
        }
    }

    fun notifyMainSessionUpdated(newSessionEpoch: Long, newBaseUrl: String?) {
        if (!appPreferences.isMainProcess) return
        val tokens = synchronized(lock) { activeClients.keys.toList() }
        for (token in tokens) {
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR_CLIENT)
                data.writeLong(newSessionEpoch)
                data.writeString(newBaseUrl)
                token.transact(TX_CLIENT_SESSION_UPDATED, data, null, IBinder.FLAG_ONEWAY)
            } catch (t: Throwable) {
                Timber.d(t, "Failed to push onMainSessionUpdated to :xrplayer client")
            } finally {
                data.recycle()
            }
        }
    }

    fun forceCloseAllXrClients(expectedEpoch: Long) {
        if (!appPreferences.isMainProcess) return
        val snapshot = synchronized(lock) { activeClients.values.toList() }
        if (snapshot.isEmpty()) return
        val targetPids = snapshot.map { it.pid }.filter { it > 0 && it != Process.myPid() }.toSet()
        for (reg in snapshot) {
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR_CLIENT)
                reg.token.transact(TX_CLIENT_FORCE_CLOSE, data, null, IBinder.FLAG_ONEWAY)
            } catch (t: Throwable) {
                Timber.d(t, "Failed to send onForceClose to :xrplayer client")
            } finally {
                data.recycle()
            }
        }
        if (targetPids.isNotEmpty()) {
            scope.launch {
                delay(FORCE_CLOSE_FALLBACK_MS)
                val stillAlivePids = synchronized(lock) {
                    activeClients.values
                        .filter { it.pid in targetPids }
                        .map { it.pid }
                        .toSet()
                }
                if (stillAlivePids.isEmpty()) return@launch
                if (sessionActivationCoordinator.get().sessionEpoch.get() != expectedEpoch) return@launch
                killXrPlayerProcessPids(stillAlivePids)
            }
        }
    }

    private fun killXrPlayerProcessPids(pids: Set<Int>) {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val xrProcessName = "${context.packageName}:xrplayer"
        val running = runCatching { am.runningAppProcesses }.getOrNull().orEmpty()
        for (proc in running) {
            if (proc.processName == xrProcessName && proc.pid in pids) {
                Timber.w("XrPlayerSessionCoordinator: force-killing unresponsive :xrplayer pid=%d", proc.pid)
                runCatching { Process.killProcess(proc.pid) }
            }
        }
    }

    internal fun handleClientSetPendingSyncPlayLeave(serverId: String, userId: String, expectedSessionEpoch: Long) {
        xrProcessPreferenceBridge.get().setPendingSyncPlayLeave(serverId, userId, expectedSessionEpoch)
    }

    internal fun handleClientClearPendingSyncPlayLeave(serverId: String, userId: String, expectedSessionEpoch: Long) {
        xrProcessPreferenceBridge.get().clearPendingSyncPlayLeave(serverId, userId, expectedSessionEpoch)
    }

    internal fun handleClientSetSeriesLanguageOverride(seriesId: String, override: SeriesLanguageOverride?) {
        xrProcessPreferenceBridge.get().setSeriesLanguageOverride(seriesId, override)
    }

    internal fun handleClientSetPlayerMaxBitrate(bitrate: Long) {
        xrProcessPreferenceBridge.get().setPlayerMaxBitrate(bitrate)
    }

    internal fun handleClientRecordVoiceTelemetry(entry: VoiceTelemetryEntry) {
        xrProcessPreferenceBridge.get().recordVoiceTelemetry(entry)
    }

    internal fun handleClientReportStopAndMaybeRunHygiene(
        sourceType: XrProcessPreferenceBridge.StopSourceType,
        itemId: UUID?,
        localMediaId: Long?,
        networkVideoId: String?,
        positionMs: Long,
        durationMs: Long,
        playedPercentage: Int,
        markedPlayed: Boolean,
        runHygiene: Boolean,
        serverId: String?,
        userId: UUID?,
        expectedSessionEpoch: Long,
    ) {
        xrProcessPreferenceBridge.get().reportStopAndMaybeRunHygiene(
            sourceType = sourceType,
            itemId = itemId,
            localMediaId = localMediaId,
            networkVideoId = networkVideoId,
            positionMs = positionMs,
            durationMs = durationMs,
            playedPercentage = playedPercentage,
            markedPlayed = markedPlayed,
            runHygiene = runHygiene,
            serverId = serverId,
            userId = userId,
            expectedSessionEpoch = expectedSessionEpoch,
        )
    }

    private fun armModalWatchdog(durationMs: Long) {
        val now = SystemClock.elapsedRealtime()
        modalWatchdogDeadlineElapsedMs = now + durationMs
        modalWatchdogJob?.cancel()
        modalWatchdogJob = scope.launch {
            while (SystemClock.elapsedRealtime() < modalWatchdogDeadlineElapsedMs) {
                if (!isDeviceInteractive()) {
                    modalWatchdogDeadlineElapsedMs = 0L
                    break
                }
                delay(MODAL_WATCHDOG_TICK_MS)
            }
            modalWatchdogDeadlineElapsedMs = 0L
            synchronized(lock) {
                activeClients.values.forEach { it.permissionPromptPending = false }
            }
            evaluateAppBackgroundedLockedOrDeferred()
        }
    }

    private fun scheduleDeferredLock(graceMs: Long) {
        synchronized(lock) {
            pendingLockJob?.cancel()
            pendingLockJob = scope.launch {
                delay(graceMs)
                evaluateAppBackgroundedLockedOrDeferred()
            }
        }
    }

    private fun evaluateAppBackgroundedLockedOrDeferred() {
        if (!appPreferences.isMainProcess) return
        val now = SystemClock.elapsedRealtime()
        val shouldDefer = synchronized(lock) {
            if (startedMainActivities.isNotEmpty()) return
            val hasPendingPrompt = activeClients.values.any { it.permissionPromptPending }
            activeClients.isNotEmpty() ||
                (isDeviceInteractive() && now < pendingXrLaunchUntilElapsedMs) ||
                (isDeviceInteractive() && (systemModalGraceState.activeCount.value > 0 || hasPendingPrompt)) ||
                (isDeviceInteractive() && now < modalWatchdogDeadlineElapsedMs)
        }
        if (!shouldDefer) {
            appLockManager.get().onAppBackgrounded()
        }
    }

    private fun isDeviceInteractive(): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return true
        return runCatching { pm.isInteractive }.getOrDefault(true)
    }

    companion object {
        const val XR_PLAYER_ACTIVITY_FQCN = "dev.jdtech.jellyfin.player.xr.XrPlayerActivity"
        const val XR_FCAST_INBOUND_ACTIVITY_FQCN = "dev.jdtech.jellyfin.player.xr.XrFCastInboundPlayerActivity"

        val XR_PLAYER_ACTIVITY_CLASSES: Set<String> = setOf(
            XR_PLAYER_ACTIVITY_FQCN,
            XR_FCAST_INBOUND_ACTIVITY_FQCN,
        )

        internal const val DESCRIPTOR_SERVICE = "dev.jdtech.jellyfin.core.util.IXrPlayerSessionService"
        internal const val DESCRIPTOR_CLIENT = "dev.jdtech.jellyfin.core.util.IXrPlayerSessionClient"

        internal const val TX_REGISTER_SESSION = IBinder.FIRST_CALL_TRANSACTION + 0
        internal const val TX_UNREGISTER_SESSION = IBinder.FIRST_CALL_TRANSACTION + 1
        internal const val TX_REQUEST_EPHEMERAL_DEK = IBinder.FIRST_CALL_TRANSACTION + 2
        internal const val TX_SET_PERMISSION_PROMPT_PENDING = IBinder.FIRST_CALL_TRANSACTION + 3
        internal const val TX_NOTIFY_PENDING_LAUNCH = IBinder.FIRST_CALL_TRANSACTION + 4
        internal const val TX_SET_PENDING_SYNCPLAY_LEAVE = IBinder.FIRST_CALL_TRANSACTION + 5
        internal const val TX_CLEAR_PENDING_SYNCPLAY_LEAVE = IBinder.FIRST_CALL_TRANSACTION + 6
        internal const val TX_SET_SERIES_LANG_OVERRIDE = IBinder.FIRST_CALL_TRANSACTION + 7
        internal const val TX_SET_PLAYER_MAX_BITRATE = IBinder.FIRST_CALL_TRANSACTION + 8
        internal const val TX_RECORD_VOICE_TELEMETRY = IBinder.FIRST_CALL_TRANSACTION + 9
        internal const val TX_REPORT_STOP_AND_HYGIENE = IBinder.FIRST_CALL_TRANSACTION + 10

        internal const val TX_CLIENT_SESSION_UPDATED = IBinder.FIRST_CALL_TRANSACTION + 100
        internal const val TX_CLIENT_FORCE_CLOSE = IBinder.FIRST_CALL_TRANSACTION + 101

        internal const val DEK_STATUS_LOCKED_REJECTED = 0
        internal const val DEK_STATUS_GRANTED = 1
        internal const val DEK_STATUS_NOT_NEEDED = 2
        internal const val DEK_STATUS_UNAVAILABLE = 3

        private const val PENDING_LAUNCH_GRACE_MS = 8_000L
        private const val MODAL_GRACE_MAX_MS = 20_000L
        private const val MODAL_WATCHDOG_TICK_MS = 500L
        private const val HANDOFF_GRACE_MS = 2_000L
        private const val FORCE_CLOSE_FALLBACK_MS = 500L
    }
}

@AndroidEntryPoint
class XrPlayerSessionService : Service() {

    @Inject
    lateinit var coordinator: XrPlayerSessionCoordinator

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(XrPlayerSessionCoordinator.DESCRIPTOR_SERVICE)
                return true
            }
            if (Binder.getCallingUid() != Process.myUid()) {
                throw SecurityException("XrPlayerSessionService rejects cross-UID caller")
            }
            return when (code) {
                XrPlayerSessionCoordinator.TX_REGISTER_SESSION -> {
                    data.enforceInterface(XrPlayerSessionCoordinator.DESCRIPTOR_SERVICE)
                    val token = data.readStrongBinder()
                    val result = coordinator.registerSession(token, Binder.getCallingPid())
                    reply?.writeNoException()
                    when (result) {
                        is RegisterSessionResult.Registered -> {
                            reply?.writeInt(1)
                            reply?.writeLong(result.authoritativeSessionEpoch)
                            reply?.writeString(result.activeBaseUrl)
                        }
                        RegisterSessionResult.Rejected -> {
                            reply?.writeInt(0)
                        }
                    }
                    true
                }
                XrPlayerSessionCoordinator.TX_UNREGISTER_SESSION -> {
                    data.enforceInterface(XrPlayerSessionCoordinator.DESCRIPTOR_SERVICE)
                    val token = data.readStrongBinder()
                    val isFinishing = data.readInt() != 0
                    coordinator.unregisterSession(token, isFinishing)
                    reply?.writeNoException()
                    true
                }
                XrPlayerSessionCoordinator.TX_REQUEST_EPHEMERAL_DEK -> {
                    data.enforceInterface(XrPlayerSessionCoordinator.DESCRIPTOR_SERVICE)
                    val token = data.readStrongBinder()
                    val result = coordinator.requestEphemeralDek(token)
                    reply?.writeNoException()
                    when (result) {
                        EphemeralDekResult.LockedRejected ->
                            reply?.writeInt(XrPlayerSessionCoordinator.DEK_STATUS_LOCKED_REJECTED)
                        is EphemeralDekResult.DekGranted -> {
                            try {
                                reply?.writeInt(XrPlayerSessionCoordinator.DEK_STATUS_GRANTED)
                                reply?.writeByteArray(result.dek)
                            } finally {
                                result.dek.fill(0)
                            }
                        }
                        EphemeralDekResult.NotNeeded ->
                            reply?.writeInt(XrPlayerSessionCoordinator.DEK_STATUS_NOT_NEEDED)
                        EphemeralDekResult.Unavailable ->
                            reply?.writeInt(XrPlayerSessionCoordinator.DEK_STATUS_UNAVAILABLE)
                    }
                    true
                }
                XrPlayerSessionCoordinator.TX_SET_PERMISSION_PROMPT_PENDING -> {
                    data.enforceInterface(XrPlayerSessionCoordinator.DESCRIPTOR_SERVICE)
                    val token = data.readStrongBinder()
                    val pending = data.readInt() != 0
                    coordinator.setPermissionPromptPending(token, pending)
                    reply?.writeNoException()
                    true
                }
                XrPlayerSessionCoordinator.TX_NOTIFY_PENDING_LAUNCH -> {
                    data.enforceInterface(XrPlayerSessionCoordinator.DESCRIPTOR_SERVICE)
                    val targetClass = data.readString()
                    val launchIntent = targetClass?.let {
                        Intent().setClassName(packageName, it)
                    }
                    coordinator.notifyPendingLaunch(launchIntent)
                    reply?.writeNoException()
                    true
                }
                XrPlayerSessionCoordinator.TX_SET_PENDING_SYNCPLAY_LEAVE -> {
                    data.enforceInterface(XrPlayerSessionCoordinator.DESCRIPTOR_SERVICE)
                    val serverId = data.readString().orEmpty()
                    val userId = data.readString().orEmpty()
                    val expectedEpoch = data.readLong()
                    if (serverId.isNotBlank() && userId.isNotBlank()) {
                        coordinator.handleClientSetPendingSyncPlayLeave(serverId, userId, expectedEpoch)
                    }
                    reply?.writeNoException()
                    true
                }
                XrPlayerSessionCoordinator.TX_CLEAR_PENDING_SYNCPLAY_LEAVE -> {
                    data.enforceInterface(XrPlayerSessionCoordinator.DESCRIPTOR_SERVICE)
                    val serverId = data.readString().orEmpty()
                    val userId = data.readString().orEmpty()
                    val expectedEpoch = data.readLong()
                    coordinator.handleClientClearPendingSyncPlayLeave(serverId, userId, expectedEpoch)
                    reply?.writeNoException()
                    true
                }
                XrPlayerSessionCoordinator.TX_SET_SERIES_LANG_OVERRIDE -> {
                    data.enforceInterface(XrPlayerSessionCoordinator.DESCRIPTOR_SERVICE)
                    val seriesId = data.readString().orEmpty()
                    val hasOverride = data.readInt() != 0
                    val override = if (hasOverride) {
                        val audioLang = data.readString()
                        val audioSig = data.readString()
                        val subLang = data.readString()
                        val subSig = data.readString()
                        val subEnabledFlag = data.readInt()
                        val subEnabled = when (subEnabledFlag) {
                            1 -> true
                            0 -> false
                            else -> null
                        }
                        SeriesLanguageOverride(
                            audioLanguageCode = audioLang,
                            audioTrackSignature = audioSig,
                            subtitleLanguageCode = subLang,
                            subtitleTrackSignature = subSig,
                            subtitlesEnabled = subEnabled,
                        )
                    } else {
                        null
                    }
                    if (seriesId.isNotBlank()) {
                        coordinator.handleClientSetSeriesLanguageOverride(seriesId, override)
                    }
                    reply?.writeNoException()
                    true
                }
                XrPlayerSessionCoordinator.TX_SET_PLAYER_MAX_BITRATE -> {
                    data.enforceInterface(XrPlayerSessionCoordinator.DESCRIPTOR_SERVICE)
                    val bitrate = data.readLong()
                    coordinator.handleClientSetPlayerMaxBitrate(bitrate)
                    reply?.writeNoException()
                    true
                }
                XrPlayerSessionCoordinator.TX_RECORD_VOICE_TELEMETRY -> {
                    data.enforceInterface(XrPlayerSessionCoordinator.DESCRIPTOR_SERVICE)
                    val entry = VoiceTelemetryEntry(
                        transcript = data.readString().orEmpty(),
                        normalizedTranscript = data.readString().orEmpty(),
                        action = data.readString().orEmpty(),
                        strategy = data.readString().orEmpty(),
                        latencyMs = data.readLong(),
                        success = data.readInt() != 0,
                        retry = data.readInt() != 0,
                        timestampMs = data.readLong(),
                        selectedSkill = data.readString().orEmpty(),
                        validatedInput = data.readString().orEmpty(),
                        resultDisposition = data.readString().orEmpty(),
                        details = data.readString().orEmpty(),
                    )
                    coordinator.handleClientRecordVoiceTelemetry(entry)
                    reply?.writeNoException()
                    true
                }
                XrPlayerSessionCoordinator.TX_REPORT_STOP_AND_HYGIENE -> {
                    data.enforceInterface(XrPlayerSessionCoordinator.DESCRIPTOR_SERVICE)
                    val sourceType = runCatching {
                        XrProcessPreferenceBridge.StopSourceType.valueOf(data.readString().orEmpty())
                    }.getOrDefault(XrProcessPreferenceBridge.StopSourceType.JELLYFIN)
                    val itemId = data.readString()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    val hasLocalMediaId = data.readInt() != 0
                    val localMediaId = if (hasLocalMediaId) data.readLong() else null
                    val networkVideoId = data.readString()
                    val positionMs = data.readLong()
                    val durationMs = data.readLong()
                    val playedPercentage = data.readInt()
                    val markedPlayed = data.readInt() != 0
                    val runHygiene = data.readInt() != 0
                    val serverId = data.readString()
                    val userId = data.readString()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    val expectedEpoch = data.readLong()
                    coordinator.handleClientReportStopAndMaybeRunHygiene(
                        sourceType = sourceType,
                        itemId = itemId,
                        localMediaId = localMediaId,
                        networkVideoId = networkVideoId,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        playedPercentage = playedPercentage,
                        markedPlayed = markedPlayed,
                        runHygiene = runHygiene,
                        serverId = serverId,
                        userId = userId,
                        expectedSessionEpoch = expectedEpoch,
                    )
                    reply?.writeNoException()
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}

/**
 * `:xrplayer`-side Binder client + main-process launch-arming bridge.
 */
object XrPlayerSessionClient {

    interface SessionCallbacks {
        fun onRegistered(result: RegisterSessionResult)
        fun onMainSessionUpdated(newSessionEpoch: Long, newBaseUrl: String?)
        fun onForceClose()
        fun onBindFailed(reason: String)
    }

    private val bindExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "XrPlayerSessionBind").apply { isDaemon = true }
    }

    @Volatile
    private var mainProcessCoordinator: XrPlayerSessionCoordinator? = null

    @Volatile
    private var activeBinder: IBinder? = null

    @Volatile
    private var activeConnection: ServiceConnection? = null

    @Volatile
    private var activeClientToken: IBinder? = null

    @Volatile
    private var activeCallbacks: SessionCallbacks? = null

    @Volatile
    private var boundAppContext: Context? = null

    @Volatile
    private var connectLatch: CountDownLatch = CountDownLatch(0)

    private val pendingIpcQueue = ConcurrentLinkedQueue<(IBinder) -> Unit>()

    // Read-through shadow state in :xrplayer so cross-episode transitions and reopened dialogs
    // observe writes immediately without waiting on main-process SharedPreferences propagation.
    private val shadowSeriesLanguageOverrides = ConcurrentHashMap<String, SeriesLanguageOverride>()
    private val shadowClearedSeriesLanguageOverrides = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var shadowPlayerMaxBitrate: Long? = null

    fun installMainProcessCoordinator(coordinator: XrPlayerSessionCoordinator) {
        mainProcessCoordinator = coordinator
    }

    fun notifyPendingLaunch(intent: Intent?) {
        if (intent == null) return
        val coordinator = mainProcessCoordinator
        if (coordinator != null) {
            coordinator.notifyPendingLaunch(intent)
            return
        }
        val component = intent.component ?: return
        if (component.className !in XrPlayerSessionCoordinator.XR_PLAYER_ACTIVITY_CLASSES) return
        val binder = activeBinder ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(XrPlayerSessionCoordinator.DESCRIPTOR_SERVICE)
            data.writeString(component.className)
            binder.transact(XrPlayerSessionCoordinator.TX_NOTIFY_PENDING_LAUNCH, data, reply, 0)
            reply.readException()
        } catch (t: Throwable) {
            Timber.w(t, "XrPlayerSessionClient: notifyPendingLaunch Binder call failed")
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun bindAndRegister(
        context: Context,
        callbacks: SessionCallbacks,
    ): Boolean {
        val appContext = context.applicationContext
        boundAppContext = appContext
        activeCallbacks = callbacks

        val existing = activeBinder
        val existingToken = activeClientToken
        if (existing != null && existing.isBinderAlive && existingToken != null) {
            bindExecutor.execute {
                val res = performRegister(existing, existingToken)
                flushPendingQueue(existing)
                callbacks.onRegistered(res)
            }
            return true
        }

        val token = createClientToken(callbacks)
        activeClientToken = token
        val latch = CountDownLatch(1)
        connectLatch = latch

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) {
                    latch.countDown()
                    callbacks.onBindFailed("null_service_binder")
                    return
                }
                activeBinder = service
                latch.countDown()
                val res = performRegister(service, token)
                flushPendingQueue(service)
                callbacks.onRegistered(res)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                activeBinder = null
                callbacks.onBindFailed("service_disconnected")
            }

            override fun onBindingDied(name: ComponentName?) {
                activeBinder = null
                latch.countDown()
                callbacks.onBindFailed("binding_died")
            }

            override fun onNullBinding(name: ComponentName?) {
                activeBinder = null
                latch.countDown()
                callbacks.onBindFailed("null_binding")
            }
        }
        activeConnection = connection

        val intent = Intent(appContext, XrPlayerSessionService::class.java)
        return try {
            val bound = appContext.bindService(
                intent,
                Context.BIND_AUTO_CREATE,
                bindExecutor,
                connection,
            )
            if (!bound) {
                latch.countDown()
                callbacks.onBindFailed("bind_returned_false")
            }
            bound
        } catch (t: Throwable) {
            latch.countDown()
            callbacks.onBindFailed("bind_exception:${t.javaClass.simpleName}")
            false
        }
    }

    fun requestEphemeralDek(): EphemeralDekResult {
        val binder = activeBinder ?: return EphemeralDekResult.LockedRejected
        val token = activeClientToken ?: return EphemeralDekResult.LockedRejected
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(XrPlayerSessionCoordinator.DESCRIPTOR_SERVICE)
            data.writeStrongBinder(token)
            binder.transact(XrPlayerSessionCoordinator.TX_REQUEST_EPHEMERAL_DEK, data, reply, 0)
            reply.readException()
            when (reply.readInt()) {
                XrPlayerSessionCoordinator.DEK_STATUS_GRANTED -> {
                    val bytes = reply.createByteArray()
                    if (bytes != null) EphemeralDekResult.DekGranted(bytes) else EphemeralDekResult.Unavailable
                }
                XrPlayerSessionCoordinator.DEK_STATUS_NOT_NEEDED -> EphemeralDekResult.NotNeeded
                XrPlayerSessionCoordinator.DEK_STATUS_UNAVAILABLE -> EphemeralDekResult.Unavailable
                else -> EphemeralDekResult.LockedRejected
            }
        } catch (t: Throwable) {
            Timber.w(t, "XrPlayerSessionClient: requestEphemeralDek failed")
            EphemeralDekResult.LockedRejected
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun setPermissionPromptPending(pending: Boolean) {
        val token = activeClientToken ?: return
        enqueueOrSend { binder ->
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(XrPlayerSessionCoordinator.DESCRIPTOR_SERVICE)
                data.writeStrongBinder(token)
                data.writeInt(if (pending) 1 else 0)
                binder.transact(XrPlayerSessionCoordinator.TX_SET_PERMISSION_PROMPT_PENDING, data, reply, 0)
                reply.readException()
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    fun unregisterAndUnbind(context: Context, isFinishing: Boolean) {
        flushPendingSync(timeoutMs = 1_000L)
        val binder = activeBinder
        val token = activeClientToken
        if (binder != null && token != null && binder.isBinderAlive) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(XrPlayerSessionCoordinator.DESCRIPTOR_SERVICE)
                data.writeStrongBinder(token)
                data.writeInt(if (isFinishing) 1 else 0)
                binder.transact(XrPlayerSessionCoordinator.TX_UNREGISTER_SESSION, data, reply, 0)
                reply.readException()
            } catch (t: Throwable) {
                Timber.d(t, "XrPlayerSessionClient: unregisterSession failed")
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
        val conn = activeConnection
        activeBinder = null
        activeConnection = null
        activeClientToken = null
        if (conn != null) {
            runCatching { context.applicationContext.unbindService(conn) }
        }
    }

    fun flushPendingSync(timeoutMs: Long = 1_000L) {
        if (pendingIpcQueue.isEmpty()) return
        var binder = activeBinder
        if (binder == null || !binder.isBinderAlive) {
            runCatching { connectLatch.await(timeoutMs, TimeUnit.MILLISECONDS) }
            binder = activeBinder
        }
        if ((binder == null || !binder.isBinderAlive) && boundAppContext != null) {
            binder = bindOnDemandForFlush(boundAppContext!!, timeoutMs)
        }
        if (binder != null && binder.isBinderAlive) {
            flushPendingQueue(binder)
        }
    }

    fun enqueueSetPendingSyncPlayLeave(serverId: String, userId: String, expectedSessionEpoch: Long) {
        enqueueOrSend { binder ->
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(XrPlayerSessionCoordinator.DESCRIPTOR_SERVICE)
                data.writeString(serverId)
                data.writeString(userId)
                data.writeLong(expectedSessionEpoch)
                binder.transact(XrPlayerSessionCoordinator.TX_SET_PENDING_SYNCPLAY_LEAVE, data, reply, 0)
                reply.readException()
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    fun enqueueClearPendingSyncPlayLeave(serverId: String, userId: String, expectedSessionEpoch: Long) {
        enqueueOrSend { binder ->
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(XrPlayerSessionCoordinator.DESCRIPTOR_SERVICE)
                data.writeString(serverId)
                data.writeString(userId)
                data.writeLong(expectedSessionEpoch)
                binder.transact(XrPlayerSessionCoordinator.TX_CLEAR_PENDING_SYNCPLAY_LEAVE, data, reply, 0)
                reply.readException()
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    fun hasShadowSeriesLanguageOverride(seriesId: String): Boolean =
        shadowSeriesLanguageOverrides.containsKey(seriesId) ||
            shadowClearedSeriesLanguageOverrides.contains(seriesId)

    fun getShadowSeriesLanguageOverride(seriesId: String): SeriesLanguageOverride? =
        if (shadowClearedSeriesLanguageOverrides.contains(seriesId)) {
            null
        } else {
            shadowSeriesLanguageOverrides[seriesId]
        }

    fun enqueueSetSeriesLanguageOverride(seriesId: String, override: SeriesLanguageOverride?) {
        if (override == null) {
            shadowSeriesLanguageOverrides.remove(seriesId)
            shadowClearedSeriesLanguageOverrides.add(seriesId)
        } else {
            shadowClearedSeriesLanguageOverrides.remove(seriesId)
            shadowSeriesLanguageOverrides[seriesId] = override
        }
        enqueueOrSend { binder ->
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(XrPlayerSessionCoordinator.DESCRIPTOR_SERVICE)
                data.writeString(seriesId)
                if (override != null) {
                    data.writeInt(1)
                    data.writeString(override.audioLanguageCode)
                    data.writeString(override.audioTrackSignature)
                    data.writeString(override.subtitleLanguageCode)
                    data.writeString(override.subtitleTrackSignature)
                    data.writeInt(
                        when (override.subtitlesEnabled) {
                            true -> 1
                            false -> 0
                            null -> -1
                        },
                    )
                } else {
                    data.writeInt(0)
                }
                binder.transact(XrPlayerSessionCoordinator.TX_SET_SERIES_LANG_OVERRIDE, data, reply, 0)
                reply.readException()
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    fun getShadowPlayerMaxBitrate(): Long? = shadowPlayerMaxBitrate

    fun enqueueSetPlayerMaxBitrate(bitrate: Long) {
        shadowPlayerMaxBitrate = bitrate
        enqueueOrSend { binder ->
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(XrPlayerSessionCoordinator.DESCRIPTOR_SERVICE)
                data.writeLong(bitrate)
                binder.transact(XrPlayerSessionCoordinator.TX_SET_PLAYER_MAX_BITRATE, data, reply, 0)
                reply.readException()
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    fun enqueueRecordVoiceTelemetry(entry: VoiceTelemetryEntry) {
        enqueueOrSend { binder ->
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(XrPlayerSessionCoordinator.DESCRIPTOR_SERVICE)
                data.writeString(entry.transcript)
                data.writeString(entry.normalizedTranscript)
                data.writeString(entry.action)
                data.writeString(entry.strategy)
                data.writeLong(entry.latencyMs)
                data.writeInt(if (entry.success) 1 else 0)
                data.writeInt(if (entry.retry) 1 else 0)
                data.writeLong(entry.timestampMs)
                data.writeString(entry.selectedSkill)
                data.writeString(entry.validatedInput)
                data.writeString(entry.resultDisposition)
                data.writeString(entry.details)
                binder.transact(XrPlayerSessionCoordinator.TX_RECORD_VOICE_TELEMETRY, data, reply, 0)
                reply.readException()
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    fun enqueueReportStopAndMaybeRunHygiene(
        sourceType: XrProcessPreferenceBridge.StopSourceType,
        itemId: UUID?,
        localMediaId: Long?,
        networkVideoId: String?,
        positionMs: Long,
        durationMs: Long,
        playedPercentage: Int,
        markedPlayed: Boolean,
        runHygiene: Boolean,
        serverId: String?,
        userId: UUID?,
        expectedSessionEpoch: Long,
    ) {
        enqueueOrSend { binder ->
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(XrPlayerSessionCoordinator.DESCRIPTOR_SERVICE)
                data.writeString(sourceType.name)
                data.writeString(itemId?.toString())
                if (localMediaId != null) {
                    data.writeInt(1)
                    data.writeLong(localMediaId)
                } else {
                    data.writeInt(0)
                }
                data.writeString(networkVideoId)
                data.writeLong(positionMs)
                data.writeLong(durationMs)
                data.writeInt(playedPercentage)
                data.writeInt(if (markedPlayed) 1 else 0)
                data.writeInt(if (runHygiene) 1 else 0)
                data.writeString(serverId)
                data.writeString(userId?.toString())
                data.writeLong(expectedSessionEpoch)
                binder.transact(XrPlayerSessionCoordinator.TX_REPORT_STOP_AND_HYGIENE, data, reply, 0)
                reply.readException()
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    private fun enqueueOrSend(tx: (IBinder) -> Unit) {
        val binder = activeBinder
        if (binder != null && binder.isBinderAlive && pendingIpcQueue.isEmpty()) {
            try {
                tx(binder)
                return
            } catch (t: Throwable) {
                Timber.d(t, "XrPlayerSessionClient: direct IPC send failed; queueing")
            }
        }
        pendingIpcQueue.add(tx)
        val live = activeBinder
        if (live != null && live.isBinderAlive) {
            flushPendingQueue(live)
        }
    }

    private fun flushPendingQueue(binder: IBinder) {
        while (true) {
            val next = pendingIpcQueue.peek() ?: break
            try {
                next(binder)
                pendingIpcQueue.poll()
            } catch (t: Throwable) {
                Timber.w(t, "XrPlayerSessionClient: queued IPC transaction failed")
                break
            }
        }
    }

    private fun bindOnDemandForFlush(appContext: Context, timeoutMs: Long): IBinder? {
        val latch = CountDownLatch(1)
        var resolved: IBinder? = null
        val tempConn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                resolved = service
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
            override fun onBindingDied(name: ComponentName?) {
                latch.countDown()
            }

            override fun onNullBinding(name: ComponentName?) {
                latch.countDown()
            }
        }
        val intent = Intent(appContext, XrPlayerSessionService::class.java)
        val bound = runCatching {
            appContext.bindService(intent, Context.BIND_AUTO_CREATE, bindExecutor, tempConn)
        }.getOrDefault(false)
        if (!bound) return null
        runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
        val result = resolved
        if (result != null && result.isBinderAlive) {
            flushPendingQueue(result)
        }
        runCatching { appContext.unbindService(tempConn) }
        return result
    }

    private fun performRegister(binder: IBinder, token: IBinder): RegisterSessionResult {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(XrPlayerSessionCoordinator.DESCRIPTOR_SERVICE)
            data.writeStrongBinder(token)
            binder.transact(XrPlayerSessionCoordinator.TX_REGISTER_SESSION, data, reply, 0)
            reply.readException()
            val ok = reply.readInt() != 0
            if (ok) {
                RegisterSessionResult.Registered(
                    authoritativeSessionEpoch = reply.readLong(),
                    activeBaseUrl = reply.readString(),
                )
            } else {
                RegisterSessionResult.Rejected
            }
        } catch (t: Throwable) {
            Timber.w(t, "XrPlayerSessionClient: registerSession IPC failed")
            RegisterSessionResult.Rejected
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun createClientToken(callbacks: SessionCallbacks): Binder =
        object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) {
                    reply?.writeString(XrPlayerSessionCoordinator.DESCRIPTOR_CLIENT)
                    return true
                }
                if (Binder.getCallingUid() != Process.myUid()) {
                    throw SecurityException("XrPlayerSessionClient rejects cross-UID callback")
                }
                return when (code) {
                    XrPlayerSessionCoordinator.TX_CLIENT_SESSION_UPDATED -> {
                        data.enforceInterface(XrPlayerSessionCoordinator.DESCRIPTOR_CLIENT)
                        val newEpoch = data.readLong()
                        val newBaseUrl = data.readString()
                        callbacks.onMainSessionUpdated(newEpoch, newBaseUrl)
                        true
                    }
                    XrPlayerSessionCoordinator.TX_CLIENT_FORCE_CLOSE -> {
                        data.enforceInterface(XrPlayerSessionCoordinator.DESCRIPTOR_CLIENT)
                        callbacks.onForceClose()
                        true
                    }
                    else -> super.onTransact(code, data, reply, flags)
                }
            }
        }
}
