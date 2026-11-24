package dev.spatialfin.companion.host

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.session.ActiveSessionBus
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.spatialfin.companion.protocol.WearCredentials
import dev.spatialfin.companion.protocol.WearProtocolCodec
import dev.spatialfin.companion.protocol.WearProtocolPaths
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import timber.log.Timber

@Singleton
class WearCredentialPusher
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val appPreferences: AppPreferences,
    private val serverDao: ServerDatabaseDao,
    private val repository: JellyfinRepository,
    private val sessionBus: ActiveSessionBus,
    private val appLockObserver: AppLockObserver,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val publishMutex = Mutex()
    private var observationJob: Job? = null

    fun startObserving() {
        if (observationJob != null) return
        observationJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                launch {
                    sessionBus.events.collect { pushCredentials() }
                }
                launch {
                    var previous: AppLockManager.LockState? = null
                    appLockObserver.lockState.collect { state ->
                        if (previous == AppLockManager.LockState.LOCKED &&
                            state != AppLockManager.LockState.LOCKED
                        ) {
                            pushCredentials()
                        }
                        previous = state
                    }
                }
            }
    }

    private fun currentCredentials(): WearCredentials? {
        val id = appPreferences.getValue(appPreferences.currentServer) ?: return null
        val data = serverDao.getServerWithAddressAndUser(id) ?: return null
        val address = data.address?.address?.takeIf { it.isNotBlank() } ?: return null
        val user = data.user ?: return null
        val token = user.accessToken?.takeIf { it.isNotBlank() } ?: return null
        return WearCredentials(
            address,
            token,
            user.id.toString(),
            repository.getDeviceId().orEmpty(),
            data.server.id,
            data.server.name,
            user.name,
        )
    }

    suspend fun pushCredentials(): Boolean = publishMutex.withLock {
        if (appLockObserver.lockState.value == AppLockManager.LockState.LOCKED) {
            Timber.d("WearCredentialPusher: skipping credential push while App Lock is LOCKED")
            return@withLock false
        }
        val creds = currentCredentials()
        val request =
            PutDataMapRequest.create(WearProtocolPaths.PATH_STATE_CREDENTIALS)
                .apply {
                    dataMap.putBoolean(WearProtocolPaths.DATA_KEY_REVOKED, creds == null)
                    creds?.let {
                        dataMap.putByteArray(
                            WearProtocolPaths.DATA_KEY_PAYLOAD,
                            WearProtocolCodec.encodeCredentials(it),
                        )
                    }
                    dataMap.putLong(
                        WearProtocolPaths.DATA_KEY_TIMESTAMP,
                        System.currentTimeMillis(),
                    )
                }
                .asPutDataRequest()
                .setUrgent()

        runCatching {
                Wearable.getDataClient(context).putDataItem(request).await()
                Timber.d("WearCredentialPusher: credentials successfully published to DataClient")
                true
            }
            .onFailure { Timber.w(it, "WearCredentialPusher: failed to put credentials data item") }
            .getOrDefault(false)
    }
}
