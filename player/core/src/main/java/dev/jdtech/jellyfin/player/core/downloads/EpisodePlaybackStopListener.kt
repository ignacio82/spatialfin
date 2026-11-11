package dev.jdtech.jellyfin.player.core.downloads

import java.util.UUID

interface EpisodePlaybackStopListener {
    suspend fun onEpisodePlaybackStopped(
        itemId: UUID,
        playedPercentage: Int,
        markedPlayed: Boolean,
    )

    object NoOp : EpisodePlaybackStopListener {
        override suspend fun onEpisodePlaybackStopped(
            itemId: UUID,
            playedPercentage: Int,
            markedPlayed: Boolean,
        ) = Unit
    }
}
