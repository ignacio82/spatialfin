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

    /**
     * True while the local master is participating in a split-A/V session as the video
     * master. The controller signals end-of-session via [setAudioMuted]`(false)` (the only
     * place that's called from), which flips this back to false.
     *
     * Surfaces that mute the player for non-split-A/V reasons (voice ducking, etc.) read
     * this state to decide whether to *keep* the player muted. Without this signal, voice
     * ducking would re-mute the player every time the voice listener cycles, even after the
     * user ended split-A/V from the receiver side.
     */
    private val _splitAvActive = MutableStateFlow(true)
    val splitAvActive: StateFlow<Boolean> = _splitAvActive.asStateFlow()

    private val listener = object : Player.Listener {
        // We track user intent (playWhenReady) rather than `isPlaying`. `isPlaying` flips
        // false on every transient buffering moment (chunk fetch, seek refill) which would
        // cause the split-A/V pause-mirror to spam pause/resume to the receiver every couple
        // of seconds. `playWhenReady` only changes when the *user* (or a programmatic
        // pause/play) toggles intent, which is what the cascade actually wants.
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            _isPlaying.value = playWhenReady
        }
    }

    init {
        player.addListener(listener)
        _isPlaying.value = player.playWhenReady
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
        timber.log.Timber.tag("SplitAvPauseTrace").w("pauseFromMaster IPC → player.pause()")
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
            // Unmute is only ever called when split-A/V ends — signal it so the voice
            // ducker stops forcing volume back to 0 on every voice state cycle.
            _splitAvActive.value = false
        }
    }
}
