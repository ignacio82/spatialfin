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
import dev.jdtech.jellyfin.network.SmbPathNormalizer
import dev.jdtech.jellyfin.network.SmbConnectionTarget
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.models.companion.CompanionConfig
import dev.jdtech.jellyfin.models.companion.CompanionNetworkShare
import dev.jdtech.jellyfin.models.companion.CompanionUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import timber.log.Timber
import java.util.UUID
import dev.jdtech.jellyfin.settings.domain.applyCompanionPreference

private data class SyncedServerState(
    val serverId: String,
    val addressId: UUID,
    val userIds: Set<UUID>
)

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

        Timber.d("COMPANION SYNC: Background sync started from $url")

        try {
            val config = fetchConfig(url, token)
            applyConfig(config)
            appPreferences.setValue(appPreferences.lastCompanionSyncTime, System.currentTimeMillis())
            Timber.i("COMPANION SYNC: Background sync completed successfully")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "COMPANION SYNC: Background sync failed")
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
            Timber.d("COMPANION SYNC: Fetched config, length=${body.length}")
            val config = json.decodeFromString<CompanionConfig>(body)
            Timber.d("COMPANION SYNC: Parsed config: servers=${config.servers.size}, preferences=${config.preferences.size}")
            return config
        }
    }

    private suspend fun applyConfig(config: CompanionConfig) {
        // Apply Global Preferences
        Timber.d("COMPANION SYNC: Applying ${config.preferences.size} global preferences")
        applyPreferences(config.preferences)

        val syncedServers = mutableListOf<SyncedServerState>()
        val importedUserPreferences = mutableMapOf<UUID, Map<String, String?>>()
        val previousCurrentServer = appPreferences.getValue(appPreferences.currentServer)

        // Apply Servers and Users (Merge Logic)
        config.servers.forEach { s ->
            val serverId = s.id
            val rawAddress = s.addresses.firstOrNull() ?: ""
            val baseUrl = if (rawAddress.isNotEmpty() && !rawAddress.endsWith("/")) "$rawAddress/" else rawAddress

            val validUsers = mutableListOf<User>()
            s.users.forEach { u ->
                try {
                    val user = authenticateOrUseToken(u, baseUrl, serverId)
                    if (user != null) {
                        validUsers.add(user)
                        importedUserPreferences[user.id] = u.preferences
                        Timber.d("COMPANION SYNC: Added user ${u.username} (id=${user.id})")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "COMPANION SYNC: Failed to add user ${u.username}")
                }
            }

            Timber.d("COMPANION SYNC: Server ${s.name}: ${validUsers.size}/${s.users.size} users valid")

            if (validUsers.isNotEmpty()) {
                val existingServer = serverDatabase.get(serverId)
                val preferredCurrentUserId =
                    existingServer?.currentUserId?.takeIf { currentUserId ->
                        validUsers.any { it.id == currentUserId }
                    } ?: validUsers.first().id
                val server = Server(
                    id = serverId,
                    name = s.name,
                    currentServerAddressId = null,
                    currentUserId = preferredCurrentUserId
                )
                serverDatabase.insertServer(server)

                val addressId = UUID.randomUUID()
                serverDatabase.insertServerAddress(ServerAddress(id = addressId, serverId = serverId, address = baseUrl))
                server.currentServerAddressId = addressId
                serverDatabase.update(server)

                validUsers.forEach { serverDatabase.insertUser(it) }
                syncedServers.add(
                    SyncedServerState(
                        serverId = serverId,
                        addressId = addressId,
                        userIds = validUsers.map { it.id }.toSet()
                    )
                )
            }
        }

        val activeServerState =
            syncedServers.firstOrNull { it.serverId == previousCurrentServer }
                ?: syncedServers.firstOrNull()
        activeServerState?.let { synced ->
            val currentServer = serverDatabase.get(synced.serverId)
            val currentUserId =
                currentServer?.currentUserId?.takeIf { it in synced.userIds }
                    ?: synced.userIds.firstOrNull()
            if (currentServer != null) {
                currentServer.currentServerAddressId = synced.addressId
                currentUserId?.let { currentServer.currentUserId = it }
                serverDatabase.update(currentServer)
            }
            if (previousCurrentServer.isNullOrEmpty()) {
                appPreferences.setValue(appPreferences.currentServer, synced.serverId)
            }
            currentUserId?.let { userId ->
                importedUserPreferences[userId]?.let { userPrefs ->
                    val nonNull = userPrefs.filterValues { it != null }.mapValues { it.value!! }
                    if (nonNull.isNotEmpty()) {
                        Timber.d("COMPANION SYNC: Applying ${nonNull.size} user-level preferences")
                        applyPreferences(nonNull)
                    }
                }
            }
        }

        // Apply Network Shares
        Timber.d("COMPANION SYNC: Applying ${config.networkShares.size} network shares")
        config.networkShares.forEach { s ->
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

    private suspend fun authenticateOrUseToken(u: CompanionUser, baseUrl: String, serverId: String): User? {
        // Try pre-authenticated token first
        if (u.accessToken != null) {
            Timber.d("COMPANION SYNC: Using pre-authenticated token for ${u.username}")
            try {
                jellyfinApi.api.update(baseUrl = baseUrl, accessToken = u.accessToken)
                val userInfo = jellyfinApi.userApi.getCurrentUser().content
                return User(
                    id = userInfo.id,
                    name = u.username,
                    serverId = serverId,
                    accessToken = u.accessToken,
                    preferences = serializeUserPrefs(u.preferences)
                )
            } catch (e: Exception) {
                Timber.w(e, "COMPANION SYNC: Token invalid for ${u.username}, falling back to password")
            }
        }

        if (u.password != null) {
            Timber.d("COMPANION SYNC: Authenticating ${u.username} with password")
            jellyfinApi.api.update(baseUrl = baseUrl, accessToken = null)
            val authResult = jellyfinApi.userApi.authenticateUserByName(
                data = AuthenticateUserByName(username = u.username, pw = u.password)
            ).content

            val userId = authResult.user?.id ?: UUID.randomUUID()
            return User(
                id = userId,
                name = u.username,
                serverId = serverId,
                accessToken = authResult.accessToken,
                preferences = serializeUserPrefs(u.preferences)
            )
        }

        Timber.w("COMPANION SYNC: No access_token or password for ${u.username}, skipping")
        return null
    }

    private fun serializeUserPrefs(prefs: Map<String, String?>): String? {
        val nonNull = prefs.filterValues { it != null }.mapValues { it.value!! }
        return if (nonNull.isNotEmpty()) json.encodeToString(nonNull) else null
    }

    private fun applyPreferences(prefs: Map<String, String?>) {
        prefs.forEach { (key, value) ->
            try {
                if (key == appPreferences.voiceAssistantCloudApiKey.backendName) {
                    Timber.d("COMPANION SYNC: Setting cloud API key (length=${value?.length ?: 0})")
                }
                if (!appPreferences.applyCompanionPreference(key, value)) {
                    Timber.d("COMPANION SYNC: Ignoring unknown preference key: $key")
                }
            } catch (e: Exception) {
                Timber.w(e, "COMPANION SYNC: Failed to apply preference $key")
            }
        }
    }
}
