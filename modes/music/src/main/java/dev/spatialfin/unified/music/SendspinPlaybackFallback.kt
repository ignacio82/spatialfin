package dev.spatialfin.unified.music

import dev.jdtech.jellyfin.data.musicassistant.data.model.server.RepeatMode
import dev.jdtech.jellyfin.data.musicassistant.repository.MaPlayerSummary
import dev.jdtech.jellyfin.data.musicassistant.repository.MaSession
import dev.jdtech.jellyfin.data.musicassistant.repository.NowPlayingTrack
import dev.jdtech.jellyfin.data.musicassistant.repository.PlaybackPhase
import dev.jdtech.jellyfin.sendspin.receiver.SendspinControllerCommands
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverPlaybackState
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverUiState

internal fun MaSession.shouldUseSendspinFallback(
    receiverState: SendspinReceiverUiState,
): Boolean =
    nowPlaying == null &&
        receiverState.showControls &&
        receiverState.playbackStarted &&
        receiverState.hasMedia

internal fun MaSession.withSendspinFallback(
    receiverState: SendspinReceiverUiState,
): MaSession {
    if (!shouldUseSendspinFallback(receiverState)) return this
    val playerId =
        receiverState.musicAssistantQueuePlayerId
            ?: receiverState.musicAssistantCurrentPlayerId
            ?: "sendspin-local"
    return copy(
        selectedPlayer = MaPlayerSummary(
            id = playerId,
            name = receiverState.serverName ?: "This device",
            provider = "SendSpin",
            isPlaying = receiverState.isPlaying,
            volumeLevel = receiverState.volume,
            volumeMuted = receiverState.muted,
            supportsVolume = receiverState.supports(SendspinControllerCommands.VOLUME),
        ),
        nowPlaying = NowPlayingTrack(
            uri = playerId,
            title = receiverState.title ?: receiverState.album ?: "Playing audio",
            artist = receiverState.artist ?: receiverState.album,
            artworkUrl = receiverState.artworkUrl,
            durationMs = null,
        ),
        elapsedMs = null,
        elapsedAsOfEpochMs = null,
        playbackPhase = when (receiverState.playbackState) {
            SendspinReceiverPlaybackState.PLAYING -> PlaybackPhase.Playing
            SendspinReceiverPlaybackState.PAUSED -> PlaybackPhase.Paused
            SendspinReceiverPlaybackState.STOPPED -> PlaybackPhase.Idle
        },
        pendingPlayUri = null,
        activeQueueId = null,
        repeatMode = when (receiverState.repeat) {
            "one" -> RepeatMode.ONE
            "all" -> RepeatMode.ALL
            else -> RepeatMode.OFF
        },
        shuffleEnabled = receiverState.shuffle == true,
        dontStopTheMusic = false,
        currentQueueItemId = null,
        partySource = null,
    )
}
