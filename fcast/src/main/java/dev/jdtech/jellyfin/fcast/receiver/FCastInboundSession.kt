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

    /** v4 synchronized-start target carried alongside a pending [PendingPlayIntent.Resume].
     *  Non-null ⇒ the queued resume should be a scheduled `resumeAt`, not a resume-now. */
    @Volatile private var pendingResumeAtMs: Long? = null

    private enum class PendingPlayIntent { Resume, Pause }

    fun bindControl(c: ExternalStreamPlayer) {
        control = c
        // Snapshot then clear so a redundant onCreate (rare but possible — singleTask + new
        // intent re-binds) can't double-apply the same pending intent.
        val seek = pendingSeekSeconds
        val intent = pendingPlayIntent
        val resumeAt = pendingResumeAtMs
        pendingSeekSeconds = null
        pendingPlayIntent = null
        pendingResumeAtMs = null
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
        }
    }

    /**
     * A new player Activity of a different kind is being launched (flat to immersive or the
     * reverse). Stop dispatching sender commands to the departing Activity while the new one
     * binds; seek/resume received during that window use the existing pending-command path.
     */
    fun suspendControlForReplacement() {
        control = null
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
    }

    fun unbindBroadcaster() {
        playbackBroadcaster = null
        volumeBroadcaster = null
        tracksBroadcaster = null
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
        // Stop arriving pre-bind means the sender wants the whole session torn down. The
        // Activity is on its way up but will see no further control frames; the simplest
        // correct behavior is to clear any pending intent and let the Activity self-finish
        // when its session-stop watchdog fires. We don't try to dispatch a stop to a not-yet-
        // bound control because it'd race the Activity's own initialization.
        pendingPlayIntent = null
        pendingResumeAtMs = null
        control?.stop()
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
        tracksBroadcaster?.invoke(update)
    }
}
