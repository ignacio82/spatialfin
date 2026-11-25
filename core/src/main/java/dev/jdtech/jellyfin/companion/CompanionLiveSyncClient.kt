package dev.jdtech.jellyfin.companion

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.models.companion.CompanionEndpoint
import dev.jdtech.jellyfin.models.companion.DeviceIdentity
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.network.newBuilderWithSsrfGuard
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.work.CompanionSyncWorker
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live push channel from the Companion app. Connects over WebSocket to the Companion's root
 * endpoint and listens for `config_changed` frames, enqueuing a one-shot [CompanionSyncWorker]
 * (`CompanionSyncPush`) when targeted at all devices or this device's [DeviceIdentity.deviceId].
 */
@Singleton
class CompanionLiveSyncClient
@Inject
constructor(
    @param:ApplicationContext private val appContext: Context,
    private val appPreferences: AppPreferences,
    private val baseHttpClient: OkHttpClient,
    private val workManager: dagger.Lazy<WorkManager>,
    private val appLockObserver: AppLockObserver = AppLockObserver.NoOp,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val stateLock = Any()

    private var currentSocket: WebSocket? = null
    private var activeCredentials: Pair<String, String>? = null
    private var reconnectJob: Job? = null

    private val _configChangedEvents =
        MutableSharedFlow<Unit>(
            replay = 0,
            extraBufferCapacity = 4,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val configChangedEvents: SharedFlow<Unit> = _configChangedEvents.asSharedFlow()

    @Volatile private var backoffMs: Long = INITIAL_BACKOFF_MS
    @Volatile private var started = false
    @Volatile private var explicitStop = false
    @Volatile private var pendingSyncOnUnlock = false
    @Volatile var authRevoked: Boolean = false
        internal set

    private fun isAppLocked(): Boolean =
        appLockObserver.lockState.value == AppLockManager.LockState.LOCKED

    fun onAppUnlocked() {
        if (isAppLocked()) return
        val shouldSync =
            synchronized(stateLock) {
                val pending = pendingSyncOnUnlock
                pendingSyncOnUnlock = false
                pending && CompanionEndpoint.fromPreferences(appPreferences) != null
            }
        if (shouldSync) {
            triggerSync()
        }
    }

    fun start() {
        synchronized(stateLock) {
            if (started) return
            started = true
            explicitStop = false
        }
        scope.launch { connect() }
    }

    /** Connects the live push WebSocket if credentials exist and no active socket is open. */
    fun ensureConnected() {
        if (CompanionEndpoint.fromPreferences(appPreferences) == null) return
        val shouldConnect: Boolean
        synchronized(stateLock) {
            if (authRevoked || currentSocket != null) return
            explicitStop = false
            started = true
            backoffMs = INITIAL_BACKOFF_MS
            reconnectJob?.cancel()
            reconnectJob = null
            shouldConnect = true
        }
        if (shouldConnect) {
            scope.launch { connect() }
        }
    }

    fun stop() {
        val toClose: WebSocket?
        synchronized(stateLock) {
            explicitStop = true
            started = false
            pendingSyncOnUnlock = false
            reconnectJob?.cancel()
            reconnectJob = null
            toClose = currentSocket
            currentSocket = null
            activeCredentials = null
        }
        toClose?.close(WS_NORMAL_CLOSE, "client stop")
    }

    /** Called when Companion URL or token changes (e.g., pairing completes or unpairing). */
    fun refreshConnection() {
        val nextEndpoint = CompanionEndpoint.fromPreferences(appPreferences)
        val nextCreds = nextEndpoint?.let { it.normalizedBaseUrl to it.setupToken }

        var toClose: WebSocket? = null
        var shouldCancelPush = false
        var shouldConnect = false

        synchronized(stateLock) {
            if (nextCreds == null) {
                // Unpaired: tear down socket, clear backoff/revocation, cancel queued push syncs.
                reconnectJob?.cancel()
                reconnectJob = null
                toClose = currentSocket
                currentSocket = null
                activeCredentials = null
                pendingSyncOnUnlock = false
                authRevoked = false
                shouldCancelPush = true
            } else if (nextCreds == activeCredentials && !authRevoked && currentSocket != null) {
                // Deduplicate batched SharedPreferences.Editor.apply() notifications.
                return
            } else {
                if (nextCreds != activeCredentials) {
                    authRevoked = false
                }
                explicitStop = false
                started = true
                reconnectJob?.cancel()
                reconnectJob = null
                toClose = currentSocket
                currentSocket = null
                activeCredentials = null
                backoffMs = INITIAL_BACKOFF_MS
                shouldConnect = true
            }
        }

        toClose?.close(WS_NORMAL_CLOSE, "credentials updated")
        if (shouldCancelPush) {
            runCatching { workManager.get().cancelUniqueWork(WORK_NAME_PUSH) }
        }
        if (shouldConnect) {
            scope.launch { connect() }
        }
    }

    private fun connect() {
        val endpoint: CompanionEndpoint
        synchronized(stateLock) {
            if (explicitStop || authRevoked) return
            val resolved = CompanionEndpoint.fromPreferences(appPreferences)
            if (resolved == null) {
                activeCredentials = null
                return
            }
            val creds = resolved.normalizedBaseUrl to resolved.setupToken
            if (currentSocket != null && activeCredentials == creds) return
            endpoint = resolved
            activeCredentials = creds
        }

        val wsUrl = endpoint.webSocketUrlString()
        val policy =
            if (wsUrl.startsWith("wss://", ignoreCase = true)) {
                UrlTargetPolicy.PUBLIC_OR_LAN
            } else {
                UrlTargetPolicy.PRIVATE_LAN_ONLY
            }
        val guardedClient =
            baseHttpClient
                .newBuilderWithSsrfGuard(policy = policy)
                .pingInterval(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()

        val request =
            Request.Builder()
                .url(wsUrl)
                .addHeader("X-Setup-Token", endpoint.setupToken)
                .build()
        Timber.i("CompanionLiveSync: connecting to %s", wsUrl)

        val socket = guardedClient.newWebSocket(request, SocketListener())
        var stale = false
        synchronized(stateLock) {
            if (explicitStop || authRevoked) {
                stale = true
            } else {
                currentSocket = socket
            }
        }
        if (stale) {
            socket.close(WS_NORMAL_CLOSE, "stale connect")
        }
    }

    private fun scheduleReconnect() {
        synchronized(stateLock) {
            if (explicitStop || authRevoked) return
            if (CompanionEndpoint.fromPreferences(appPreferences) == null) return
            reconnectJob?.cancel()
            val delayMs = backoffMs
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            reconnectJob =
                scope.launch {
                    delay(delayMs)
                    connect()
                }
        }
    }

    internal fun handleMessageText(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        val type = json.optString("type")
        if (type == "config_changed") {
            val targetDeviceId =
                listOf("targetDeviceId", "deviceId").firstNotNullOfOrNull { key ->
                    if (json.isNull(key)) {
                        null
                    } else {
                        json.optString(key, "").trim().takeIf { it.isNotEmpty() }
                    }
                }
            val myDeviceId = DeviceIdentity.deviceId(appContext)
            if (targetDeviceId == null || targetDeviceId == myDeviceId) {
                Timber.i("CompanionLiveSync: received config_changed, triggering sync")
                triggerSync()
            } else {
                Timber.d("CompanionLiveSync: ignoring config_changed for another device")
            }
        }
    }

    private fun triggerSync() {
        if (isAppLocked()) {
            pendingSyncOnUnlock = true
            Timber.i("CompanionLiveSync: App Lock active — deferring CompanionSyncPush until unlock")
            return
        }
        pendingSyncOnUnlock = false
        _configChangedEvents.tryEmit(Unit)
        val req =
            OneTimeWorkRequestBuilder<CompanionSyncWorker>()
                .addTag(WORK_NAME_PUSH)
                .build()
        workManager.get().enqueueUniqueWork(
            WORK_NAME_PUSH,
            ExistingWorkPolicy.REPLACE,
            req,
        )
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Timber.i("CompanionLiveSync: connected")
            backoffMs = INITIAL_BACKOFF_MS
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessageText(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Timber.d("CompanionLiveSync: closing (%d %s)", code, reason)
            if (code == WS_AUTH_REVOKED_CLOSE) {
                authRevoked = true
            }
            webSocket.close(WS_NORMAL_CLOSE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Timber.d("CompanionLiveSync: closed (%d %s)", code, reason)
            val wasCurrent: Boolean
            synchronized(stateLock) {
                if (code == WS_AUTH_REVOKED_CLOSE) {
                    authRevoked = true
                }
                wasCurrent = currentSocket === webSocket
                if (wasCurrent) currentSocket = null
            }
            if (wasCurrent && !authRevoked) {
                scheduleReconnect()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            Timber.w(t, "CompanionLiveSync: failure (response=%s)", code ?: "-")
            val wasCurrent: Boolean
            synchronized(stateLock) {
                if (code == 401 || code == 403) {
                    authRevoked = true
                }
                wasCurrent = currentSocket === webSocket
                if (wasCurrent) currentSocket = null
            }
            if (wasCurrent && !authRevoked) {
                scheduleReconnect()
            }
        }
    }

    companion object {
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val WS_NORMAL_CLOSE = 1000
        private const val WS_AUTH_REVOKED_CLOSE = 4401
        private const val WORK_NAME_PUSH = "CompanionSyncPush"

        fun from(context: Context): CompanionLiveSyncClient =
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                CompanionLiveSyncClientEntryPoint::class.java,
            ).companionLiveSyncClient()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CompanionLiveSyncClientEntryPoint {
    fun companionLiveSyncClient(): CompanionLiveSyncClient
}
