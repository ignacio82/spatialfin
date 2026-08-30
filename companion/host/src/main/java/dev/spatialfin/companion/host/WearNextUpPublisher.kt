package dev.spatialfin.companion.host

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.spatialfin.companion.protocol.WearNextUpItem
import dev.spatialfin.companion.protocol.WearNextUpState
import dev.spatialfin.companion.protocol.WearProtocolCodec
import dev.spatialfin.companion.protocol.WearProtocolPaths
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WearNextUpPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: JellyfinRepository,
) {

    suspend fun publishNextUp(): Boolean {
        return runCatching {
            val episodes = repository.getNextUp()
            val items = episodes.take(10).map { ep ->
                WearNextUpItem(
                    id = ep.id.toString(),
                    title = ep.name,
                    seriesName = ep.seriesName,
                    seasonNumber = ep.parentIndexNumber,
                    episodeNumber = ep.indexNumber,
                    overview = ep.overview,
                    mediaType = "Episode",
                    durationSeconds = (ep.runtimeTicks / 10_000_000L).coerceAtLeast(0L),
                    playbackPositionSeconds = (ep.playbackPositionTicks / 10_000_000L).coerceAtLeast(0L),
                    primaryImageUrl = ep.images.primary?.toString(),
                )
            }

            val state = WearNextUpState(
                items = items,
                updatedAtEpochMs = System.currentTimeMillis(),
            )

            val payload = WearProtocolCodec.encodeNextUp(state)
            val request = PutDataMapRequest.create(WearProtocolPaths.PATH_STATE_NEXT_UP).apply {
                dataMap.putByteArray(WearProtocolPaths.DATA_KEY_PAYLOAD, payload)
                dataMap.putLong(WearProtocolPaths.DATA_KEY_TIMESTAMP, System.currentTimeMillis())
            }.asPutDataRequest()

            Wearable.getDataClient(context).putDataItem(request).await()
            Timber.d("WearNextUpPublisher: published %d Next Up items to DataClient", items.size)
            true
        }.onFailure {
            Timber.w(it, "WearNextUpPublisher: failed to publish Next Up state")
        }.getOrDefault(false)
    }
}
