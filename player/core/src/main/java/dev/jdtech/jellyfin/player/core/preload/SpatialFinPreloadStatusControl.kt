package dev.jdtech.jellyfin.player.core.preload

import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager

class SpatialFinPreloadStatusControl : TargetPreloadStatusControl<Int> {

    // Conservative distance-based preload mapping
    override fun getTargetPreloadStatus(distance: Int): TargetPreloadStatusControl.PreloadStatus? {
        return when (distance) {
            1 -> {
                // Next item: load a specific duration (e.g., 3 seconds)
                DefaultPreloadManager.Status(DefaultPreloadManager.Status.STAGE_LOADED_FOR_DURATION_MS, 3000L)
            }
            2 -> {
                // Distance 2: Just select tracks to be ready
                DefaultPreloadManager.Status(DefaultPreloadManager.Status.STAGE_TRACKS_SELECTED)
            }
            3, 4 -> {
                // Distance 3 or 4: Just prepare the source
                DefaultPreloadManager.Status(DefaultPreloadManager.Status.STAGE_SOURCE_PREPARED)
            }
            else -> null // NOT_PRELOADED equivalent
        }
    }
}
