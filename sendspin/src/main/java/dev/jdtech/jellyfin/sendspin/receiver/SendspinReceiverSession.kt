package dev.jdtech.jellyfin.sendspin.receiver

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class SendspinReceiverPlaybackState {
    PLAYING,
    PAUSED,
    STOPPED,
}

enum class SendspinMusicAssistantAuthState {
    UNKNOWN,
    MISSING,
    AUTHENTICATING,
    AUTHENTICATED,
    INVALID,
    ERROR,
}

data class SendspinMusicAssistantPlayer(
    val id: String,
    val name: String,
    val type: String,
    val available: Boolean,
    val enabled: Boolean,
    val powered: Boolean?,
    val playbackState: String?,
    val syncedTo: String?,
    val activeGroup: String?,
    val groupMembers: Set<String>,
    val canGroupWith: Set<String>,
    val supportedFeatures: Set<String>,
    val isCurrent: Boolean = false,
    val inActiveGroup: Boolean = false,
    val canToggleGroup: Boolean = false,
)

data class SendspinReceiverUiState(
    val serviceRunning: Boolean = false,
    val connected: Boolean = false,
    val serverName: String? = null,
    val serverId: String? = null,
    val playbackState: SendspinReceiverPlaybackState = SendspinReceiverPlaybackState.STOPPED,
    val controlsDismissed: Boolean = false,
    /**
     * True once genuine playback has started in THIS session (a real
     * PLAYING transition, not the server's restore sync after a reconnect).
     * Lets the receiver UI stay up through a pause/stop — where MA reports the
     * SendSpin endpoint as STOPPED — while still NOT hijacking the screen on a
     * cold launch that only replayed last session's stale metadata.
     */
    val playbackStarted: Boolean = false,
    val supportedCommands: Set<String> = emptySet(),
    val volume: Int = 100,
    val muted: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtwork: ByteArray? = null,
    val artworkUrl: String? = null,
    val visualizerLevels: List<Float> = emptyList(),
    val repeat: String? = null,
    val shuffle: Boolean? = null,
    val musicAssistantServerUrl: String? = null,
    val musicAssistantAuthState: SendspinMusicAssistantAuthState =
        SendspinMusicAssistantAuthState.UNKNOWN,
    val musicAssistantLoading: Boolean = false,
    val musicAssistantError: String? = null,
    val musicAssistantPlayers: List<SendspinMusicAssistantPlayer> = emptyList(),
    val musicAssistantCurrentPlayerId: String? = null,
    val musicAssistantGroupTargetPlayerId: String? = null,
    val musicAssistantActiveMemberIds: Set<String> = emptySet(),
    val musicAssistantBusyPlayerIds: Set<String> = emptySet(),
) {
    val isPlaying: Boolean
        get() = playbackState == SendspinReceiverPlaybackState.PLAYING

    val hasMedia: Boolean
        get() = !title.isNullOrBlank() || !artist.isNullOrBlank() || !album.isNullOrBlank()

    val hasArtwork: Boolean
        get() = albumArtwork?.isNotEmpty() == true || !artworkUrl.isNullOrBlank()

    val showControls: Boolean
        // Surface the receiver UI while playback is live (PLAYING / PAUSED), and
        // also while STOPPED *if* we've genuinely played this session — so a
        // pause/stop (which MA reports as STOPPED for a SendSpin endpoint)
        // doesn't make the controls vanish. A cold launch that only replayed
        // last session's stale metadata has playbackStarted == false, so it
        // still won't hijack Home.
        get() =
            connected &&
                !controlsDismissed &&
                (playbackState != SendspinReceiverPlaybackState.STOPPED || (playbackStarted && hasMedia))

    fun supports(command: String): Boolean = command in supportedCommands
}

object SendspinReceiverSession {
    private val _state = MutableStateFlow(SendspinReceiverUiState())
    val state: StateFlow<SendspinReceiverUiState> = _state.asStateFlow()

    internal fun update(transform: (SendspinReceiverUiState) -> SendspinReceiverUiState) {
        _state.update(transform)
    }

    fun dismissControls() {
        _state.update { it.copy(controlsDismissed = true) }
    }

    /**
     * Re-show the SendSpin fullscreen. The fullscreen and the mini-player are
     * gated on `controlsDismissed`: tapping the mini-player calls this so the
     * full UI returns. Without this, "X" was a one-way trip — closing the
     * fullscreen left the user with no playback controls.
     */
    fun restoreControls() {
        _state.update { it.copy(controlsDismissed = false) }
    }

    internal fun reset() {
        _state.value = SendspinReceiverUiState()
    }
}

object SendspinControllerCommands {
    const val PLAY = "play"
    const val PAUSE = "pause"
    const val STOP = "stop"
    const val NEXT = "next"
    const val PREVIOUS = "previous"
    const val VOLUME = "volume"
    const val MUTE = "mute"
    const val REPEAT_OFF = "repeat_off"
    const val REPEAT_ONE = "repeat_one"
    const val REPEAT_ALL = "repeat_all"
    const val SHUFFLE = "shuffle"
    const val UNSHUFFLE = "unshuffle"
    const val SWITCH = "switch"
}
