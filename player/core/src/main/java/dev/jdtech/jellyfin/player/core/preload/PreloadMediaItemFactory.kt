package dev.jdtech.jellyfin.player.core.preload

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import javax.inject.Inject

class PreloadMediaItemFactory @Inject constructor(
    private val repository: JellyfinRepository
) {
    suspend fun createFrom(item: SpatialFinItem): MediaItem? {
        // Only preload streams if we can construct a stable URL
        // A direct streaming URL bypasses the need for full PlaybackInfo
        // For Jellyfin, a basic stream URL looks like:
        // /Videos/{itemId}/stream?api_key={token}...
        // We will ask the repository for a stream URL.
        val streamUrl = repository.getStreamUrl(item.id, "")
        
        return MediaItem.Builder()
            .setMediaId(item.id.toString())
            .setUri(Uri.parse(streamUrl))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.name).build())
            .build()
    }
}
