package dev.jdtech.jellyfin.fcast.receiver

import dev.jdtech.jellyfin.fcast.protocol.PlayMessage

/**
 * Routes inbound FCast control messages to the player layer. The library cannot directly call
 * `PlayerViewModel` (that lives in `:player:local` and depends on Jellyfin / Media3 / Hilt), so the
 * app implements this interface and registers it with [FCastReceiverServer].
 *
 * Each method should return promptly — long work belongs in the player coroutine scope. The
 * server holds no locks while invoking the router.
 */
interface FCastIngressRouter {

    /**
     * A sender wants us to start playing [request]. Implementations decide whether to:
     *  - resolve the URL against a paired Jellyfin server (Phase 2 path), or
     *  - hand it to an external-stream player as an arbitrary URL (Phase 3 path).
     *
     * Return [IngressResult.Accepted] to acknowledge; the server will not send a PlaybackError to
     * the sender. Return [IngressResult.Rejected] to push a PlaybackError back with [Rejected.reason].
     */
    fun onPlay(request: PlayMessage): IngressResult

    fun onPause()
    fun onResume()

    /**
     * v4 synchronized-start: begin playback when the receiver's monotonic clock
     * (`SystemClock.elapsedRealtime`) reaches [atReceiverMonotonicMs]. Implementations must
     * clamp — if the instant is already past or unreasonably far out, resume immediately.
     * Defaults to a plain [onResume] so pre-v4 routers need no change.
     */
    fun onResumeAt(atReceiverMonotonicMs: Long) = onResume()

    fun onStop()
    fun onSeek(seconds: Double)
    fun onSetVolume(volume: Double)
    fun onSetSpeed(speed: Double)

    sealed interface IngressResult {
        data object Accepted : IngressResult
        data class Rejected(val reason: String) : IngressResult
    }

    /** No-op implementation for when the receiver is enabled but no player is wired up yet. */
    object NoOp : FCastIngressRouter {
        override fun onPlay(request: PlayMessage): IngressResult =
            IngressResult.Rejected("SpatialFin is not configured to accept media")
        override fun onPause() = Unit
        override fun onResume() = Unit
        override fun onStop() = Unit
        override fun onSeek(seconds: Double) = Unit
        override fun onSetVolume(volume: Double) = Unit
        override fun onSetSpeed(speed: Double) = Unit
    }
}
