package dev.jdtech.jellyfin.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.SpatialFinUserDataDto
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.offline.OfflineSyncStatusMonitor
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import timber.log.Timber

@HiltWorker
class SyncWorker
@AssistedInject
constructor(
    @Assisted private val context: Context,
    @Assisted private val workerParams: WorkerParameters,
    val database: ServerDatabaseDao,
    val appPreferences: AppPreferences,
    val offlineSyncStatusMonitor: OfflineSyncStatusMonitor,
    private val jellyfinApi: JellyfinApi,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val pendingChanges = database.getPendingUserDataSyncCount()
        if (pendingChanges <= 0) {
            offlineSyncStatusMonitor.markSyncFinished(
                syncedChanges = 0,
                failedChanges = 0,
                errorMessage = null,
            )
            return Result.success()
        }

        offlineSyncStatusMonitor.markSyncStarted(pendingChanges)
        
        val originalBaseUrl = jellyfinApi.api.baseUrl
        val originalAccessToken = jellyfinApi.api.accessToken
        val originalUserId = jellyfinApi.userId

        return try {
            val syncResult =
                withContext(Dispatchers.IO) {
                    var syncedChanges = 0
                    var failedChanges = 0
                    val servers = database.getAllServersSync()

                    for (server in servers) {
                        val serverWithAddressesAndUsers =
                            database.getServerWithAddressesAndUsers(server.id) ?: continue
                        val serverAddress =
                            serverWithAddressesAndUsers.addresses.firstOrNull {
                                it.id == server.currentServerAddressId
                            } ?: serverWithAddressesAndUsers.addresses.firstOrNull()
                            ?: continue
                        if (server.currentServerAddressId != serverAddress.id) {
                            database.updateServerCurrentAddress(server.id, serverAddress.id)
                        }
                        for (user in serverWithAddressesAndUsers.users) {
                            val pendingForUser = database.getUserDataToBeSynced(user.id)
                            if (pendingForUser.isEmpty()) continue

                            jellyfinApi.apply {
                                api.update(baseUrl = serverAddress.address, accessToken = user.accessToken)
                                userId = user.id
                            }

                            val userResult = syncUserData(jellyfinApi, user, pendingForUser)
                            syncedChanges += userResult.synced
                            failedChanges += userResult.failed
                        }
                    }

                    SyncResult(synced = syncedChanges, failed = failedChanges)
                }
            offlineSyncStatusMonitor.markSyncFinished(
                syncedChanges = syncResult.synced,
                failedChanges = syncResult.failed,
                errorMessage =
                    if (syncResult.failed > 0) {
                        "Some offline changes could not be synced"
                    } else {
                        null
                    },
            )
            if (syncResult.failed > 0) Result.retry() else Result.success()
        } catch (e: Exception) {
            offlineSyncStatusMonitor.markSyncFinished(
                syncedChanges = 0,
                failedChanges = database.getPendingUserDataSyncCount().coerceAtLeast(1),
                errorMessage = e.message ?: "Offline sync failed",
            )
            Result.retry()
        } finally {
            jellyfinApi.apply {
                api.update(baseUrl = originalBaseUrl, accessToken = originalAccessToken)
                userId = originalUserId
            }
        }
    }

    private suspend fun syncUserData(
        jellyfinApi: JellyfinApi,
        user: User,
        pendingUserData: List<SpatialFinUserDataDto>,
    ): SyncResult {
        var syncedChanges = 0
        var failedChanges = 0
        for (userData in pendingUserData) {
            try {
                jellyfinApi.itemsApi.updateItemUserData(
                    itemId = userData.itemId,
                    userId = user.id,
                    data =
                        UpdateUserItemDataDto(
                            playbackPositionTicks = userData.playbackPositionTicks,
                            isFavorite = userData.favorite,
                            played = userData.played,
                        ),
                )

                database.setUserDataToBeSynced(user.id, userData.itemId, false)
                syncedChanges++
            } catch (e: Exception) {
                if (isUnrecoverableUserDataSyncError(e)) {
                    Timber.w(
                        e,
                        "SyncWorker: dropping unrecoverable userdata sync for missing/invalid server itemId=%s userId=%s",
                        userData.itemId,
                        user.id,
                    )
                    database.setUserDataToBeSynced(user.id, userData.itemId, false)
                    syncedChanges++
                } else {
                    Timber.w(
                        e,
                        "SyncWorker: transient failure syncing userdata for itemId=%s userId=%s",
                        userData.itemId,
                        user.id,
                    )
                    failedChanges++
                }
            }
        }
        return SyncResult(synced = syncedChanges, failed = failedChanges)
    }

    private data class SyncResult(
        val synced: Int,
        val failed: Int,
    )

    companion object {
        internal fun isUnrecoverableUserDataSyncError(throwable: Throwable): Boolean {
            var current: Throwable? = throwable
            val seen = mutableSetOf<Throwable>()
            while (current != null && seen.add(current)) {
                if (current is InvalidStatusException) {
                    if (current.status == 400 || current.status == 404 || current.status == 410) {
                        return true
                    }
                }
                current = current.cause
            }
            return false
        }
    }
}
