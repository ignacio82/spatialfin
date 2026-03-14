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
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val jellyfinApi =
            JellyfinApi(
                androidContext = context.applicationContext,
                requestTimeout = appPreferences.getValue(appPreferences.requestTimeout),
                connectTimeout = appPreferences.getValue(appPreferences.connectTimeout),
                socketTimeout = appPreferences.getValue(appPreferences.socketTimeout),
            )

        return withContext(Dispatchers.IO) {
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

                    syncUserData(jellyfinApi, user, movies)
                    syncUserData(jellyfinApi, user, episodes)
                }
            }

            Result.success()
        }
    }

    private suspend fun syncUserData(
        jellyfinApi: JellyfinApi,
        user: User,
        items: List<SpatialFinItem>,
    ) {
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
            } catch (_: Exception) {}
        }
    }
}
