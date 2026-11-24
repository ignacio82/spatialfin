package dev.spatialfin.companion.host

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionMode
import dev.spatialfin.companion.protocol.WearNextUpItem
import dev.spatialfin.companion.protocol.WearNextUpState
import dev.spatialfin.companion.protocol.WearProtocolCodec
import dev.spatialfin.companion.protocol.WearProtocolPaths
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await
import timber.log.Timber

@Singleton
class WearNextUpPublisher
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val repository: JellyfinRepository,
    private val appLockObserver: AppLockObserver,
    private val sessionActivationCoordinator: SessionActivationCoordinator,
) {

    private fun isAllowedToPublish(): Boolean =
        appLockObserver.lockState.value != AppLockManager.LockState.LOCKED &&
            sessionActivationCoordinator.sessionState.value.sessionMode == SessionMode.SERVER_AUTHENTICATED

    suspend fun clearNextUp(): Boolean {
        return runCatching {
            val state = WearNextUpState(
                items = emptyList(),
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            val payload = WearProtocolCodec.encodeNextUp(state)
            val request =
                PutDataMapRequest.create(WearProtocolPaths.PATH_STATE_NEXT_UP)
                    .apply {
                        dataMap.putByteArray(WearProtocolPaths.DATA_KEY_PAYLOAD, payload)
                        dataMap.putLong(
                            WearProtocolPaths.DATA_KEY_TIMESTAMP,
                            System.currentTimeMillis(),
                        )
                    }
                    .asPutDataRequest()
                    .setUrgent()
            Wearable.getDataClient(context).putDataItem(request).await()
            Timber.d("WearNextUpPublisher: cleared Next Up items on DataClient")
            true
        }
            .onFailure { Timber.w(it, "WearNextUpPublisher: failed to clear Next Up state") }
            .getOrDefault(false)
    }

    suspend fun publishNextUp(): Boolean {
        if (!isAllowedToPublish()) {
            clearNextUp()
            return false
        }
        return runCatching {
                val episodes = repository.getResumeItems()
                if (!isAllowedToPublish()) {
                    clearNextUp()
                    return false
                }
                val items =
                    episodes.take(10).map { ep ->
                        WearNextUpItem(
                            id = ep.id.toString(),
                            title = ep.name,
                            seriesName =
                                (ep as? dev.jdtech.jellyfin.models.SpatialFinEpisode)?.seriesName,
                            seasonNumber =
                                (ep as? dev.jdtech.jellyfin.models.SpatialFinEpisode)
                                    ?.parentIndexNumber,
                            episodeNumber =
                                (ep as? dev.jdtech.jellyfin.models.SpatialFinEpisode)?.indexNumber,
                            overview = ep.overview,
                            mediaType =
                                if (ep is dev.jdtech.jellyfin.models.SpatialFinEpisode) "Episode"
                                else "Movie",
                            durationSeconds = (ep.runtimeTicks / 10_000_000L).coerceAtLeast(0L),
                            playbackPositionSeconds =
                                (ep.playbackPositionTicks / 10_000_000L).coerceAtLeast(0L),
                            primaryImageUrl = ep.images.primary?.toString(),
                        )
                    }

                val state =
                    WearNextUpState(
                        items = items,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )

                val payload = WearProtocolCodec.encodeNextUp(state)
                val request =
                    PutDataMapRequest.create(WearProtocolPaths.PATH_STATE_NEXT_UP)
                        .apply {
                            dataMap.putByteArray(WearProtocolPaths.DATA_KEY_PAYLOAD, payload)
                            dataMap.putLong(
                                WearProtocolPaths.DATA_KEY_TIMESTAMP,
                                System.currentTimeMillis(),
                            )
                        }
                        .asPutDataRequest()

                Wearable.getDataClient(context).putDataItem(request).await()
                Timber.d(
                    "WearNextUpPublisher: published %d Next Up items to DataClient",
                    items.size,
                )
                true
            }
            .onFailure { Timber.w(it, "WearNextUpPublisher: failed to publish Next Up state") }
            .getOrDefault(false)
    }
}
