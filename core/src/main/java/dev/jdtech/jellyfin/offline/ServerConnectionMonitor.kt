package dev.jdtech.jellyfin.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.work.SyncWorker
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
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
import timber.log.Timber
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

data class ServerConnectionState(
    val manualOfflineMode: Boolean = false,
    val serverAccessible: Boolean = true,
) {
    val effectiveOfflineMode: Boolean
        get() = manualOfflineMode || !serverAccessible
}

@Singleton
class ServerConnectionMonitor
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val appPreferences: AppPreferences,
    private val database: ServerDatabaseDao,
    private val workManager: WorkManager,
) {
    companion object {
        private const val ACCESSIBLE_REFRESH_MS = 120_000L
        private const val INACCESSIBLE_REFRESH_MS = 20_000L
        private const val PROBE_FAILURE_LOG_INTERVAL_MS = 60_000L
    }

    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state =
        MutableStateFlow(
            ServerConnectionState(
                manualOfflineMode = appPreferences.getValue(appPreferences.offlineMode)
            )
        )
    val state: StateFlow<ServerConnectionState> = _state.asStateFlow()
    @Volatile private var lastProbeFailureLogAtMs: Long = 0L
    @Volatile private var lastProbeFailureAddress: String? = null

    private val preferenceListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (
                key == appPreferences.offlineMode.backendName ||
                    key == appPreferences.currentServer.backendName
            ) {
                _state.update {
                    it.copy(
                        manualOfflineMode = appPreferences.getValue(appPreferences.offlineMode)
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
        appPreferences.sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }
            .onFailure { Timber.w(it, "Failed to register network callback") }

        scope.launch {
            refreshServerAccessibility()
            while (isActive) {
                delay(if (state.value.serverAccessible) ACCESSIBLE_REFRESH_MS else INACCESSIBLE_REFRESH_MS)
                refreshServerAccessibility()
            }
        }
    }

    fun markServerInaccessible() {
        updateAccessibility(false)
    }

    fun markServerAccessible() {
        updateAccessibility(true)
    }

    fun shouldUseOfflineRepository(): Boolean = state.value.effectiveOfflineMode

    fun isConnectionFailure(throwable: Throwable): Boolean {
        if (throwable is CancellationException) return false
        return throwable is IOException ||
            throwable is org.jellyfin.sdk.api.client.exception.ApiClientException
    }

    fun triggerRefresh() {
        scope.launch { refreshServerAccessibility() }
    }

    private suspend fun refreshServerAccessibility() {
        val manualOffline = appPreferences.getValue(appPreferences.offlineMode)
        val serverId = appPreferences.getValue(appPreferences.currentServer)
        if (serverId == null) {
            _state.update { it.copy(manualOfflineMode = manualOffline, serverAccessible = true) }
            return
        }

        val address = database.getServerCurrentAddress(serverId)?.address
        if (address.isNullOrBlank()) {
            _state.update { it.copy(manualOfflineMode = manualOffline, serverAccessible = false) }
            return
        }

        if (!hasActiveNetwork()) {
            _state.update { it.copy(manualOfflineMode = manualOffline, serverAccessible = false) }
            return
        }

        val accessible = probeServer(address)
        _state.update {
            it.copy(manualOfflineMode = manualOffline, serverAccessible = accessible)
        }
        if (accessible) enqueueSync()
    }

    private fun hasActiveNetwork(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun probeServer(address: String): Boolean {
        return try {
            val sanitizedBaseUrl = address.trimEnd('/')
            val url = URL("$sanitizedBaseUrl/System/Info/Public")
            val connection =
                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 3_000
                    readTimeout = 3_000
                    instanceFollowRedirects = true
                }
            try {
                connection.responseCode in 200..399
            } finally {
                connection.disconnect()
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

    private fun updateAccessibility(accessible: Boolean) {
        val wasAccessible = state.value.serverAccessible
        _state.update { it.copy(serverAccessible = accessible) }
        if (!wasAccessible && accessible) enqueueSync()
    }

    private fun enqueueSync() {
        val request =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
        workManager.enqueueUniqueWork("sync-on-reconnect", ExistingWorkPolicy.REPLACE, request)
    }
}
