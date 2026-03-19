package dev.jdtech.jellyfin.player.session.voice

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

    data class SetQuality(val maxBitrate: Long) : XrPlayerAction

    data class SelectAudioTrack(val language: String? = null, val index: Int? = null) :
        XrPlayerAction

    data class SelectSubtitleTrack(val language: String? = null, val index: Int? = null) :
        XrPlayerAction

    data object DisableSubtitles : XrPlayerAction

    data class Search(val query: String, val autoPlay: Boolean = false) : XrPlayerAction

    data class SelectOption(val index: Int) : XrPlayerAction

    data object OpenSyncPlay : XrPlayerAction

    data object CreateSyncPlayGroup : XrPlayerAction

    data class JoinSyncPlayGroup(
        val groupName: String? = null,
        val selectionIndex: Int? = null,
    ) : XrPlayerAction

    data object LeaveSyncPlayGroup : XrPlayerAction

    data object RefreshSyncPlay : XrPlayerAction

    data class AdjustVolume(val percentage: Float? = null, val delta: Float? = null) : XrPlayerAction

    data object GoHome : XrPlayerAction

    data object CloseApp : XrPlayerAction

    data object GoBack : XrPlayerAction

    data object ShowControls : XrPlayerAction

    data object HideControls : XrPlayerAction

    data object ReportCurrentTime : XrPlayerAction

    data object ReportRemainingTime : XrPlayerAction

    data object ReportEndTime : XrPlayerAction

    data object ReportCurrentMedia : XrPlayerAction

    data object ReportPassthroughStatus : XrPlayerAction

    data class SetPassthrough(val enabled: Boolean) : XrPlayerAction

    data object TogglePassthrough : XrPlayerAction

    data class ChatQuery(val query: String) : XrPlayerAction

    data class Unrecognized(val transcript: String) : XrPlayerAction
}
