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

    fun bindControl(c: ExternalStreamPlayer) {
        control = c
    }

    /** Identity-checked unbind so a stale Activity destroy doesn't drop a newer Activity's control. */
    fun unbindControl(c: ExternalStreamPlayer) {
        if (control === c) control = null
    }

    fun bindBroadcaster(
        playback: (PlaybackUpdateMessage) -> Unit,
        volume: (VolumeUpdateMessage) -> Unit,
    ) {
        playbackBroadcaster = playback
        volumeBroadcaster = volume
    }

    fun unbindBroadcaster() {
        playbackBroadcaster = null
        volumeBroadcaster = null
    }

    // Called by IntentBasedExternalStreamPlayer when an FCast frame lands on the router.
    fun pause() {
        control?.pause()
    }

    fun resume() {
        control?.resume()
    }

    fun stop() {
        control?.stop()
    }

    fun seek(seconds: Double) {
        control?.seek(seconds)
    }

    fun setVolume(volume: Double) {
        control?.setVolume(volume)
    }

    fun setSpeed(speed: Double) {
        control?.setSpeed(speed)
    }

    // Called by the Activity each time the ExoPlayer state moves so connected senders see
    // accurate Play/Pause icons and seek positions.
    fun pushPlaybackUpdate(update: PlaybackUpdateMessage) {
        playbackBroadcaster?.invoke(update)
    }

    fun pushVolumeUpdate(update: VolumeUpdateMessage) {
        volumeBroadcaster?.invoke(update)
    }
}
