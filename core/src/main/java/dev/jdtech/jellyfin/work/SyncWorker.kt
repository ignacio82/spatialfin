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
import dev.jdtech.jellyfin.network.SafeUrlBlockedException
import dev.jdtech.jellyfin.offline.OfflineSyncStatusMonitor
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import timber.log.Timber

@HiltWorker
class SyncWorker
@AssistedInject
constructor(
    @Assisted private val context: Context,
    @Assisted private val workerParams: WorkerParameters,
    val database: ServerDatabaseDao,
    val offlineSyncStatusMonitor: OfflineSyncStatusMonitor,
    private val jellyfinApi: JellyfinApi,
    private val sessionActivationCoordinator: SessionActivationCoordinator,
    private val appLockObserver: AppLockObserver,
) : CoroutineWorker(context, workerParams) {

    private fun isAppLocked(): Boolean =
        appLockObserver.lockState.value == AppLockManager.LockState.LOCKED

    override suspend fun doWork(): Result {
        sessionActivationCoordinator.ensureInitialized()
        if (isAppLocked()) {
            return Result.success()
        }

        val pendingChanges = database.getPendingUserDataSyncCount()
        if (pendingChanges <= 0) {
            offlineSyncStatusMonitor.markSyncFinished(
                syncedChanges = 0,
                failedChanges = 0,
                errorMessage = null,
            )
            return Result.success()
        }

        if (isAppLocked()) {
            return Result.success()
        }

        offlineSyncStatusMonitor.markSyncStarted(pendingChanges)
        var finishedRecorded = false
        var lastSynced = 0
        var lastFailed = 0
        var lastError: String? = null

        return try {
            val syncResult =
                withContext(Dispatchers.IO) {
                    var syncedChanges = 0
                    var failedChanges = 0
                    val servers = database.getAllServersSync()

                    outer@ for (server in servers) {
                        if (isStopped || isAppLocked()) break@outer
                        val serverWithAddressesAndUsers =
                            database.getServerWithAddressesAndUsers(server.id) ?: continue
                        val serverAddress =
                            serverWithAddressesAndUsers.addresses.firstOrNull {
                                it.id == server.currentServerAddressId
                            } ?: serverWithAddressesAndUsers.addresses.firstOrNull()
                            ?: continue

                        for (user in serverWithAddressesAndUsers.users) {
                            if (isStopped || isAppLocked()) break@outer
                            val token = user.accessToken?.takeIf { it.isNotBlank() } ?: continue
                            val pendingForUser = database.getUserDataToBeSynced(user.id)
                            if (pendingForUser.isEmpty()) continue

                            // Re-verify server & user still exist in Room before building ApiClient
                            if (database.get(server.id) == null || database.getUser(user.id) == null) {
                                continue
                            }

                            val scopedApi = jellyfinApi.createApi(
                                baseUrl = serverAddress.address,
                                accessToken = token,
                            )
                            val userResult = syncUserData(scopedApi, server.id, user, pendingForUser)
                            syncedChanges += userResult.synced
                            failedChanges += userResult.failed
                        }
                    }

                    SyncResult(synced = syncedChanges, failed = failedChanges)
                }
            lastSynced = syncResult.synced
            lastFailed = syncResult.failed
            lastError = if (syncResult.failed > 0) "Some offline changes could not be synced" else null
            offlineSyncStatusMonitor.markSyncFinished(
                syncedChanges = lastSynced,
                failedChanges = lastFailed,
                errorMessage = lastError,
            )
            finishedRecorded = true
            if (syncResult.failed > 0) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastFailed = database.getPendingUserDataSyncCount().coerceAtLeast(1)
            lastError = e.message ?: "Offline sync failed"
            offlineSyncStatusMonitor.markSyncFinished(
                syncedChanges = lastSynced,
                failedChanges = lastFailed,
                errorMessage = lastError,
            )
            finishedRecorded = true
            Result.retry()
        } finally {
            if (!finishedRecorded) {
                offlineSyncStatusMonitor.markSyncFinished(
                    syncedChanges = lastSynced,
                    failedChanges = lastFailed,
                    errorMessage = lastError,
                )
            }
        }
    }

    private suspend fun syncUserData(
        scopedApi: ApiClient,
        serverId: String,
        user: User,
        pendingUserData: List<SpatialFinUserDataDto>,
    ): SyncResult {
        var syncedChanges = 0
        var failedChanges = 0
        for (userData in pendingUserData) {
            if (isStopped || isAppLocked()) break
            if (database.get(serverId) == null || database.getUser(user.id) == null) break
            try {
                scopedApi.userDataApi.updateItemUserData(
                    itemId = userData.itemId,
                    userId = user.id,
                    data =
                        UpdateUserItemDataDto(
                            playbackPositionTicks = userData.playbackPositionTicks,
                            isFavorite = userData.favorite,
                            played = userData.played,
                        ),
                )

                if (database.getUser(user.id) != null) {
                    database.setUserDataToBeSynced(user.id, userData.itemId, false)
                }
                syncedChanges++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isNonRetryingSkipError(e)) {
                    Timber.w(
                        e,
                        "SyncWorker: skipping blocked/unauthorized userdata sync for itemId=%s userId=%s",
                        userData.itemId,
                        user.id,
                    )
                } else if (isUnrecoverableUserDataSyncError(e)) {
                    Timber.w(
                        e,
                        "SyncWorker: dropping unrecoverable userdata sync for missing/invalid server itemId=%s userId=%s",
                        userData.itemId,
                        user.id,
                    )
                    if (database.getUser(user.id) != null) {
                        database.setUserDataToBeSynced(user.id, userData.itemId, false)
                    }
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
        internal fun isNonRetryingSkipError(throwable: Throwable): Boolean {
            var current: Throwable? = throwable
            val seen = mutableSetOf<Throwable>()
            while (current != null && seen.add(current)) {
                if (current is SafeUrlBlockedException) return true
                if (current is InvalidStatusException && (current.status == 401 || current.status == 403)) {
                    return true
                }
                current = current.cause
            }
            return false
        }

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
