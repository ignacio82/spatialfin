package dev.jdtech.jellyfin.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.NetworkShareDto
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.models.companion.CompanionConfig
import dev.jdtech.jellyfin.models.companion.CompanionNetworkShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import timber.log.Timber
import java.util.UUID

@HiltWorker
class CompanionSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val appPreferences: AppPreferences,
    private val serverDatabase: ServerDatabaseDao,
    private val jellyfinApi: JellyfinApi
) : CoroutineWorker(context, params) {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder().build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = appPreferences.getValue(appPreferences.companionUrl)
        val token = appPreferences.getValue(appPreferences.companionToken)

        if (url.isEmpty() || token.isEmpty()) {
            return@withContext Result.success()
        }

        Timber.d("COMPANION: Background sync started from $url")

        try {
            val config = fetchConfig(url, token)
            applyConfig(config)
            appPreferences.setValue(appPreferences.lastCompanionSyncTime, System.currentTimeMillis())
            Timber.i("COMPANION: Background sync completed successfully")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "COMPANION: Background sync failed")
            Result.retry()
        }
    }

    private suspend fun fetchConfig(url: String, token: String): CompanionConfig {
        val request = Request.Builder()
            .url("$url/api/v1/config")
            .addHeader("X-Setup-Token", token)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body = response.body?.string() ?: throw Exception("Empty response")
            return json.decodeFromString<CompanionConfig>(body)
        }
    }

    private suspend fun applyConfig(config: CompanionConfig) {
        // Apply Preferences
        applyPreferences(config.preferences)

        // Apply Servers and Users (Merge Logic)
        config.servers.forEach { s ->
            val serverId = s.id
            val rawAddress = s.addresses.firstOrNull() ?: ""
            val baseUrl = if (rawAddress.isNotEmpty() && !rawAddress.endsWith("/")) "$rawAddress/" else rawAddress

            val validUsers = mutableListOf<User>()
            s.users.forEach { u ->
                try {
                    jellyfinApi.api.update(baseUrl = baseUrl)
                    val authResult = jellyfinApi.userApi.authenticateUserByName(
                        data = AuthenticateUserByName(username = u.username, pw = u.password)
                    ).content

                    val userId = authResult.user?.id ?: UUID.randomUUID()
                    validUsers.add(User(
                        id = userId,
                        name = u.username,
                        serverId = serverId,
                        accessToken = authResult.accessToken,
                        preferences = if (u.preferences.isNotEmpty()) json.encodeToString(u.preferences) else null
                    ))
                } catch (e: Exception) {
                    Timber.e(e, "COMPANION SYNC: Failed to authenticate user ${u.username}")
                }
            }

            if (validUsers.isNotEmpty()) {
                val existingServer = serverDatabase.get(serverId)
                val server = Server(
                    id = serverId,
                    name = s.name,
                    currentServerAddressId = existingServer?.currentServerAddressId,
                    currentUserId = existingServer?.currentUserId ?: validUsers.first().id
                )
                serverDatabase.insertServer(server)

                // Add address if not exists
                val addressId = UUID.randomUUID()
                serverDatabase.insertServerAddress(ServerAddress(id = addressId, serverId = serverId, address = baseUrl))
                if (server.currentServerAddressId == null) {
                    server.currentServerAddressId = addressId
                    serverDatabase.update(server)
                }
                
                validUsers.forEach { serverDatabase.insertUser(it) }
            }
        }

        // Apply Network Shares
        config.networkShares.forEach { s ->
            val share = NetworkShareDto(
                id = s.id, protocol = s.protocol, host = s.host, shareName = s.shareName,
                path = s.path ?: "", displayName = s.displayName, username = s.username,
                password = s.password, domain = s.domain, addedAtEpochMs = s.addedAtEpochMs ?: System.currentTimeMillis(),
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
                    appPreferences.smartPreferOriginalAudio.backendName -> appPreferences.setValue(appPreferences.smartPreferOriginalAudio, value.toBoolean())
                    appPreferences.smartSpokenLanguages.backendName -> appPreferences.setValue(appPreferences.smartSpokenLanguages, value)
                    appPreferences.voiceAssistantCloudApiKey.backendName -> appPreferences.setValue(appPreferences.voiceAssistantCloudApiKey, value)
                    appPreferences.tmdbApiKey.backendName -> appPreferences.setValue(appPreferences.tmdbApiKey, value)
                    appPreferences.seerrEnabled.backendName -> appPreferences.setValue(appPreferences.seerrEnabled, value.toBoolean())
                    appPreferences.seerrUrl.backendName -> appPreferences.setValue(appPreferences.seerrUrl, value)
                    appPreferences.seerrApiKey.backendName -> appPreferences.setValue(appPreferences.seerrApiKey, value)
                }
            } catch (_: Exception) {}
        }
    }
}
