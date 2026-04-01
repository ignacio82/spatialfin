package dev.jdtech.jellyfin.player.xr.mcp

import android.content.Context

data class PlaybackState(
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val playbackState: Int,
    val videoWidth: Int,
    val videoHeight: Int,
)

data class SceneState(
    val entities: List<EntityInfo>,
)

data class EntityInfo(
    val name: String,
    val type: String,
    val posX: Float,
    val posY: Float,
    val posZ: Float,
    val scale: Float,
    val isEnabled: Boolean,
)

data class LibassState(
    val renderWidth: Int,
    val renderHeight: Int,
    val hasContent: Boolean,
    val frameVersion: Int,
)

object McpBridge {
    var onActionTriggered: ((String) -> Unit)? = null

    fun register(context: Context) {
        // No-op in the player module. The app-layer bridge lives in the app module.
    }

    fun unregister(context: Context) {
        // No-op in the player module. The app-layer bridge lives in the app module.
    }

    fun updatePlayback(state: PlaybackState) {
        // No-op in the player module.
    }

    fun updateScene(state: SceneState) {
        // No-op in the player module.
    }

    fun updateLibass(state: LibassState) {
        // No-op in the player module.
    }
}
