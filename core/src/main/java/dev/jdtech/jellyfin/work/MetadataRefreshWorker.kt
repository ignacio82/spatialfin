package dev.jdtech.jellyfin.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.toSpatialFinEpisodeDto
import dev.jdtech.jellyfin.models.toSpatialFinMovieDto
import dev.jdtech.jellyfin.models.toSpatialFinSeasonDto
import dev.jdtech.jellyfin.models.toSpatialFinShowDto
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionMode
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

@HiltWorker
class MetadataRefreshWorker
@AssistedInject
constructor(
    @Assisted private val context: Context,
    @Assisted private val workerParams: WorkerParameters,
    private val database: ServerDatabaseDao,
    private val repository: JellyfinRepository,
    private val sessionActivationCoordinator: SessionActivationCoordinator,
    private val appLockObserver: AppLockObserver,
) : CoroutineWorker(context, workerParams) {

    private fun isFenceValid(serverId: String, expectedEpoch: Long): Boolean =
        !isStopped &&
            appLockObserver.lockState.value != AppLockManager.LockState.LOCKED &&
            !sessionActivationCoordinator.isActivating.get() &&
            sessionActivationCoordinator.sessionMode.value == SessionMode.SERVER_AUTHENTICATED &&
            sessionActivationCoordinator.sessionEpoch.get() == expectedEpoch &&
            sessionActivationCoordinator.sessionState.value.activeServerId == serverId

    override suspend fun doWork(): Result {
        if (isStopped || appLockObserver.lockState.value == AppLockManager.LockState.LOCKED) {
            return Result.success()
        }
        val session = sessionActivationCoordinator.ensureInitialized()
        if (session.sessionMode != SessionMode.SERVER_AUTHENTICATED) return Result.success()
        val serverId = session.activeServerId ?: return Result.success()
        val expectedEpoch = session.sessionEpoch

        return try {
            withContext(Dispatchers.IO) {
                val movieDtos = database.getMoviesByServerId(serverId)
                for (dto in movieDtos) {
                    if (!isFenceValid(serverId, expectedEpoch)) return@withContext
                    try {
                        val fresh = repository.getItem(dto.id) as? SpatialFinMovie ?: continue
                        if (!isFenceValid(serverId, expectedEpoch)) return@withContext
                        database.upsertMovie(fresh.toSpatialFinMovieDto(serverId))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "MetadataRefresh: failed to refresh movie %s", dto.id)
                    }
                }

                val episodeIdsToRefresh = buildSet {
                    database.getEpisodesByServerId(serverId).forEach { add(it.id) }
                    // Self-heal only downloaded sources belonging to snapshotted serverId.
                    database.getAllSources().forEach { source ->
                        val belongsToServer =
                            database.getDownloadTasksBySourceId(source.id).any {
                                database.resolveDownloadTaskServerId(it) == serverId
                            }
                        if (belongsToServer &&
                            database.getMovieOrNull(source.itemId) == null &&
                            database.getEpisodeOrNull(source.itemId) == null
                        ) {
                            add(source.itemId)
                        }
                    }
                }
                val refreshedShowIds = mutableSetOf<java.util.UUID>()
                val refreshedSeasonIds = mutableSetOf<java.util.UUID>()

                for (itemId in episodeIdsToRefresh) {
                    if (!isFenceValid(serverId, expectedEpoch)) return@withContext
                    try {
                        val freshItem = repository.getItem(itemId) ?: continue
                        if (!isFenceValid(serverId, expectedEpoch)) return@withContext
                        if (freshItem is SpatialFinMovie) {
                            database.upsertMovie(freshItem.toSpatialFinMovieDto(serverId))
                            continue
                        }
                        val fresh = freshItem as? SpatialFinEpisode ?: continue

                        if (refreshedShowIds.add(fresh.seriesId) || database.getShowOrNull(fresh.seriesId) == null) {
                            try {
                                val show = repository.getShow(fresh.seriesId)
                                if (!isFenceValid(serverId, expectedEpoch)) return@withContext
                                database.upsertShow(show.toSpatialFinShowDto(serverId))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Timber.w(e, "MetadataRefresh: failed to refresh show %s", fresh.seriesId)
                            }
                        }

                        if (refreshedSeasonIds.add(fresh.seasonId) || database.getSeasonOrNull(fresh.seasonId) == null) {
                            try {
                                val season = repository.getSeason(fresh.seasonId)
                                if (!isFenceValid(serverId, expectedEpoch)) return@withContext
                                database.upsertSeason(season.toSpatialFinSeasonDto())
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Timber.w(e, "MetadataRefresh: failed to refresh season %s", fresh.seasonId)
                            }
                        }

                        if (!isFenceValid(serverId, expectedEpoch)) return@withContext
                        database.upsertEpisode(fresh.toSpatialFinEpisodeDto(serverId))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "MetadataRefresh: failed to refresh episode %s", itemId)
                    }
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "MetadataRefresh: worker failed")
            Result.success()
        }
    }
}
