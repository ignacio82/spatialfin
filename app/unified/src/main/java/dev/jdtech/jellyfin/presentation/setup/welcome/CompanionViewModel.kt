package dev.jdtech.jellyfin.presentation.setup.welcome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.NetworkShareDto
import dev.jdtech.jellyfin.network.SmbPathNormalizer
import dev.jdtech.jellyfin.network.SmbConnectionTarget
import dev.jdtech.jellyfin.models.companion.CompanionConfig
import dev.jdtech.jellyfin.models.companion.CompanionDiscoveryPayload
import dev.jdtech.jellyfin.models.companion.CompanionNetworkShare
import dev.jdtech.jellyfin.models.companion.CompanionUser
import dev.jdtech.jellyfin.models.companion.DeviceIdentity
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.applyCompanionPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.jdtech.jellyfin.work.CompanionSyncWorker
import timber.log.Timber
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context

sealed class CompanionState {
    data object Idle : CompanionState()
    data object Scanning : CompanionState()
    data object Fetching : CompanionState()
    data object Success : CompanionState()
    data class Error(val message: String) : CompanionState()
}

private data class ImportedSession(
    val serverId: String,
    val baseUrl: String,
    val userId: UUID,
    val accessToken: String?
)

@HiltViewModel
class CompanionViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val serverDatabase: ServerDatabaseDao,
    private val jellyfinApi: JellyfinApi,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow<CompanionState>(CompanionState.Idle)
    val state: StateFlow<CompanionState> = _state

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder().build()

    fun startScanning() {
        _state.value = CompanionState.Scanning
    }

    fun syncNow() {
        val url = appPreferences.getValue(appPreferences.companionUrl)
        val token = appPreferences.getValue(appPreferences.companionToken)
        if (url.isNotEmpty() && token.isNotEmpty()) {
            fetchAndApplyConfig(CompanionDiscoveryPayload(version = 1, companion_url = url, setup_token = token))
        }
    }

    fun fetchAndApplyConfig(payload: CompanionDiscoveryPayload) {
        if (_state.value is CompanionState.Fetching || _state.value is CompanionState.Success) return

        // Save companion info for background sync
        appPreferences.setValue(appPreferences.companionUrl, payload.companion_url)
        appPreferences.setValue(appPreferences.companionToken, payload.setup_token)

        viewModelScope.launch {
            _state.value = CompanionState.Fetching
            try {
                val config = fetchConfig(payload)
                applyConfig(config)
                appPreferences.setValue(appPreferences.lastCompanionSyncTime, System.currentTimeMillis())
                _state.value = CompanionState.Success
            } catch (e: Exception) {
                Timber.e(e, "COMPANION: Failed to fetch or apply config")
                val message = when (e) {
                    is SocketTimeoutException -> "Connection timed out. Ensure headset is on the same WiFi as Companion App."
                    is ConnectException -> "Connection refused. Check if Companion App is running and reachable."
                    else -> e.message ?: "Unknown error"
                }
                _state.value = CompanionState.Error(message)
            }
        }
    }

    private suspend fun fetchConfig(payload: CompanionDiscoveryPayload): CompanionConfig = withContext(Dispatchers.IO) {
        Timber.d("COMPANION: Fetching config from ${payload.companion_url}/api/v1/config")
        val request = Request.Builder()
            .url("${payload.companion_url}/api/v1/config")
            .addHeader("X-Setup-Token", payload.setup_token)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body = response.body?.string() ?: throw Exception("Empty response")
            Timber.d("COMPANION: Raw config response length=${body.length}")
            val config = json.decodeFromString<CompanionConfig>(body)
            Timber.d("COMPANION: Parsed config: version=${config.version}, servers=${config.servers.size}, preferences=${config.preferences.size}, networkShares=${config.networkShares.size}")
            config.servers.forEachIndexed { i, s ->
                Timber.d("COMPANION:   server[$i] id=${s.id} name=${s.name} addresses=${s.addresses} users=${s.users.size}")
                s.users.forEachIndexed { j, u ->
                    Timber.d("COMPANION:     user[$j] username=${u.username} hasAccessToken=${u.accessToken != null} hasPassword=${u.password != null} preferences=${u.preferences.keys}")
                }
            }
            config.preferences.forEach { (k, v) ->
                val display = if (k.contains("key", ignoreCase = true) || k.contains("password", ignoreCase = true)) "***" else v
                Timber.d("COMPANION:   pref: $k = $display")
            }
            config
        }
    }

    private suspend fun applyConfig(config: CompanionConfig) = withContext(Dispatchers.IO) {
        // Apply Global Preferences first, then this device's per-device overrides so
        // they win over globals. User-level prefs (below) still override both.
        Timber.d("COMPANION: Applying ${config.preferences.size} global preferences")
        applyPreferences(config.preferences)

        val myDeviceId = DeviceIdentity.deviceId(context)
        val deviceOverrides = config.devicePreferences[myDeviceId]
        if (!deviceOverrides.isNullOrEmpty()) {
            Timber.d(
                "COMPANION: Applying %d device-level overrides for deviceId=%s",
                deviceOverrides.size, myDeviceId,
            )
            applyPreferences(deviceOverrides)
        }

        val importedSessions = mutableListOf<ImportedSession>()
        val importedUserPreferences = mutableMapOf<UUID, Map<String, String?>>()
        val previousCurrentServer = appPreferences.getValue(appPreferences.currentServer)

        // Apply Servers and Users
        config.servers.forEach { s ->
            val serverId = s.id
            var serverSession: ImportedSession? = null

            val validUsers = mutableListOf<User>()

            // Normalize URL (must have trailing slash for Jellyfin SDK)
            val rawAddress = s.addresses.firstOrNull() ?: ""
            val baseUrl = if (rawAddress.isNotEmpty() && !rawAddress.endsWith("/")) "$rawAddress/" else rawAddress

            s.users.forEach { u ->
                try {
                    val user = authenticateOrUseToken(u, baseUrl, serverId)
                    if (user != null) {
                        validUsers.add(user)
                        importedUserPreferences[user.id] = u.preferences
                        Timber.d("COMPANION: Successfully added user ${u.username} (id=${user.id})")
                        if (serverSession == null) {
                            serverSession = ImportedSession(
                                serverId = serverId,
                                baseUrl = baseUrl,
                                userId = user.id,
                                accessToken = user.accessToken
                            )
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "COMPANION: Failed to add user ${u.username} on ${s.name}")
                }
            }

            Timber.d("COMPANION: Server ${s.name}: ${validUsers.size}/${s.users.size} users valid")

            // Only insert server if we have at least one valid user
            if (validUsers.isNotEmpty()) {
                val server = Server(
                    id = serverId,
                    name = s.name,
                    currentServerAddressId = null,
                    currentUserId = serverSession?.userId
                )
                serverDatabase.insertServer(server)

                // Insert Address
                val addressId = UUID.randomUUID()
                val serverAddress = ServerAddress(id = addressId, serverId = serverId, address = baseUrl)
                serverDatabase.insertServerAddress(serverAddress)

                server.currentServerAddressId = addressId
                serverDatabase.update(server)

                validUsers.forEach { serverDatabase.insertUser(it) }
                serverSession?.let(importedSessions::add)
            }
        }

        // Apply Network Shares
        applyNetworkShares(config.networkShares)

        val activeSession =
            importedSessions.firstOrNull { it.serverId == previousCurrentServer }
                ?: importedSessions.firstOrNull()

        // If we found any valid server, set it as active and update the live API session.
        activeSession?.let { session ->
            appPreferences.setValue(appPreferences.currentServer, session.serverId)
            appPreferences.setValue(appPreferences.onboardingCompleted, true)
            jellyfinApi.api.update(baseUrl = session.baseUrl, accessToken = session.accessToken)
            jellyfinApi.userId = session.userId
            scheduleCompanionSync()
            importedUserPreferences[session.userId]?.let { userPrefs ->
                val nonNull = userPrefs.filterValues { it != null }.mapValues { it.value!! }
                if (nonNull.isNotEmpty()) {
                    Timber.d("COMPANION: Applying ${nonNull.size} user-level preferences")
                    applyPreferences(nonNull)
                }
            }
        }
    }

    private fun applyPreferences(prefs: Map<String, String?>) {
        prefs.forEach { (key, value) ->
            try {
                if (key == appPreferences.voiceAssistantCloudApiKey.backendName) {
                    Timber.d("COMPANION: Setting cloud API key (length=${value?.length ?: 0})")
                }
                if (!appPreferences.applyCompanionPreference(key, value)) {
                    Timber.d("COMPANION: Ignoring unknown preference key: $key")
                }
            } catch (e: Exception) {
                Timber.w(e, "COMPANION: Failed to apply preference $key")
            }
        }
    }

    /**
     * Authenticate a companion user against the Jellyfin server.
     * Uses the pre-authenticated access_token from the companion app if available,
     * falling back to username/password authentication.
     */
    private suspend fun authenticateOrUseToken(u: CompanionUser, baseUrl: String, serverId: String): User? {
        // Try pre-authenticated token first
        if (u.accessToken != null) {
            Timber.d("COMPANION: Using pre-authenticated token for ${u.username} on $baseUrl")
            try {
                jellyfinApi.api.update(baseUrl = baseUrl, accessToken = u.accessToken)
                val userInfo = jellyfinApi.userApi.getCurrentUser().content
                val userId = userInfo.id
                Timber.d("COMPANION: Token valid for ${u.username}, userId=$userId")
                return User(
                    id = userId,
                    name = u.username,
                    serverId = serverId,
                    accessToken = u.accessToken,
                    preferences = serializeUserPrefs(u.preferences)
                )
            } catch (e: Exception) {
                Timber.w(e, "COMPANION: Pre-authenticated token invalid for ${u.username}, falling back to password auth")
            }
        }

        // Fall back to password authentication
        if (u.password != null) {
            Timber.d("COMPANION: Authenticating ${u.username} with password on $baseUrl")
            jellyfinApi.api.update(baseUrl = baseUrl, accessToken = null)
            val authResult = jellyfinApi.userApi.authenticateUserByName(
                data = AuthenticateUserByName(username = u.username, pw = u.password)
            ).content

            val userId = authResult.user?.id ?: UUID.randomUUID()
            Timber.d("COMPANION: Password auth succeeded for ${u.username}, userId=$userId, hasToken=${authResult.accessToken != null}")
            return User(
                id = userId,
                name = u.username,
                serverId = serverId,
                accessToken = authResult.accessToken,
                preferences = serializeUserPrefs(u.preferences)
            )
        }

        Timber.w("COMPANION: No access_token or password for ${u.username}, skipping")
        return null
    }

    private fun serializeUserPrefs(prefs: Map<String, String?>): String? {
        val nonNull = prefs.filterValues { it != null }.mapValues { it.value!! }
        return if (nonNull.isNotEmpty()) json.encodeToString(nonNull) else null
    }

    private fun scheduleCompanionSync() {
        val workManager = WorkManager.getInstance(context)
        val syncRequest = PeriodicWorkRequestBuilder<CompanionSyncWorker>(12, TimeUnit.HOURS)
            .addTag("CompanionSync")
            .build()

        workManager.enqueueUniquePeriodicWork(
            "CompanionSync",
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }

    private fun applyNetworkShares(shares: List<CompanionNetworkShare>) {
        Timber.d("COMPANION: Applying ${shares.size} network shares")
        shares.forEach { s ->
            val target: SmbConnectionTarget? = if (s.protocol.equals("smb", ignoreCase = true)) {
                SmbPathNormalizer.normalizeConnectionTarget(s.host, s.shareName)
            } else {
                null
            }
            val normalizedHost = target?.host ?: s.host
            val normalizedShareName = target?.shareName ?: s.shareName

            val share = NetworkShareDto(
                id = s.id,
                protocol = s.protocol,
                host = normalizedHost,
                shareName = normalizedShareName,
                path = s.path ?: "$normalizedHost/$normalizedShareName",
                displayName = s.displayName,
                username = s.username,
                password = s.password,
                domain = s.domain,
                addedAtEpochMs = s.addedAtEpochMs ?: System.currentTimeMillis(),
                lastScannedAtEpochMs = null
            )
            serverDatabase.insertNetworkShare(share)
        }
    }

}
