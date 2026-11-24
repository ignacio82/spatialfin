package dev.jdtech.jellyfin.player.core.downloads

import java.util.UUID

interface EpisodePlaybackStopListener {
    suspend fun onEpisodePlaybackStopped(
        itemId: UUID,
        playedPercentage: Int,
        markedPlayed: Boolean,
        serverId: String? = null,
        userId: UUID? = null,
        sessionEpoch: Long = 0L,
    )

    object NoOp : EpisodePlaybackStopListener {
        override suspend fun onEpisodePlaybackStopped(
            itemId: UUID,
            playedPercentage: Int,
            markedPlayed: Boolean,
            serverId: String?,
            userId: UUID?,
            sessionEpoch: Long,
        ) = Unit
    }
}
