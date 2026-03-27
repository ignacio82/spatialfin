package dev.spatialfin.beam

import dev.jdtech.jellyfin.models.NetworkShareDto
import dev.jdtech.jellyfin.models.NetworkVideoItem
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.SpatialFinSourceType
import org.junit.Assert.assertEquals
import org.junit.Test

class BeamNetworkScreensTest {

    @Test
    fun `dedupeNetworkVideos keeps first entry per network video id`() {
        val first = video("video-1", "First")
        val second = video("video-1", "Second")

        val deduped = dedupeNetworkVideos(listOf(first, second))

        assertEquals(listOf(first), deduped)
    }

    @Test
    fun `dedupeNetworkShares keeps first entry per share id`() {
        val first = share("share-1", "First")
        val second = share("share-1", "Second")

        val deduped = dedupeNetworkShares(listOf(first, second))

        assertEquals(listOf(first), deduped)
    }

    private fun video(networkVideoId: String, name: String) = NetworkVideoItem(
        networkVideoId = networkVideoId,
        shareId = "share-1",
        filePath = "$name.mkv",
        fileName = "$name.mkv",
        sizeBytes = 1L,
        tmdbId = null,
        tmdbType = null,
        seriesGroupKey = null,
        seasonNumber = null,
        episodeNumber = null,
        releaseYear = null,
        name = name,
        sources = listOf(
            SpatialFinSource(
                id = networkVideoId,
                name = name,
                type = SpatialFinSourceType.NETWORK,
                path = "$name.mkv",
                size = 1L,
                mediaStreams = emptyList(),
            )
        ),
        runtimeTicks = 0L,
        playbackPositionTicks = 0L,
    )

    private fun share(id: String, displayName: String) = NetworkShareDto(
        id = id,
        protocol = "smb",
        host = "nas.local",
        shareName = "Movies",
        path = "smb://nas.local/Movies",
        displayName = displayName,
        username = null,
        password = null,
        domain = null,
        addedAtEpochMs = 0L,
        lastScannedAtEpochMs = null,
    )
}
