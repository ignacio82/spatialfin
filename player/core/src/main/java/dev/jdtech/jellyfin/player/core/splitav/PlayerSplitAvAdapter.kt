package dev.jdtech.jellyfin.player.core.splitav

import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Concrete [SplitAvVideoMaster] backed by a Media3 [Player]. The host Activity creates one of
 * these, registers it with [SplitAvVideoBridge.bind], and unregisters in `onDestroy`. The
 * adapter is responsible for:
 *
 *  - Reflecting the player's playback state into [isPlaying] so the controller can gate drift
 *    correction on the local side.
 *  - Translating master commands (pause/resume/seek/setPlaybackSpeed) into Player calls.
 *  - Saving and restoring the player's volume around split-mode toggles. The user's volume
 *    preference is preserved when split mode ends.
 *  - Forwarding `stopFromMaster()` to a caller-supplied callback so the Activity can finish.
 *
 * Concurrency: Media3's [Player] is single-threaded — it must be touched only from the
 * application's main looper. The adapter dispatches every external call through [postToPlayer]
 * so the controller can call from any coroutine and it lands on the right thread.
 */
class PlayerSplitAvAdapter(
    private val player: Player,
    private val onStopFromMaster: () -> Unit,
    private val postToPlayer: (() -> Unit) -> Unit,
) : SplitAvVideoMaster {

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private var savedVolume: Float = 1f

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            _isPlaying.value = playing
        }
    }

    init {
        player.addListener(listener)
        _isPlaying.value = player.isPlaying
    }

    /** Detach the listener. Call from the Activity's `onDestroy` after `unbind`. */
    fun release() {
        player.removeListener(listener)
    }

    override fun currentPositionMs(): Long = player.currentPosition

    override fun setPlaybackSpeed(factor: Float) = postToPlayer {
        player.setPlaybackSpeed(factor)
    }

    override fun seekTo(positionMs: Long) = postToPlayer {
        player.seekTo(positionMs.coerceAtLeast(0L))
    }

    override fun pauseFromMaster() = postToPlayer {
        player.pause()
    }

    override fun resumeFromMaster() = postToPlayer {
        player.play()
    }

    override fun stopFromMaster() = postToPlayer {
        player.stop()
        onStopFromMaster()
    }

    override fun setAudioMuted(muted: Boolean) = postToPlayer {
        if (muted) {
            if (player.volume > 0f) savedVolume = player.volume
            player.volume = 0f
        } else {
            player.volume = savedVolume
        }
    }
}
