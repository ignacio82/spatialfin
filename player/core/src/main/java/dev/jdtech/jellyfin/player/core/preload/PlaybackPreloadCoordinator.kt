package dev.jdtech.jellyfin.player.core.preload

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class PlaybackPreloadCoordinator @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val trackSelector = DefaultTrackSelector(context)
    private val preloadStatusControl = SpatialFinPreloadStatusControl()
    
    // We use a single builder for both the PreloadManager and the ExoPlayers it provides
    private val defaultPreloadManagerBuilder = DefaultPreloadManager.Builder(context, preloadStatusControl)

    private val preloadManager: DefaultPreloadManager by lazy {
        defaultPreloadManagerBuilder.build()
    }
    /**
     * Updates the preload manager with a list of currently visible/ranked items.
     * The focused item index dictates the ranking distance.
     */
    fun updateVisibleItems(items: List<MediaItem>, focusedIndex: Int) {
        Timber.d("Preload: updateVisibleItems with ${items.size} items, focused at $focusedIndex")
        // Remove items that are far away or not in list
        // Media3 PreloadManager handles invalidation automatically when add/remove are called
        // We just add all items and let ranking decide what to preload
        items.forEachIndexed { index, mediaItem ->
            val distance = Math.abs(index - focusedIndex)
            preloadManager.add(mediaItem, distance)
        }
        preloadManager.invalidate()
    }

    fun release() {
        preloadManager.release()
    }

    fun getMediaSource(mediaItem: MediaItem): MediaSource? {
        return preloadManager.getMediaSource(mediaItem)
    }

    /**
     * Creates an ExoPlayer instance from the coordinator's builder, which is a hard requirement
     * from Media3 to use the PreloadManager's sources.
     */
    fun buildExoPlayer(exoPlayerBuilder: ExoPlayer.Builder): ExoPlayer {
        return defaultPreloadManagerBuilder.buildExoPlayer(exoPlayerBuilder)
    }
}
