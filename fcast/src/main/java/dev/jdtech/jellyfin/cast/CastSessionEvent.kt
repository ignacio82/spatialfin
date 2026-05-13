package dev.jdtech.jellyfin.cast

/**
 * Unified event stream emitted by every [ProtocolAdapter]. The session manager observes a single
 * `SharedFlow<CastSessionEvent>` per active adapter and translates these into UI state.
 *
 * Designed to be a superset across protocols — adapters that don't support a particular signal
 * simply never emit it (AirPlay never emits [SpeedChanged], FCast never emits a Cast-specific
 * `transportIdAssigned`, etc.). Avoid adding protocol-specific subtypes here; if a protocol
 * needs a side-channel, expose it on its adapter directly.
 */
sealed interface CastSessionEvent {

    data class ConnectionStateChanged(val state: CastConnectionState) : CastSessionEvent

    data class MediaStateChanged(val state: CastMediaState) : CastSessionEvent

    /** Position in ms from the start of the media timeline. */
    data class PositionChanged(val positionMs: Long) : CastSessionEvent

    /** Duration in ms; absent until the receiver has decoded enough to report one. */
    data class DurationChanged(val durationMs: Long) : CastSessionEvent

    /** Receiver-side volume in the range 0..1 (linear). */
    data class VolumeChanged(val volume: Float) : CastSessionEvent

    /** Playback rate multiplier (1.0 = normal speed). Never emitted by AirPlay v1. */
    data class SpeedChanged(val speed: Float) : CastSessionEvent

    /** Media played to completion. */
    data object Ended : CastSessionEvent

    /** Recoverable or terminal failure; pair with the connection state to disambiguate. */
    data class Error(val reason: String) : CastSessionEvent
}
