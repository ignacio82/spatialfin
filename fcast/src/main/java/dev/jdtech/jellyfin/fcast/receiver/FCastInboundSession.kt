package dev.jdtech.jellyfin.fcast.receiver

import dev.jdtech.jellyfin.fcast.protocol.PlaybackUpdateMessage
import dev.jdtech.jellyfin.fcast.protocol.VolumeUpdateMessage

/**
 * Process-wide bridge between the receiver Activity that owns the active ExoPlayer and the
 * receiver Service that holds the open sender sockets. They live in different lifecycles
 * (Activity ↔ Service) and neither has a direct handle on the other, so they meet here.
 *
 * Single in-process consumer of each side: the Service binds [bindBroadcaster] once after the
 * server is up, and the Activity binds [bindControl] once on `onCreate`.
 *
 * Without this, `IntentBasedExternalStreamPlayer.pause/resume/seek/setVolume/setSpeed` are
 * silent no-ops — the sender's controls do nothing because the FCast frames arrive at the
 * router but there's no path from there back to the running ExoPlayer.
 */
object FCastInboundSession {

    @Volatile private var control: ExternalStreamPlayer? = null
    @Volatile private var playbackBroadcaster: ((PlaybackUpdateMessage) -> Unit)? = null
    @Volatile private var volumeBroadcaster: ((VolumeUpdateMessage) -> Unit)? = null
    @Volatile private var tracksBroadcaster: ((dev.jdtech.jellyfin.fcast.protocol.SpatialFinTracksUpdateMessage) -> Unit)? = null
    // Cached so the service can replay it to senders that connect after onTracksChanged fires.
    @Volatile private var lastTracksUpdate: dev.jdtech.jellyfin.fcast.protocol.SpatialFinTracksUpdateMessage? = null

    /**
     * Latest control messages that arrived **before** an [ExternalStreamPlayer] was bound.
     * Senders can pipeline `Play` → `Seek` → `Resume` immediately after picking a receiver
     * (the split-A/V controller's first-play alignment, and the calibration orchestrator's
     * immediate Resume both do this), but the Play frame arrives at the Service, fires an
     * Intent to launch the receiver Activity, and any follow-up Seek/Resume can land **before**
     * that Activity finishes `onCreate` and calls [bindControl]. Without queueing, those
     * frames were silent no-ops and the receiver stayed at position 0 / playWhenReady=false
     * forever.
     *
     * Latest-wins semantics:
     *  - The most recent play-intent (Resume/Pause) replaces any previous one.
     *  - Only the most recent Seek target is kept — a sender that scrubs to 5:00 then 10:00
     *    expects to land at 10:00, not pass through 5:00.
     *
     * Cleared on [unbindControl] so a stale intent doesn't leak into a future Activity.
     */
    @Volatile private var pendingPlayIntent: PendingPlayIntent? = null
    @Volatile private var pendingSeekSeconds: Double? = null
    @Volatile private var pendingStop: Boolean = false

    /** v4 synchronized-start target carried alongside a pending [PendingPlayIntent.Resume].
     *  Non-null ⇒ the queued resume should be a scheduled `resumeAt`, not a resume-now. */
    @Volatile private var pendingResumeAtMs: Long? = null

    private enum class PendingPlayIntent { Resume, Pause }

    fun bindControl(c: ExternalStreamPlayer) {
        control = c
        // Snapshot then clear so a redundant onCreate (rare but possible — singleTask + new
        // intent re-binds) can't double-apply the same pending intent.
        val stop = pendingStop
        val seek = pendingSeekSeconds
        val intent = pendingPlayIntent
        val resumeAt = pendingResumeAtMs
        pendingStop = false
        pendingSeekSeconds = null
        pendingPlayIntent = null
        pendingResumeAtMs = null
        if (stop) {
            c.stop()
            return
        }
        // Seek before play-intent: senders send them in that order, and applying a Seek
        // *after* a Resume forces an extra buffer-flush mid-playback.
        if (seek != null) c.seek(seek)
        when (intent) {
            PendingPlayIntent.Resume ->
                if (resumeAt != null) c.resumeAt(resumeAt) else c.resume()
            PendingPlayIntent.Pause -> c.pause()
            null -> Unit
        }
    }

    /** Identity-checked unbind so a stale Activity destroy doesn't drop a newer Activity's control. */
    fun unbindControl(c: ExternalStreamPlayer) {
        if (control === c) {
            control = null
            // Clear any stale pending intents — they belonged to the now-departed Activity.
            pendingPlayIntent = null
            pendingSeekSeconds = null
            pendingResumeAtMs = null
            pendingStop = false
        }
    }

    /**
     * A new player Activity of a different kind is being launched (flat to immersive or the
     * reverse). Stop dispatching sender commands to the departing Activity while the new one
     * binds; seek/resume received during that window use the existing pending-command path.
     */
    fun suspendControlForReplacement() {
        val departing = control
        control = null
        pendingPlayIntent = null
        pendingSeekSeconds = null
        pendingResumeAtMs = null
        pendingStop = false
        departing?.stop()
    }

    /**
     * A new FCast Play frame is about to launch or retarget the inbound Activity. Drop any
     * stale pre-bind Stop/Pause/Seek left by a previous session so it cannot immediately stop
     * or seek the newly-started media.
     */
    fun prepareForNewPlayback() {
        pendingStop = false
        pendingPlayIntent = null
        pendingSeekSeconds = null
        pendingResumeAtMs = null
    }

    fun bindBroadcaster(
        playback: (PlaybackUpdateMessage) -> Unit,
        volume: (VolumeUpdateMessage) -> Unit,
        tracks: (dev.jdtech.jellyfin.fcast.protocol.SpatialFinTracksUpdateMessage) -> Unit = {},
    ) {
        playbackBroadcaster = playback
        volumeBroadcaster = volume
        tracksBroadcaster = tracks
        // Replay the cached tracks update so senders that connect after onTracksChanged
        // already fired still receive the available track list immediately.
        lastTracksUpdate?.let { tracks(it) }
    }

    fun unbindBroadcaster() {
        playbackBroadcaster = null
        volumeBroadcaster = null
        tracksBroadcaster = null
        lastTracksUpdate = null
    }

    // Called by IntentBasedExternalStreamPlayer when an FCast frame lands on the router.
    fun pause() {
        val c = control
        if (c != null) {
            c.pause()
        } else {
            pendingPlayIntent = PendingPlayIntent.Pause
            pendingResumeAtMs = null
        }
    }

    fun resume() {
        val c = control
        if (c != null) {
            c.resume()
        } else {
            pendingPlayIntent = PendingPlayIntent.Resume
            pendingResumeAtMs = null // a plain resume supersedes any queued scheduled start
        }
    }

    fun resumeAt(atReceiverMonotonicMs: Long) {
        val c = control
        if (c != null) {
            c.resumeAt(atReceiverMonotonicMs)
        } else {
            pendingPlayIntent = PendingPlayIntent.Resume
            pendingResumeAtMs = atReceiverMonotonicMs
        }
    }

    fun stop() {
        pendingPlayIntent = null
        pendingResumeAtMs = null
        pendingSeekSeconds = null
        val c = control
        if (c != null) {
            c.stop()
        } else {
            pendingStop = true
        }
    }

    fun seek(seconds: Double) {
        val c = control
        if (c != null) c.seek(seconds) else pendingSeekSeconds = seconds
    }

    fun setVolume(volume: Double) {
        control?.setVolume(volume)
    }

    fun setSpeed(speed: Double) {
        control?.setSpeed(speed)
    }

    fun setTrack(type: Int, trackId: String) {
        control?.setTrack(type, trackId)
    }

    // Called by the Activity each time the ExoPlayer state moves so connected senders see
    // accurate Play/Pause icons and seek positions.
    fun pushPlaybackUpdate(update: PlaybackUpdateMessage) {
        playbackBroadcaster?.invoke(update)
    }

    fun pushVolumeUpdate(update: VolumeUpdateMessage) {
        volumeBroadcaster?.invoke(update)
    }

    fun pushTracksUpdate(update: dev.jdtech.jellyfin.fcast.protocol.SpatialFinTracksUpdateMessage) {
        lastTracksUpdate = update
        tracksBroadcaster?.invoke(update)
    }
}
