package dev.jdtech.jellyfin.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.toSpatialFinEpisode
import dev.jdtech.jellyfin.models.toSpatialFinMovie
import dev.jdtech.jellyfin.offline.OfflineSyncStatusMonitor
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto

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
        if (pendingChanges <= 0) return Result.success()

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
                            } ?: continue
                        for (user in serverWithAddressesAndUsers.users) {
                            jellyfinApi.apply {
                                api.update(baseUrl = serverAddress.address, accessToken = user.accessToken)
                                userId = user.id
                            }
                            val movies =
                                database.getMoviesByServerId(server.id).map {
                                    it.toSpatialFinMovie(database, user.id)
                                }
                            val episodes =
                                database.getEpisodesByServerId(server.id).map {
                                    it.toSpatialFinEpisode(database, user.id)
                                }

                            val movieResult = syncUserData(jellyfinApi, user, movies)
                            val episodeResult = syncUserData(jellyfinApi, user, episodes)
                            syncedChanges += movieResult.synced + episodeResult.synced
                            failedChanges += movieResult.failed + episodeResult.failed
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
        items: List<SpatialFinItem>,
    ): SyncResult {
        var syncedChanges = 0
        var failedChanges = 0
        for (item in items) {
            val userData = database.getUserDataToBeSynced(user.id, item.id) ?: continue

            try {
                jellyfinApi.itemsApi.updateItemUserData(
                    itemId = item.id,
                    userId = user.id,
                    data =
                        UpdateUserItemDataDto(
                            playbackPositionTicks = userData.playbackPositionTicks,
                            isFavorite = userData.favorite,
                            played = userData.played,
                        ),
                )

                database.setUserDataToBeSynced(user.id, item.id, false)
                syncedChanges++
            } catch (_: Exception) {
                failedChanges++
            }
        }
        return SyncResult(synced = syncedChanges, failed = failedChanges)
    }

    private data class SyncResult(
        val synced: Int,
        val failed: Int,
    )
}
