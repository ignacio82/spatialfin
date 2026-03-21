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
import dev.jdtech.jellyfin.models.companion.CompanionConfig
import dev.jdtech.jellyfin.models.companion.CompanionDiscoveryPayload
import dev.jdtech.jellyfin.models.companion.CompanionNetworkShare
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
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

@HiltViewModel
class CompanionViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val serverDatabase: ServerDatabaseDao,
    private val jellyfinApi: JellyfinApi,
    @ApplicationContext private val context: Context
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
        val request = Request.Builder()
            .url("${payload.companion_url}/api/v1/config")
            .addHeader("X-Setup-Token", payload.setup_token)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body = response.body?.string() ?: throw Exception("Empty response")
            json.decodeFromString<CompanionConfig>(body)
        }
    }

    private suspend fun applyConfig(config: CompanionConfig) = withContext(Dispatchers.IO) {
        // Apply Global Preferences first
        applyPreferences(config.preferences)

        var firstValidServerId: String? = null
        var firstValidAddressId: UUID? = null
        var firstValidUserId: UUID? = null

        // Apply Servers and Users
        config.servers.forEach { s ->
            val serverId = s.id
            var serverAddressId: UUID? = null
            var serverUserId: UUID? = null
            
            val validUsers = mutableListOf<User>()
            
            // Normalize URL (must have trailing slash for Jellyfin SDK)
            val rawAddress = s.addresses.firstOrNull() ?: ""
            val baseUrl = if (rawAddress.isNotEmpty() && !rawAddress.endsWith("/")) "$rawAddress/" else rawAddress

            s.users.forEach { u ->
                try {
                    Timber.d("COMPANION: Authenticating user ${u.username} on $baseUrl")
                    jellyfinApi.api.update(baseUrl = baseUrl)
                    
                    val authResult = jellyfinApi.userApi.authenticateUserByName(
                        data = AuthenticateUserByName(username = u.username, pw = u.password)
                    ).content

                    val userId = authResult.user?.id ?: UUID.randomUUID()
                    val user = User(
                        id = userId,
                        name = u.username,
                        serverId = serverId,
                        accessToken = authResult.accessToken,
                        preferences = if (u.preferences.isNotEmpty()) json.encodeToString(u.preferences) else null
                    )
                    validUsers.add(user)
                    
                    if (serverUserId == null) serverUserId = userId
                } catch (e: Exception) {
                    Timber.e(e, "COMPANION: Failed to authenticate user ${u.username} on ${s.name}")
                }
            }

            // Only insert server if we have at least one valid user
            if (validUsers.isNotEmpty()) {
                val server = Server(
                    id = serverId,
                    name = s.name,
                    currentServerAddressId = null,
                    currentUserId = serverUserId
                )
                serverDatabase.insertServer(server)

                // Insert Address
                val addressId = UUID.randomUUID()
                val serverAddress = ServerAddress(id = addressId, serverId = serverId, address = baseUrl)
                serverDatabase.insertServerAddress(serverAddress)
                
                server.currentServerAddressId = addressId
                serverDatabase.update(server)
                
                validUsers.forEach { serverDatabase.insertUser(it) }

                if (firstValidServerId == null) {
                    firstValidServerId = serverId
                    firstValidAddressId = addressId
                    firstValidUserId = serverUserId
                }
            }
        }

        // Apply Network Shares
        applyNetworkShares(config.networkShares)
        
        // If we found any valid server, set it as active
        firstValidServerId?.let { 
            appPreferences.setValue(appPreferences.currentServer, it)
            appPreferences.setValue(appPreferences.onboardingCompleted, true)
            scheduleCompanionSync()
        }
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
        shares.forEach { s ->
            val share = NetworkShareDto(
                id = s.id,
                protocol = s.protocol,
                host = s.host,
                shareName = s.shareName,
                path = s.path ?: "",
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

    private fun applyPreferences(prefs: Map<String, String>) {
        prefs.forEach { (key, value) ->
            try {
                when (key) {
                    appPreferences.preferredAudioLanguage.backendName -> appPreferences.setValue(appPreferences.preferredAudioLanguage, value)
                    appPreferences.preferredSubtitleLanguage.backendName -> appPreferences.setValue(appPreferences.preferredSubtitleLanguage, value)
                    appPreferences.animeAudioLanguage.backendName -> appPreferences.setValue(appPreferences.animeAudioLanguage, value)
                    appPreferences.animeSubtitleLanguage.backendName -> appPreferences.setValue(appPreferences.animeSubtitleLanguage, value)
                    appPreferences.nonAnimeAudioLanguage.backendName -> appPreferences.setValue(appPreferences.nonAnimeAudioLanguage, value)
                    appPreferences.nonAnimeSubtitleLanguage.backendName -> appPreferences.setValue(appPreferences.nonAnimeSubtitleLanguage, value)
                    appPreferences.nonAnimeSubtitleDisabled.backendName -> appPreferences.setValue(appPreferences.nonAnimeSubtitleDisabled, value.toBoolean())
                    appPreferences.smartPreferOriginalAudio.backendName -> appPreferences.setValue(appPreferences.smartPreferOriginalAudio, value.toBoolean())
                    appPreferences.smartSpokenLanguages.backendName -> appPreferences.setValue(appPreferences.smartSpokenLanguages, value)
                    appPreferences.voiceAssistantCloudApiKey.backendName -> appPreferences.setValue(appPreferences.voiceAssistantCloudApiKey, value)
                    appPreferences.tmdbApiKey.backendName -> appPreferences.setValue(appPreferences.tmdbApiKey, value)
                    appPreferences.seerrEnabled.backendName -> appPreferences.setValue(appPreferences.seerrEnabled, value.toBoolean())
                    appPreferences.seerrUrl.backendName -> appPreferences.setValue(appPreferences.seerrUrl, value)
                    appPreferences.seerrApiKey.backendName -> appPreferences.setValue(appPreferences.seerrApiKey, value)
                }
            } catch (e: Exception) {
                Timber.w("COMPANION: Failed to apply preference $key: ${e.message}")
            }
        }
    }
}
