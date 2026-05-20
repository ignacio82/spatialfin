package dev.jdtech.jellyfin.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.jdtech.jellyfin.models.DownloadRequest
import dev.jdtech.jellyfin.models.DownloadMode
import dev.jdtech.jellyfin.models.SpatialFinSourceType
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.utils.Downloader
import timber.log.Timber
import java.util.UUID

@HiltWorker
class BulkDownloadResolutionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val downloader: Downloader,
    private val jellyfinRepository: JellyfinRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val itemIds = inputData.getStringArray(KEY_ITEM_IDS) ?: return Result.failure()
        val modeString = inputData.getString(KEY_MODE) ?: return Result.failure()
        val mode = runCatching { DownloadMode.valueOf(modeString) }.getOrElse { DownloadMode.ORIGINAL }
        val videoBitrate = inputData.getInt(KEY_VIDEO_BITRATE, -1).takeIf { it != -1 }

        for (idString in itemIds) {
            val itemId = runCatching { UUID.fromString(idString) }.getOrNull() ?: continue
            val item = runCatching { jellyfinRepository.getEpisode(itemId) }.getOrNull()
                ?: runCatching { jellyfinRepository.getMovie(itemId) }.getOrNull()
                ?: continue
                
            val sources = runCatching { jellyfinRepository.getMediaSources(itemId, true) }.getOrNull()
            val source = sources?.firstOrNull { it.type == SpatialFinSourceType.REMOTE }
            
            if (source == null) {
                Timber.w("BulkDownloadResolutionWorker: no remote source found for item %s", itemId)
                continue
            }
            
            val request = DownloadRequest(
                sourceId = source.id,
                mode = mode,
                videoBitrate = videoBitrate,
            )
            downloader.downloadItem(item, request)
        }
        
        return Result.success()
    }

    companion object {
        const val KEY_ITEM_IDS = "item_ids"
        const val KEY_MODE = "mode"
        const val KEY_VIDEO_BITRATE = "video_bitrate"
    }
}
