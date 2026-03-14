package dev.jdtech.jellyfin.player.xr.voice

sealed interface XrPlayerAction {
    data object Play : XrPlayerAction

    data object Pause : XrPlayerAction

    data object TogglePlayPause : XrPlayerAction

    data class SeekForward(val seconds: Int = 15) : XrPlayerAction

    data class SeekBackward(val seconds: Int = 10) : XrPlayerAction

    data class SeekTo(val positionSeconds: Long) : XrPlayerAction

    data object SkipIntro : XrPlayerAction

    data object SkipOutro : XrPlayerAction

    data object NextEpisode : XrPlayerAction

    data object PreviousEpisode : XrPlayerAction

    data class SetSpeed(val speed: Float) : XrPlayerAction

    data class SelectAudioTrack(val language: String? = null, val index: Int? = null) :
        XrPlayerAction

    data class SelectSubtitleTrack(val language: String? = null, val index: Int? = null) :
        XrPlayerAction

    data object DisableSubtitles : XrPlayerAction

    data class Search(val query: String) : XrPlayerAction

    data object GoBack : XrPlayerAction

    data object ShowControls : XrPlayerAction

    data object HideControls : XrPlayerAction

    data class Unrecognized(val transcript: String) : XrPlayerAction
}
