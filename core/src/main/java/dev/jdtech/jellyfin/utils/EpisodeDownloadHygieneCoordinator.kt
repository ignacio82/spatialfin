package dev.jdtech.jellyfin.utils

import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.BulkDownloadSettings
import dev.jdtech.jellyfin.models.DownloadMode
import dev.jdtech.jellyfin.models.DownloadTaskKind
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinSourceType
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.models.toSpatialFinEpisode
import dev.jdtech.jellyfin.models.toSpatialFinSource
import dev.jdtech.jellyfin.player.core.downloads.EpisodePlaybackStopListener
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class EpisodeDownloadHygieneCoordinator(
    private val database: ServerDatabaseDao,
    private val repository: JellyfinRepository,
    private val downloader: Downloader,
    private val appPreferences: AppPreferences,
) : EpisodePlaybackStopListener {

    override suspend fun onEpisodePlaybackStopped(
        itemId: UUID,
        playedPercentage: Int,
        markedPlayed: Boolean,
    ) {
        val isWatched = playedPercentage > 90 || markedPlayed
        if (!isWatched) return

        val autoDelete = appPreferences.getValue(appPreferences.autoDeleteWatchedDownloads)
        val nextCount = appPreferences.getValue(appPreferences.autoDownloadNextEpisodesCount).coerceIn(0, 2)
        if (!autoDelete && nextCount <= 0) return

        withContext(Dispatchers.IO) {
            val userId = runCatching { repository.getUserId() }.getOrNull()
            val localEpisodeDto = database.getEpisodeOrNull(itemId)
            val localSources = database.getSources(itemId).filter { it.type == SpatialFinSourceType.LOCAL }
            val localEpisode = if (localEpisodeDto != null && userId != null) {
                runCatching { localEpisodeDto.toSpatialFinEpisode(database, userId) }.getOrNull()
            } else {
                null
            }

            val hadLocalDownload = localSources.isNotEmpty() ||
                localEpisode?.sources?.any { it.type == SpatialFinSourceType.LOCAL } == true
            if (!hadLocalDownload) return@withContext

            val primaryTask = database.getDownloadTasksByItemId(itemId)
                .firstOrNull { it.kind == DownloadTaskKind.PRIMARY }
            val wasTranscoded =
                primaryTask?.finalPath?.endsWith("_transcoded.mp4", ignoreCase = true) == true ||
                    primaryTask?.requestUrl?.contains("Static=false", ignoreCase = true) == true ||
                    localSources.any { it.path.endsWith("_transcoded.mp4", ignoreCase = true) }

            val episode = localEpisode
                ?: runCatching { repository.getItem(itemId) as? SpatialFinEpisode }.getOrNull()

            if (autoDelete && episode != null) {
                val sourceToDelete =
                    episode.sources.firstOrNull { it.type == SpatialFinSourceType.LOCAL }
                        ?: localSources.firstOrNull()?.toSpatialFinSource(database)
                if (sourceToDelete != null) {
                    runCatching {
                        Timber.i("EpisodeDownloadHygiene: auto-deleting watched episode %s (%s)", episode.name, itemId)
                        downloader.deleteItem(episode, sourceToDelete)
                    }.onFailure { e ->
                        Timber.w(e, "EpisodeDownloadHygiene: failed to auto-delete watched episode %s", itemId)
                    }
                }
            }

            if (nextCount > 0 && episode != null) {
                runCatching {
                    val candidates = resolveNextUnwatchedEpisodes(
                        seriesId = episode.seriesId,
                        seasonId = episode.seasonId,
                        currentSeasonNumber = episode.parentIndexNumber,
                        currentEpisodeNumber = episode.indexNumber,
                        finishedItemId = itemId,
                        limit = nextCount,
                    )
                    if (candidates.isNotEmpty()) {
                        Timber.i(
                            "EpisodeDownloadHygiene: auto-queuing %d next episode(s) for series=%s",
                            candidates.size,
                            episode.seriesId,
                        )
                        downloader.downloadItems(
                            episodes = candidates,
                            settings = BulkDownloadSettings(
                                mode = if (wasTranscoded) DownloadMode.TRANSCODED else DownloadMode.ORIGINAL,
                            ),
                        )
                    }
                }.onFailure { e ->
                    Timber.w(e, "EpisodeDownloadHygiene: failed to queue next episodes after %s", itemId)
                }
            }
        }
    }

    private suspend fun resolveNextUnwatchedEpisodes(
        seriesId: UUID,
        seasonId: UUID,
        currentSeasonNumber: Int,
        currentEpisodeNumber: Int,
        finishedItemId: UUID,
        limit: Int,
    ): List<SpatialFinEpisode> {
        val collected = mutableListOf<SpatialFinEpisode>()
        val activeTaskItemIds = database.getAllDownloadTasks()
            .map { it.itemId }
            .toSet()

        val currentSeasonEpisodes = runCatching {
            repository.getEpisodes(
                seriesId = seriesId,
                seasonId = seasonId,
                offline = false,
            )
        }.getOrDefault(emptyList())

        currentSeasonEpisodes
            .sortedBy { it.indexNumber }
            .filter { ep ->
                ep.id != finishedItemId &&
                    ep.indexNumber > currentEpisodeNumber &&
                    !ep.played &&
                    !ep.isDownloaded() &&
                    ep.id !in activeTaskItemIds
            }
            .forEach { ep ->
                if (collected.size < limit) {
                    collected.add(ep)
                }
            }

        if (collected.size >= limit) return collected

        val seasons = runCatching {
            repository.getSeasons(seriesId = seriesId, offline = false)
        }.getOrDefault(emptyList())
            .filter { it.indexNumber > currentSeasonNumber && it.indexNumber > 0 }
            .sortedBy { it.indexNumber }

        for (nextSeason in seasons) {
            if (collected.size >= limit) break
            val seasonEpisodes = runCatching {
                repository.getEpisodes(
                    seriesId = seriesId,
                    seasonId = nextSeason.id,
                    offline = false,
                )
            }.getOrDefault(emptyList())

            seasonEpisodes
                .sortedBy { it.indexNumber }
                .filter { ep ->
                    ep.id != finishedItemId &&
                        !ep.played &&
                        !ep.isDownloaded() &&
                        ep.id !in activeTaskItemIds
                }
                .forEach { ep ->
                    if (collected.size < limit) {
                        collected.add(ep)
                    }
                }
        }

        return collected
    }
}
