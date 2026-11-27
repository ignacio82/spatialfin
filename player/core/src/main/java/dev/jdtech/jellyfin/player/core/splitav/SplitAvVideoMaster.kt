package dev.jdtech.jellyfin.player.core.splitav

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Surface a local video player exposes when it is participating in a split-A/V session as the
 * **video master**. The controller (lives in `:app:unified`) drives this interface to keep the
 * XR-side video aligned with the TV-side audio's reported playback position.
 *
 * The implementation is whichever player Activity is currently playing video locally:
 * `XrPlayerActivity` (via its `PlayerViewModel`), `BeamPlayerActivity`, etc. The Activity
 * registers itself with [SplitAvVideoBridge] in `onResume` (or whenever it is ready to be
 * driven) and unregisters in `onDestroy` — mirrors the `FCastInboundSession.bindControl`
 * pattern used by inbound FCast playback.
 *
 * All methods MUST be cheap and non-blocking. Long work belongs in the player's own coroutine
 * scope — the controller calls these from a serial dispatcher and assumes they return promptly.
 */
interface SplitAvVideoMaster {

    /** Current playback position in milliseconds. The controller polls this to compute drift. */
    fun currentPositionMs(): Long

    /**
     * Latest rendered-frame timing, when the host player exposes it.
     */
    fun renderedVideoTiming(): SplitAvVideoTiming? = null

    /** Emits once when [prepareAndHoldAt] finishes decoding/holding at the target keyframe. */
    val firstFrameReady: SharedFlow<SplitAvFirstFrameReady>
        get() = EmptySplitAvMasterFlows.firstFrameReady

    /** True while local video playback is stalled in `STATE_BUFFERING` (> 100 ms). */
    val isBufferingStalled: StateFlow<Boolean>
        get() = EmptySplitAvMasterFlows.isBufferingStalled

    /** Emits when the user switches audio track during active Split A/V playback. */
    val trackSwitches: SharedFlow<SplitAvTrackSwitchRequest>
        get() = EmptySplitAvMasterFlows.trackSwitches

    /** Emits when playback advances to a new media item (e.g. next episode). */
    val mediaItemTransitions: SharedFlow<SplitAvMediaItemTransition>
        get() = EmptySplitAvMasterFlows.mediaItemTransitions

    /** True while the player is actively rendering frames at >0× speed. */
    val isPlaying: StateFlow<Boolean>

    /**
     * Emits the new master position (ms) whenever the **user** seeks — fast-forward, rewind,
     * scrub-bar drag, "skip 10 s" buttons. The controller listens to this and cascades the seek
     * to the receiver so the audio track follows the video.
     *
     * Implementations MUST NOT emit on programmatic seeks issued *by* the controller via
     * [seekTo] (those are drift-correction hard seeks; cascading them back to the receiver
     * would cause a feedback loop). The discontinuity-reason filter on
     * `Player.Listener.onPositionDiscontinuity` is the right place to gate.
     */
    val userSeeks: SharedFlow<Long>

    /**
     * Apply a temporary playback-speed multiplier to nudge alignment. The controller schedules
     * the revert to 1.0× — implementations should NOT auto-revert.
     */
    fun setPlaybackSpeed(factor: Float)

    /** Jump to [positionMs]. Used for hard-seek drift correction. */
    fun seekTo(positionMs: Long)

    /** Pause / resume cascading from the audio master. */
    fun pauseFromMaster()
    fun resumeFromMaster()

    /**
     * Stop video playback entirely (cascading from the audio master sending Stop, or the user
     * choosing to leave split mode). The implementation should finish its Activity if appropriate.
     */
    fun stopFromMaster()

    /**
     * Mute or unmute the local video master's audio output. In split mode this should be true
     * the whole time the session is active — TV is rendering audio, not the headset.
     */
    fun setAudioMuted(muted: Boolean)

    /**
     * The user (or a receiver-initiated end) asked to fold audio back to the headset without
     * stopping playback. The split-A/V master entered with its audio track *disabled* (the
     * raw stream may carry a codec the headset can't decode), so simply unmuting is silent.
     * The implementation must restore local audio the way non-split playback already works:
     * re-enable the audio track and, if the source codec isn't headset-decodable, reload the
     * media via the normal capability-aware (transcoding) path at the current position. Must
     * NOT stop playback — the user wants to keep watching, now with sound on the headset.
     */
    fun foldBackToLocal()

    /** Hold paused on the target keyframe for [barrierSeq] and emit on [firstFrameReady]. */
    fun prepareAndHoldAt(barrierSeq: Long, targetPositionMs: Long) {}

    /** Release a previously prepared hold at [tPlayClientUs] (`CLOCK_BOOTTIME` microseconds). */
    fun startAtBoottimeUs(barrierSeq: Long, tPlayClientUs: Long) {}

    /** Signed difference (`actual_release_us - t_play_client_us`) from the last scheduled launch. */
    fun lastTPlayLaunchErrorUs(): Long? = null
}

data class SplitAvFirstFrameReady(
    val barrierSeq: Long,
    val actualPtsUs: Long,
)

data class SplitAvTrackSwitchRequest(
    val audioStreamIndex: Int,
    val codec: String?,
)

data class SplitAvMediaItemTransition(
    val itemId: String,
    val mediaSourceId: String?,
    val audioStreamIndex: Int,
)

private object EmptySplitAvMasterFlows {
    val firstFrameReady: SharedFlow<SplitAvFirstFrameReady> = MutableSharedFlow()
    val isBufferingStalled: StateFlow<Boolean> = MutableStateFlow(false)
    val trackSwitches: SharedFlow<SplitAvTrackSwitchRequest> = MutableSharedFlow()
    val mediaItemTransitions: SharedFlow<SplitAvMediaItemTransition> = MutableSharedFlow()
}

data class SplitAvVideoTiming(
    /** Media position of the rendered video frame, in milliseconds. */
    val mediaPositionMs: Long,
    /** Sender/device monotonic clock at which the frame is/was scheduled for release. */
    val releaseMonotonicMs: Long,
    /** Sender/device monotonic clock when this timing snapshot was captured. */
    val sampledMonotonicMs: Long,
    /** Microsecond-precision normalized media position for Spec 0 beacons. */
    val mediaPositionUs: Long = mediaPositionMs * 1_000L,
    /** Microsecond-precision `CLOCK_BOOTTIME` scheduled release timestamp. */
    val releaseBoottimeUs: Long = releaseMonotonicMs * 1_000L,
)

/**
 * Process-wide bridge so the controller in `:app:unified` can find whichever player Activity
 * is currently bound. Mirrors [dev.jdtech.jellyfin.fcast.receiver.FCastInboundSession]'s static
 * bind-by-singleton pattern. Idempotent — last bind wins.
 *
 * Concurrency: writes are guarded by [bindLock]; reads observe the [activeMaster] state flow so
 * the controller can react to a master appearing or disappearing without polling.
 */
object SplitAvVideoBridge {

    private val bindLock = Any()
    private val _activeMaster = MutableStateFlow<SplitAvVideoMaster?>(null)
    val activeMaster: StateFlow<SplitAvVideoMaster?> = _activeMaster.asStateFlow()

    fun bind(master: SplitAvVideoMaster) = synchronized(bindLock) {
        _activeMaster.value = master
    }

    fun unbind(master: SplitAvVideoMaster) = synchronized(bindLock) {
        if (_activeMaster.value === master) {
            _activeMaster.value = null
        }
    }
}
