package dev.jdtech.jellyfin.player.core.splitav

import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Concrete [SplitAvVideoMaster] backed by a Media3 [Player]. The host Activity creates one of
 * these, registers it with [SplitAvVideoBridge.bind], and unregisters in `onDestroy`.
 *
 * Supports both legacy FCast continuous drift tracking (ms timestamps + speed trim) and Spec 0
 * Theater Audio two-step keyframe-hold `T_play` barriers (microsecond `CLOCK_BOOTTIME` release
 * scheduling, buffering-stall detection, audio track switches, and media item transitions).
 *
 * Concurrency: Media3's [Player] is single-threaded — it must be touched only from the
 * application's main looper. The adapter dispatches every external call through [postToPlayer].
 */
class PlayerSplitAvAdapter(
    private val player: Player,
    private val onStopFromMaster: () -> Unit,
    private val postToPlayer: (() -> Unit) -> Unit,
    /**
     * Fold audio back to this device without stopping playback.
     */
    private val onFoldBackToLocal: () -> Unit = {},
    private val mediaStartOffsetUsProvider: () -> Long = { 0L },
    private val onTPlayLaunchErrorUs: (Long) -> Unit = {},
) : SplitAvVideoMaster {

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _userSeeks = MutableSharedFlow<Long>(replay = 0, extraBufferCapacity = 8)
    override val userSeeks: SharedFlow<Long> = _userSeeks.asSharedFlow()

    private val _firstFrameReady = MutableSharedFlow<SplitAvFirstFrameReady>(
        replay = 0,
        extraBufferCapacity = 8,
    )
    override val firstFrameReady: SharedFlow<SplitAvFirstFrameReady> =
        _firstFrameReady.asSharedFlow()

    private val _isBufferingStalled = MutableStateFlow(false)
    override val isBufferingStalled: StateFlow<Boolean> = _isBufferingStalled.asStateFlow()

    private val _trackSwitches = MutableSharedFlow<SplitAvTrackSwitchRequest>(
        replay = 0,
        extraBufferCapacity = 8,
    )
    override val trackSwitches: SharedFlow<SplitAvTrackSwitchRequest> =
        _trackSwitches.asSharedFlow()

    private val _mediaItemTransitions = MutableSharedFlow<SplitAvMediaItemTransition>(
        replay = 0,
        extraBufferCapacity = 8,
    )
    override val mediaItemTransitions: SharedFlow<SplitAvMediaItemTransition> =
        _mediaItemTransitions.asSharedFlow()

    @Volatile
    private var suppressNextUserSeekEmit: Boolean = false

    private var savedVolume: Float = 1f

    @Volatile
    private var latestVideoTiming: SplitAvVideoTiming? = null

    @Volatile
    private var pendingHoldBarrierSeq: Long? = null

    @Volatile
    private var pendingFirstFrameEmitted: Boolean = false

    @Volatile
    private var pendingHoldStartFramePtsUs: Long? = null

    @Volatile
    private var latestLaunchErrorUs: Long? = null

    private var pendingStartRunnable: Runnable? = null
    private var pendingReadyFallbackRunnable: Runnable? = null
    private var bufferingStallRunnable: Runnable? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val videoFrameMetadataListener = VideoFrameMetadataListener {
            presentationTimeUs: Long,
            releaseTimeNs: Long,
            _: Format,
            _: MediaFormat?,
        ->
        val nowNano = System.nanoTime()
        val nowElapsedNs = SystemClock.elapsedRealtimeNanos()
        val releaseElapsedNs = nowElapsedNs + (releaseTimeNs - nowNano)
        val releaseBoottimeUs = releaseElapsedNs / 1_000L
        val normalizedPtsUs = (presentationTimeUs + mediaStartOffsetUsProvider()).coerceAtLeast(0L)
        latestVideoTiming = SplitAvVideoTiming(
            mediaPositionMs = normalizedPtsUs / 1_000L,
            releaseMonotonicMs = releaseElapsedNs / 1_000_000L,
            sampledMonotonicMs = nowElapsedNs / 1_000_000L,
            mediaPositionUs = normalizedPtsUs,
            releaseBoottimeUs = releaseBoottimeUs,
        )
        val holdSeq = pendingHoldBarrierSeq
        if (holdSeq != null && !pendingFirstFrameEmitted) {
            pendingFirstFrameEmitted = true
            pendingHoldStartFramePtsUs = normalizedPtsUs
            pendingReadyFallbackRunnable?.let(mainHandler::removeCallbacks)
            pendingReadyFallbackRunnable = null
            _firstFrameReady.tryEmit(SplitAvFirstFrameReady(holdSeq, normalizedPtsUs))
        }
    }

    private val _splitAvActive = MutableStateFlow(true)
    val splitAvActive: StateFlow<Boolean> = _splitAvActive.asStateFlow()

    val isHoldActive: Boolean
        get() = pendingHoldBarrierSeq != null

    fun currentLaunchErrorUs(): Long? = latestLaunchErrorUs

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            _isPlaying.value = playWhenReady
            if (!playWhenReady) {
                cancelBufferingStallTimer()
            } else if (player.playbackState == Player.STATE_BUFFERING && pendingHoldBarrierSeq == null) {
                scheduleBufferingStallTimer()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    if (player.playWhenReady && pendingHoldBarrierSeq == null) {
                        scheduleBufferingStallTimer()
                    } else {
                        cancelBufferingStallTimer()
                    }
                }
                Player.STATE_READY -> {
                    cancelBufferingStallTimer()
                    val holdSeq = pendingHoldBarrierSeq
                    if (holdSeq != null && !pendingFirstFrameEmitted) {
                        scheduleReadyFallback(holdSeq)
                    }
                }
                else -> cancelBufferingStallTimer()
            }
        }

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
            cachedPositionMs = player.currentPosition
            _userSeeks.tryEmit(player.currentPosition)
        }
    }

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
        (player as? ExoPlayer)?.setVideoFrameMetadataListener(videoFrameMetadataListener)
        _isPlaying.value = player.playWhenReady
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cachedPositionMs = player.currentPosition
        }
        mainHandler.post(positionPoll)
    }

    /** Detach listeners and cancel scheduled `T_play` or stall timers. Call after `unbind`. */
    fun release() {
        mainHandler.removeCallbacks(positionPoll)
        cancelPendingStart()
        cancelReadyFallback()
        cancelBufferingStallTimer()
        (player as? ExoPlayer)?.clearVideoFrameMetadataListener(videoFrameMetadataListener)
        player.removeListener(listener)
    }

    override fun currentPositionMs(): Long = cachedPositionMs

    override fun renderedVideoTiming(): SplitAvVideoTiming? = latestVideoTiming

    override fun lastTPlayLaunchErrorUs(): Long? = latestLaunchErrorUs

    override fun setPlaybackSpeed(factor: Float) = postToPlayer {
        player.setPlaybackSpeed(factor)
    }

    override fun seekTo(positionMs: Long) = postToPlayer {
        suppressNextUserSeekEmit = true
        player.seekTo(positionMs.coerceAtLeast(0L))
    }

    override fun pauseFromMaster() = postToPlayer {
        timber.log.Timber.tag("SplitAvPauseTrace").w("pauseFromMaster IPC → player.pause()")
        cancelPendingStart()
        player.pause()
    }

    override fun resumeFromMaster() = postToPlayer {
        player.play()
    }

    override fun stopFromMaster() = postToPlayer {
        cancelPendingStart()
        cancelReadyFallback()
        cancelBufferingStallTimer()
        player.stop()
        onStopFromMaster()
    }

    override fun foldBackToLocal() = postToPlayer {
        pendingHoldBarrierSeq = null
        cancelPendingStart()
        cancelReadyFallback()
        cancelBufferingStallTimer()
        onFoldBackToLocal()
    }

    override fun setAudioMuted(muted: Boolean) = postToPlayer {
        if (muted) {
            if (player.volume > 0f) savedVolume = player.volume
            player.volume = 0f
        } else {
            player.volume = savedVolume
            _splitAvActive.value = false
        }
    }

    override fun prepareAndHoldAt(barrierSeq: Long, targetPositionMs: Long) = postToPlayer {
        cancelPendingStart()
        cancelReadyFallback()
        cancelBufferingStallTimer()
        val clampedMs = targetPositionMs.coerceAtLeast(0L)
        pendingHoldBarrierSeq = barrierSeq
        pendingFirstFrameEmitted = false
        pendingHoldStartFramePtsUs = null
        player.pause()
        val currentMs = player.currentPosition.coerceAtLeast(0L)
        if (player.mediaItemCount > 0 && (
                kotlin.math.abs(currentMs - clampedMs) > SAME_POSITION_TOLERANCE_MS ||
                    player.playbackState == Player.STATE_IDLE
                )
        ) {
            suppressNextUserSeekEmit = true
            player.seekTo(clampedMs)
        }
        if (player.playbackState == Player.STATE_READY) {
            scheduleReadyFallback(barrierSeq)
        }
    }

    override fun startAtBoottimeUs(barrierSeq: Long, tPlayClientUs: Long) = postToPlayer {
        if (pendingHoldBarrierSeq != barrierSeq) {
            return@postToPlayer
        }
        cancelPendingStart()
        val nowUs = SystemClock.elapsedRealtimeNanos() / 1_000L
        val delayMs = ((tPlayClientUs - nowUs) / 1_000L).coerceAtLeast(0L)
        val startRunnable = Runnable {
            pendingStartRunnable = null
            if (pendingHoldBarrierSeq != barrierSeq) return@Runnable
            val actualUs = SystemClock.elapsedRealtimeNanos() / 1_000L
            val launchErrorUs = actualUs - tPlayClientUs
            latestLaunchErrorUs = launchErrorUs
            onTPlayLaunchErrorUs(launchErrorUs)
            pendingHoldBarrierSeq = null
            player.play()
        }
        pendingStartRunnable = startRunnable
        if (delayMs == 0L) {
            startRunnable.run()
        } else {
            mainHandler.postDelayed(startRunnable, delayMs)
        }
    }

    fun notifyAudioTrackSwitched(audioStreamIndex: Int, codec: String?) {
        _trackSwitches.tryEmit(SplitAvTrackSwitchRequest(audioStreamIndex, codec))
    }

    fun notifyMediaItemChanged(itemId: String, mediaSourceId: String?, audioStreamIndex: Int) {
        _mediaItemTransitions.tryEmit(
            SplitAvMediaItemTransition(
                itemId = itemId,
                mediaSourceId = mediaSourceId,
                audioStreamIndex = audioStreamIndex,
            ),
        )
    }

    private fun scheduleReadyFallback(barrierSeq: Long) {
        cancelReadyFallback()
        val fallback = Runnable {
            pendingReadyFallbackRunnable = null
            if (pendingHoldBarrierSeq == barrierSeq && !pendingFirstFrameEmitted) {
                pendingFirstFrameEmitted = true
                val ptsUs = (player.currentPosition.coerceAtLeast(0L) * 1_000L +
                    mediaStartOffsetUsProvider()).coerceAtLeast(0L)
                _firstFrameReady.tryEmit(SplitAvFirstFrameReady(barrierSeq, ptsUs))
            }
        }
        pendingReadyFallbackRunnable = fallback
        mainHandler.postDelayed(fallback, READY_FRAME_FALLBACK_MS)
    }

    private fun scheduleBufferingStallTimer() {
        if (bufferingStallRunnable != null || _isBufferingStalled.value) return
        val runnable = Runnable {
            bufferingStallRunnable = null
            if (player.playbackState == Player.STATE_BUFFERING &&
                player.playWhenReady &&
                pendingHoldBarrierSeq == null
            ) {
                _isBufferingStalled.value = true
            }
        }
        bufferingStallRunnable = runnable
        mainHandler.postDelayed(runnable, BUFFERING_STALL_DEBOUNCE_MS)
    }

    private fun cancelBufferingStallTimer() {
        bufferingStallRunnable?.let(mainHandler::removeCallbacks)
        bufferingStallRunnable = null
        if (_isBufferingStalled.value) {
            _isBufferingStalled.value = false
        }
    }

    private fun cancelPendingStart() {
        pendingStartRunnable?.let(mainHandler::removeCallbacks)
        pendingStartRunnable = null
    }

    private fun cancelReadyFallback() {
        pendingReadyFallbackRunnable?.let(mainHandler::removeCallbacks)
        pendingReadyFallbackRunnable = null
    }

    private companion object {
        const val POSITION_CACHE_INTERVAL_MS: Long = 50L
        const val BUFFERING_STALL_DEBOUNCE_MS: Long = 105L
        const val READY_FRAME_FALLBACK_MS: Long = 32L
        const val SAME_POSITION_TOLERANCE_MS: Long = 16L
    }
}
