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
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.models.toSpatialFinEpisodeDto
import dev.jdtech.jellyfin.models.toSpatialFinMovieDto
import dev.jdtech.jellyfin.models.toSpatialFinSeasonDto
import dev.jdtech.jellyfin.models.toSpatialFinShowDto
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
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
    private val appPreferences: AppPreferences,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val serverId = appPreferences.getValue(appPreferences.currentServer) ?: return Result.success()

        return try {
            withContext(Dispatchers.IO) {
                val movieDtos = database.getMoviesByServerId(serverId)
                for (dto in movieDtos) {
                    try {
                        val fresh = repository.getItem(dto.id) as? SpatialFinMovie ?: continue
                        database.upsertMovie(fresh.toSpatialFinMovieDto(serverId))
                    } catch (e: Exception) {
                        Timber.w(e, "MetadataRefresh: failed to refresh movie %s", dto.id)
                    }
                }

                val episodeDtos = database.getEpisodesByServerId(serverId)
                val refreshedShowIds = mutableSetOf<java.util.UUID>()
                val refreshedSeasonIds = mutableSetOf<java.util.UUID>()

                for (dto in episodeDtos) {
                    try {
                        val fresh = repository.getItem(dto.id) as? SpatialFinEpisode ?: continue
                        database.upsertEpisode(fresh.toSpatialFinEpisodeDto(serverId))

                        if (refreshedShowIds.add(fresh.seriesId)) {
                            try {
                                val show = repository.getShow(fresh.seriesId)
                                database.upsertShow(show.toSpatialFinShowDto(serverId))
                            } catch (e: Exception) {
                                Timber.w(e, "MetadataRefresh: failed to refresh show %s", fresh.seriesId)
                            }
                        }

                        if (refreshedSeasonIds.add(fresh.seasonId)) {
                            try {
                                val season = repository.getSeason(fresh.seasonId)
                                database.upsertSeason(season.toSpatialFinSeasonDto())
                            } catch (e: Exception) {
                                Timber.w(e, "MetadataRefresh: failed to refresh season %s", fresh.seasonId)
                            }
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "MetadataRefresh: failed to refresh episode %s", dto.id)
                    }
                }
            }
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "MetadataRefresh: worker failed")
            Result.success()
        }
    }
}
