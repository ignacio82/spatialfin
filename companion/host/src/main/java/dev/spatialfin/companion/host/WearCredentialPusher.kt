package dev.spatialfin.companion.host

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.spatialfin.companion.protocol.WearCredentials
import dev.spatialfin.companion.protocol.WearProtocolCodec
import dev.spatialfin.companion.protocol.WearProtocolPaths
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WearCredentialPusher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appPreferences: AppPreferences,
    private val serverDao: ServerDatabaseDao,
    private val repository: JellyfinRepository,
) {

    suspend fun pushCredentials(): Boolean {
        val currentServerId = appPreferences.getValue(appPreferences.currentServer) ?: return false
        val serverData = serverDao.getServerWithAddressAndUser(currentServerId) ?: return false
        val address = serverData.address?.address ?: return false
        val user = serverData.user ?: return false
        val token = user.accessToken ?: return false

        val creds = WearCredentials(
            serverUrl = address,
            accessToken = token,
            userId = user.id.toString(),
            // The *host's* Jellyfin device id. The watch uses it to pick this host out of
            // the server's live session feed when relaying commands in standalone mode.
            deviceId = repository.getDeviceId().orEmpty(),
            serverId = serverData.server.id,
            serverName = serverData.server.name,
            username = user.name,
        )

        Timber.i("WearCredentialPusher: pushing credentials for %s (%s) to watch", creds.serverName, creds.username)
        val payload = WearProtocolCodec.encodeCredentials(creds)
        // Written as a DataMap (not a raw setData body) so the watch can read every
        // /state/* item through the same DataMapItem path. A raw body makes
        // DataMapItem.fromDataItem throw and takes the whole listener loop down.
        val request = PutDataMapRequest.create(WearProtocolPaths.PATH_STATE_CREDENTIALS).apply {
            dataMap.putByteArray(WearProtocolPaths.DATA_KEY_PAYLOAD, payload)
            dataMap.putLong(WearProtocolPaths.DATA_KEY_TIMESTAMP, System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()

        return runCatching {
            Wearable.getDataClient(context).putDataItem(request).await()
            Timber.d("WearCredentialPusher: credentials successfully published to DataClient")
            true
        }.onFailure {
            Timber.w(it, "WearCredentialPusher: failed to put credentials data item")
        }.getOrDefault(false)
    }
}
