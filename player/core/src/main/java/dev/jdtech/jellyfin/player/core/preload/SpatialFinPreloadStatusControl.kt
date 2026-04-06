package dev.jdtech.jellyfin.player.core.preload

import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager

class SpatialFinPreloadStatusControl : TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {

    // Conservative distance-based preload mapping
    override fun getTargetPreloadStatus(distance: Int): DefaultPreloadManager.PreloadStatus {
        return when (distance) {
            1 -> {
                // Next item: load a specific duration (e.g., 3 seconds)
                DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(3000L)
            }
            2 -> {
                // Distance 2: Just select tracks to be ready
                DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
            }
            3, 4 -> {
                // Distance 3 or 4: Just prepare the source
                DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_SOURCE_PREPARED
            }
            else -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED // NOT_PRELOADED equivalent
        }
    }
}
