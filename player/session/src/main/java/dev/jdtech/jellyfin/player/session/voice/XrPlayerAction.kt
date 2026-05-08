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

    data class SelectAudioTrack(
        val language: String? = null,
        val index: Int? = null,
        val secondaryAction: XrPlayerAction? = null,
    ) : XrPlayerAction

    data class SelectSubtitleTrack(
        val language: String? = null,
        val index: Int? = null,
        val secondaryAction: XrPlayerAction? = null,
    ) : XrPlayerAction

    data class ResolveDisambiguation(
        val query: String,
        val originalTranscript: String,
    ) : XrPlayerAction


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

    data class AdjustScale(val delta: Float? = null, val reset: Boolean = false) : XrPlayerAction

    data class AdjustDistance(val delta: Float? = null, val reset: Boolean = false) : XrPlayerAction

    data object ResetScreenPlacement : XrPlayerAction

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

    /**
     * Cast the currently-playing item to an FCast receiver.
     * - [name] is the receiver display name (mDNS instance name or user-configured alias).
     *   When null the resolver should prompt for a picker.
     * - [host] / [port] override discovery when supplied — used by manual-IP entry.
     */
    data class CastToFCastReceiver(
        val name: String? = null,
        val host: String? = null,
        val port: Int? = null,
    ) : XrPlayerAction

    /** Stop casting and clean up the FCast sender connection. No-op if nothing is being cast. */
    data object StopFCastCasting : XrPlayerAction

    data class Unrecognized(val transcript: String) : XrPlayerAction
}
