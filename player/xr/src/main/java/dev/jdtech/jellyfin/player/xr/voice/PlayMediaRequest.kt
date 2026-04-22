package dev.jdtech.jellyfin.player.xr.voice

/**
 * Structured "play this title" signal emitted by the `play_media` action in
 * [ChatToolRegistry]. Lives at the top level so consumers outside the voice
 * package (the player session, Home navigation) can handle it without having
 * to reach through an internal class.
 */
data class PlayMediaRequest(val title: String, val year: Int? = null)
