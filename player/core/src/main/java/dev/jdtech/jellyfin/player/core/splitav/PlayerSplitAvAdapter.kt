package dev.jdtech.jellyfin.player.core.splitav

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    /**
     * Fold audio back to this device without stopping playback. The host Activity supplies
     * this: it must re-enable the audio track and, when the source codec isn't decodable
     * here, reload the media via the normal capability-aware (transcoding) path at the
     * current position. Default no-op for hosts that don't support fold-back.
     */
    private val onFoldBackToLocal: () -> Unit = {},
) : SplitAvVideoMaster {

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /**
     * Emits the post-seek position whenever the **user** scrubs / fast-forwards / rewinds.
     * `replay = 0` because there's no useful "current seek state" — only the events matter.
     * `extraBufferCapacity = 8` so a burst of seeks (user holding the FF button) doesn't drop.
     * Controller-driven [seekTo] calls flip [suppressNextUserSeekEmit] so the resulting
     * discontinuity isn't echoed back as a new user seek — that'd cause a feedback loop
     * between drift correction and the cascade.
     */
    private val _userSeeks = MutableSharedFlow<Long>(replay = 0, extraBufferCapacity = 8)
    override val userSeeks: SharedFlow<Long> = _userSeeks.asSharedFlow()

    @Volatile
    private var suppressNextUserSeekEmit: Boolean = false

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

        /**
         * Fires on every position jump that isn't naturally continuous — user seek bars,
         * skip ±10 s, fast-forward release, programmatic [seekTo] from the controller, etc.
         * We only forward genuine user-driven seeks (DISCONTINUITY_REASON_SEEK) and gate them
         * against [suppressNextUserSeekEmit] so the controller's own drift-correction seeks
         * don't echo back through the cascade.
         */
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK) return
            if (suppressNextUserSeekEmit) {
                suppressNextUserSeekEmit = false
                return
            }
            // Use the player's freshly-set position (not the listener arg) so the receiver
            // lands exactly where the user landed — the listener's newPosition.positionMs is
            // post-clamp but pre-buffer-flush in some Media3 paths.
            cachedPositionMs = player.currentPosition
            _userSeeks.tryEmit(player.currentPosition)
        }
    }

    /**
     * 20 Hz position cache refreshed on the main thread. The split-A/V controller's drift
     * policy runs on `Dispatchers.Default` and asks for the master's current position via
     * [currentPositionMs] — Media3's [Player] enforces that `getCurrentPosition()` is only
     * called from its application looper, so an off-thread read raises
     * `IllegalStateException: Player is accessed on the wrong thread`.
     *
     * 50 ms granularity is plenty for drift correction: the policy's smallest tolerance band
     * is 20 ms, but a beacon-driven decision happens every 100 ms anyway. The handler is
     * cheap and avoids the deadlock risk of synchronously dispatching to main from a worker.
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var cachedPositionMs: Long = 0L

    private val positionPoll = object : Runnable {
        override fun run() {
            cachedPositionMs = player.currentPosition
            mainHandler.postDelayed(this, POSITION_CACHE_INTERVAL_MS)
        }
    }

    init {
        player.addListener(listener)
        _isPlaying.value = player.playWhenReady
        // Bootstrap the cache from whichever thread constructed us (the Activity onCreate runs
        // on main, so this is safe). The poll then keeps it fresh.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cachedPositionMs = player.currentPosition
        }
        mainHandler.post(positionPoll)
    }

    /** Detach the listener. Call from the Activity's `onDestroy` after `unbind`. */
    fun release() {
        mainHandler.removeCallbacks(positionPoll)
        player.removeListener(listener)
    }

    override fun currentPositionMs(): Long = cachedPositionMs

    override fun setPlaybackSpeed(factor: Float) = postToPlayer {
        player.setPlaybackSpeed(factor)
    }

    override fun seekTo(positionMs: Long) = postToPlayer {
        // Suppress the user-seek echo: this method is invoked by the controller for drift-
        // correction hard seeks (and possibly other programmatic seeks). The discontinuity
        // listener would otherwise emit on `_userSeeks` and the controller would cascade the
        // seek back to the receiver, which is where the drift came from in the first place →
        // feedback loop.
        suppressNextUserSeekEmit = true
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

    override fun foldBackToLocal() = postToPlayer {
        onFoldBackToLocal()
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

    private companion object {
        const val POSITION_CACHE_INTERVAL_MS: Long = 50L
    }
}
