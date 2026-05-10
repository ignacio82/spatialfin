package dev.jdtech.jellyfin.fcast.receiver

import dev.jdtech.jellyfin.fcast.protocol.PlayMessage
import dev.jdtech.jellyfin.fcast.protocol.SplitAvMetadata
import dev.jdtech.jellyfin.fcast.protocol.splitAv

/**
 * Phase 3 contract: a normalized "play this arbitrary URL" request derived from an inbound
 * FCast [PlayMessage]. The app (specifically `:player:local`) implements [ExternalStreamPlayer]
 * and constructs a Media3 `MediaSource` directly, bypassing the Jellyfin item lookup that the
 * existing PlayerViewModel relies on.
 *
 * Why a separate type vs. just passing [PlayMessage] downstream: keeps the FCast wire format from
 * leaking into the player module, gives the player a stable surface that can also be reused by
 * future ingress sources (Cast, AppFunctions, share-sheet hand-offs).
 */
data class ExternalStreamRequest(
    val url: String,
    val container: String,
    val startPositionSeconds: Double = 0.0,
    val initialVolume: Double? = null,
    val initialSpeed: Double? = null,
    val headers: Map<String, String> = emptyMap(),
    val title: String? = null,
    val thumbnailUrl: String? = null,
    /**
     * Set when the sender wants this peer to participate in a split-A/V session — typically
     * with [SplitAvMetadata.role] = `AUDIO`, meaning this device should play audio and let a
     * paired video master drive sync via PlaybackUpdate beacons. Null for normal full-A/V casts.
     */
    val splitAv: SplitAvMetadata? = null,
) {
    companion object {
        /**
         * Build a request from an FCast [PlayMessage] when the receiver decides this stream
         * is *not* a Jellyfin URL and should be played directly. Returns null when the message
         * lacks a URL — inline DASH manifest content (`PlayMessage.content`) is not supported by
         * this codepath yet.
         */
        fun fromPlayMessage(msg: PlayMessage): ExternalStreamRequest? {
            val url = msg.url ?: return null
            return ExternalStreamRequest(
                url = url,
                container = msg.container,
                startPositionSeconds = msg.time ?: 0.0,
                initialVolume = msg.volume,
                initialSpeed = msg.speed,
                headers = msg.headers.orEmpty(),
                title = msg.metadata?.title,
                thumbnailUrl = msg.metadata?.thumbnailUrl,
                splitAv = msg.splitAv(),
            )
        }
    }
}

/**
 * Implemented by the app to actually play a non-Jellyfin URL. The implementation will live in
 * `:player:local` (likely as `PlayerViewModel.playExternalStream(...)`) once the surgery in
 * Phase 3 lands. Until then, [Rejecting] is the default — sender gets a PlaybackError back.
 */
interface ExternalStreamPlayer {
    fun play(request: ExternalStreamRequest): PlayResult

    fun pause()
    fun resume()
    fun stop()
    fun seek(seconds: Double)
    fun setVolume(volume: Double)
    fun setSpeed(speed: Double)

    sealed interface PlayResult {
        data object Accepted : PlayResult
        data class Rejected(val reason: String) : PlayResult
    }

    /**
     * Default implementation: rejects every play request with an "unsupported" message.
     * The receiver service falls back to this when no real player is registered.
     */
    object Rejecting : ExternalStreamPlayer {
        override fun play(request: ExternalStreamRequest): PlayResult =
            PlayResult.Rejected("External streams are not supported on this device")
        override fun pause() = Unit
        override fun resume() = Unit
        override fun stop() = Unit
        override fun seek(seconds: Double) = Unit
        override fun setVolume(volume: Double) = Unit
        override fun setSpeed(speed: Double) = Unit
    }
}

/**
 * Adapter that turns an [ExternalStreamPlayer] into an [FCastIngressRouter]. Use this when the
 * app supports arbitrary-URL playback (Phase 3); use a Jellyfin-resolving router otherwise.
 */
class ExternalStreamIngressRouter(
    private val player: ExternalStreamPlayer,
) : FCastIngressRouter {

    override fun onPlay(request: PlayMessage): FCastIngressRouter.IngressResult {
        val external = ExternalStreamRequest.fromPlayMessage(request)
            ?: return FCastIngressRouter.IngressResult.Rejected("PlayMessage missing url")
        return when (val r = player.play(external)) {
            is ExternalStreamPlayer.PlayResult.Accepted -> FCastIngressRouter.IngressResult.Accepted
            is ExternalStreamPlayer.PlayResult.Rejected -> FCastIngressRouter.IngressResult.Rejected(r.reason)
        }
    }

    override fun onPause() = player.pause()
    override fun onResume() = player.resume()
    override fun onStop() = player.stop()
    override fun onSeek(seconds: Double) = player.seek(seconds)
    override fun onSetVolume(volume: Double) = player.setVolume(volume)
    override fun onSetSpeed(speed: Double) = player.setSpeed(speed)
}
