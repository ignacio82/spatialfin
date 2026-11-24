package dev.jdtech.jellyfin.offline

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.normalizeJellyfinId
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.network.newBuilderWithSsrfGuard
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionMode
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.work.MetadataRefreshWorker
import dev.jdtech.jellyfin.work.SyncWorker
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

/**
 * Pure decision helper for "is this throwable a connection failure we should
 * fall back to offline for?". Walks the cause chain so wrapped IOExceptions
 * (RuntimeException → SocketTimeoutException, etc.) still trigger fallback
 * instead of stranding the UI. Bounded by a `seen` set so a self-referential
 * cause doesn't loop.
 */
internal fun isApparentConnectionFailure(throwable: Throwable): Boolean {
    if (throwable is CancellationException) return false
    var current: Throwable? = throwable
    val seen = mutableSetOf<Throwable>()
    while (current != null && seen.add(current)) {
        if (current is org.jellyfin.sdk.api.client.exception.InvalidStatusException) {
            return current.status != 401 && current.status != 403
        }
        if (
            current is IOException ||
                current is org.jellyfin.sdk.api.client.exception.ApiClientException
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

data class ServerConnectionState(
    val manualOfflineMode: Boolean = false,
    val serverAccessible: Boolean = true,
    val offlineTransitionStartedAtMs: Long? = null,
) {
    val isDegradedMode: Boolean
        get() = !manualOfflineMode && !serverAccessible && offlineTransitionStartedAtMs != null

    val effectiveOfflineMode: Boolean
        get() = manualOfflineMode || (!serverAccessible && offlineTransitionStartedAtMs == null)
}

@Singleton
class ServerConnectionMonitor
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val appPreferences: AppPreferences,
    private val database: ServerDatabaseDao,
    private val workManager: dagger.Lazy<WorkManager>,
    private val okHttpClient: OkHttpClient,
    private val coordinator: SessionActivationCoordinator,
    private val appLockObserver: AppLockObserver,
) {
    companion object {
        private const val ACCESSIBLE_REFRESH_MS = 120_000L
        private const val DEGRADED_REFRESH_MS = 5_000L
        private const val INACCESSIBLE_REFRESH_MS = 20_000L
        private const val OFFLINE_GRACE_MS = 15_000L
        private const val PROBE_FAILURE_LOG_INTERVAL_MS = 60_000L
    }

    private val isMainProcess: Boolean =
        runCatching { Application.getProcessName() == context.packageName }.getOrDefault(true)
    private val probeHttpClient: OkHttpClient by lazy {
        okHttpClient
            .newBuilderWithSsrfGuard(
                policy = UrlTargetPolicy.PUBLIC_OR_LAN,
                maxRedirects = 0,
            )
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state =
        MutableStateFlow(
            ServerConnectionState(
                manualOfflineMode = appPreferences.getValue(appPreferences.offlineMode),
            ),
        )
    val state: StateFlow<ServerConnectionState> = _state.asStateFlow()

    @Volatile private var lastProbeFailureLogAtMs: Long = 0L
    @Volatile private var lastProbeFailureAddress: String? = null

    private val preferenceListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == appPreferences.offlineMode.backendName) {
                _state.update {
                    it.copy(
                        manualOfflineMode = appPreferences.getValue(appPreferences.offlineMode),
                    )
                }
                triggerRefresh()
            }
        }

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                triggerRefresh()
            }

            override fun onLost(network: Network) {
                triggerRefresh()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                triggerRefresh()
            }
        }

    init {
        if (isMainProcess) {
            appPreferences.sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceListener)
            runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }
                .onFailure { Timber.w(it, "Failed to register network callback") }

            scope.launch {
                refreshServerAccessibility()
                while (isActive) {
                    delay(
                        when {
                            state.value.isDegradedMode -> DEGRADED_REFRESH_MS
                            state.value.serverAccessible -> ACCESSIBLE_REFRESH_MS
                            else -> INACCESSIBLE_REFRESH_MS
                        },
                    )
                    refreshServerAccessibility()
                }
            }
        }
    }

    private fun isAppLocked(): Boolean =
        appLockObserver.lockState.value == AppLockManager.LockState.LOCKED

    fun onSessionActivated(serverId: String, reachable: Boolean = true) {
        val manualOffline = appPreferences.getValue(appPreferences.offlineMode)
        _state.update {
            it.copy(
                manualOfflineMode = manualOffline,
                serverAccessible = reachable,
                offlineTransitionStartedAtMs = null,
            )
        }
        Timber.d("ServerConnectionMonitor.onSessionActivated(server=%s, reachable=%s)", serverId, reachable)
    }

    fun markServerInaccessible(expectedEpoch: Long) {
        if (coordinator.sessionEpoch.get() != expectedEpoch || coordinator.isActivating.get()) return
        updateAccessibility(false)
    }

    fun markServerAccessible(expectedEpoch: Long) {
        if (coordinator.sessionEpoch.get() != expectedEpoch || coordinator.isActivating.get()) return
        updateAccessibility(true)
    }

    fun shouldUseOfflineRepository(): Boolean = state.value.effectiveOfflineMode

    fun isConnectionFailure(throwable: Throwable): Boolean =
        isApparentConnectionFailure(throwable)

    fun triggerRefresh() {
        if (isAppLocked()) return
        scope.launch { refreshServerAccessibility() }
    }

    fun reconnect() {
        if (isAppLocked()) return
        scope.launch {
            if (appPreferences.getValue(appPreferences.offlineMode)) {
                appPreferences.setValue(appPreferences.offlineMode, false)
            }
            refreshServerAccessibility()
            if (state.value.serverAccessible && !state.value.manualOfflineMode) {
                enqueueSync()
            }
        }
    }

    suspend fun refreshServerAccessibility() {
        if (isAppLocked()) return
        coordinator.ensureInitialized()
        if (isAppLocked()) return

        val epochBefore = coordinator.sessionEpoch.get()
        val manualOffline = appPreferences.getValue(appPreferences.offlineMode)
        val serverId = coordinator.sessionState.value.activeServerId
        if (serverId == null) {
            _state.update {
                it.copy(
                    manualOfflineMode = manualOffline,
                    serverAccessible = true,
                    offlineTransitionStartedAtMs = null,
                )
            }
            return
        }

        val addresses = database.getServerAddresses(serverId).filter { it.address.isNotBlank() }
        if (addresses.isEmpty()) {
            if (coordinator.sessionEpoch.get() == epochBefore && !coordinator.isActivating.get()) {
                updateAccessibility(accessible = false, manualOfflineMode = manualOffline)
            }
            return
        }

        if (!hasActiveNetwork()) {
            if (coordinator.sessionEpoch.get() == epochBefore && !coordinator.isActivating.get()) {
                updateAccessibility(accessible = false, manualOfflineMode = manualOffline)
            }
            return
        }

        val reachable = addresses.firstOrNull { probeServer(serverId, it.address) }
        if (isAppLocked()) return
        if (coordinator.sessionEpoch.get() != epochBefore || coordinator.isActivating.get()) return

        if (reachable != null) {
            if (isMainProcess) {
                repairCurrentServerAddress(serverId, reachable, epochBefore)
            }
            updateAccessibility(accessible = true, manualOfflineMode = manualOffline)
        } else {
            updateAccessibility(accessible = false, manualOfflineMode = manualOffline)
        }
    }

    private suspend fun repairCurrentServerAddress(
        serverId: String,
        address: ServerAddress,
        expectedEpoch: Long,
    ) {
        val currentAddressId = database.get(serverId)?.currentServerAddressId
        if (currentAddressId != address.id) {
            coordinator.refreshActiveAddress(
                serverId = serverId,
                addressId = address.id,
                expectedEpoch = expectedEpoch,
            )
            Timber.i("Repaired current server address for %s using %s", serverId, address.address)
        }
    }

    internal fun hasActiveNetwork(): Boolean {
        val cm = connectivityManager ?: return false
        fun isNetworkUsable(network: Network?): Boolean {
            if (network == null) return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                return true
            }
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        if (isNetworkUsable(cm.activeNetwork)) return true
        @Suppress("DEPRECATION")
        val all = runCatching { cm.allNetworks }.getOrNull().orEmpty()
        return all.any(::isNetworkUsable)
    }

    internal fun probeServer(serverId: String, address: String): Boolean {
        return try {
            val sanitizedBaseUrl = address.trimEnd('/')
            val request =
                Request.Builder()
                    .url("$sanitizedBaseUrl/System/Info/Public")
                    .header("Accept", "application/json")
                    .get()
                    .build()
            probeHttpClient.newCall(request).execute().use { response ->
                if (response.code !in 200..299) {
                    return false
                }
                val body = response.body.string().trim()
                if (!body.startsWith("{")) return false
                val json = JSONObject(body)
                val infoId = json.optString("Id", "").trim()
                val productName = json.optString("ProductName", "").trim()
                if (!serverId.startsWith("srv-")) {
                    infoId.isNotEmpty() &&
                        normalizeJellyfinId(infoId) == normalizeJellyfinId(serverId)
                } else {
                    productName == "Jellyfin Server"
                }
            }
        } catch (throwable: Throwable) {
            val now = System.currentTimeMillis()
            val shouldLog =
                lastProbeFailureAddress != address ||
                    now - lastProbeFailureLogAtMs >= PROBE_FAILURE_LOG_INTERVAL_MS
            if (shouldLog) {
                lastProbeFailureAddress = address
                lastProbeFailureLogAtMs = now
                Timber.d(throwable, "Server probe failed for %s", address)
            }
            false
        }
    }

    private fun updateAccessibility(
        accessible: Boolean,
        manualOfflineMode: Boolean = appPreferences.getValue(appPreferences.offlineMode),
    ) {
        val current = state.value
        if (accessible) {
            val wasUnavailable = !current.serverAccessible || current.offlineTransitionStartedAtMs != null
            _state.update {
                it.copy(
                    manualOfflineMode = manualOfflineMode,
                    serverAccessible = true,
                    offlineTransitionStartedAtMs = null,
                )
            }
            val hasPendingSync =
                !manualOfflineMode &&
                    runCatching { database.getPendingUserDataSyncCount() > 0 }.getOrDefault(false)
            if (wasUnavailable || hasPendingSync) enqueueSync()
            return
        }

        val now = System.currentTimeMillis()
        val offlineTransitionStartedAtMs =
            when {
                manualOfflineMode -> null
                current.serverAccessible -> now
                current.offlineTransitionStartedAtMs == null -> null
                now - current.offlineTransitionStartedAtMs >= OFFLINE_GRACE_MS -> null
                else -> current.offlineTransitionStartedAtMs
            }
        _state.update {
            it.copy(
                manualOfflineMode = manualOfflineMode,
                serverAccessible = false,
                offlineTransitionStartedAtMs = offlineTransitionStartedAtMs,
            )
        }
    }

    private fun enqueueSync() {
        if (!isMainProcess) return
        if (isAppLocked()) return
        if (coordinator.sessionMode.value != SessionMode.SERVER_AUTHENTICATED) return
        val constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val syncRequest =
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build()
        val refreshRequest =
            OneTimeWorkRequestBuilder<MetadataRefreshWorker>().setConstraints(constraints).build()
        val wm = workManager.get()
        wm.enqueueUniqueWork("sync-on-reconnect", ExistingWorkPolicy.KEEP, syncRequest)
        wm.enqueueUniqueWork(
            "metadata-refresh-on-reconnect",
            ExistingWorkPolicy.KEEP,
            refreshRequest,
        )
    }
}
