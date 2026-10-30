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

                val episodeIdsToRefresh = buildSet {
                    database.getEpisodesByServerId(serverId).forEach { add(it.id) }
                    // Self-heal any downloaded sources whose episode/movie row was previously
                    // cascade-deleted when updating parent show/season metadata.
                    database.getAllSources().forEach { source ->
                        if (database.getMovieOrNull(source.itemId) == null && database.getEpisodeOrNull(source.itemId) == null) {
                            add(source.itemId)
                        }
                    }
                }
                val refreshedShowIds = mutableSetOf<java.util.UUID>()
                val refreshedSeasonIds = mutableSetOf<java.util.UUID>()

                for (itemId in episodeIdsToRefresh) {
                    try {
                        val freshItem = repository.getItem(itemId) ?: continue
                        if (freshItem is SpatialFinMovie) {
                            database.upsertMovie(freshItem.toSpatialFinMovieDto(serverId))
                            continue
                        }
                        val fresh = freshItem as? SpatialFinEpisode ?: continue

                        // Always upsert parent show and season BEFORE the child episode so
                        // foreign key constraints are satisfied even when recovering missing rows.
                        if (refreshedShowIds.add(fresh.seriesId) || database.getShowOrNull(fresh.seriesId) == null) {
                            try {
                                val show = repository.getShow(fresh.seriesId)
                                database.upsertShow(show.toSpatialFinShowDto(serverId))
                            } catch (e: Exception) {
                                Timber.w(e, "MetadataRefresh: failed to refresh show %s", fresh.seriesId)
                            }
                        }

                        if (refreshedSeasonIds.add(fresh.seasonId) || database.getSeasonOrNull(fresh.seasonId) == null) {
                            try {
                                val season = repository.getSeason(fresh.seasonId)
                                database.upsertSeason(season.toSpatialFinSeasonDto())
                            } catch (e: Exception) {
                                Timber.w(e, "MetadataRefresh: failed to refresh season %s", fresh.seasonId)
                            }
                        }

                        database.upsertEpisode(fresh.toSpatialFinEpisodeDto(serverId))
                    } catch (e: Exception) {
                        Timber.w(e, "MetadataRefresh: failed to refresh episode %s", itemId)
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
