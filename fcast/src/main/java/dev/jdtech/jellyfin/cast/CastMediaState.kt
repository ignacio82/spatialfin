package dev.jdtech.jellyfin.cast

/**
 * Playback state on the remote receiver. Each adapter maps its protocol-specific status into
 * this set — FCast `PlaybackState`, Cast `MEDIA_STATUS.playerState`, AirPlay `playback-info.rate`.
 */
enum class CastMediaState { Idle, Buffering, Playing, Paused, Ended }
