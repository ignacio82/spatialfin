package dev.jdtech.jellyfin.models

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialFinAudioTest {

    @Test
    fun `album playback sorts by disc then track then name`() {
        val discTwoTrackOne = track(name = "Disc 2 Opening", discNumber = 2, trackNumber = 1)
        val discOneTrackTwo = track(name = "Beta", discNumber = 1, trackNumber = 2)
        val discOneTrackOne = track(name = "Alpha", discNumber = 1, trackNumber = 1)
        val noTrackNumber = track(name = "Zeta", discNumber = 1, trackNumber = null)

        val result =
            listOf(discTwoTrackOne, noTrackNumber, discOneTrackTwo, discOneTrackOne)
                .sortedForAlbumPlayback()

        assertEquals(listOf(discOneTrackOne, discOneTrackTwo, noTrackNumber, discTwoTrackOne), result)
    }

    @Test
    fun `playlist playback sorts by playlist index then name`() {
        val second = track(name = "B", trackNumber = 2)
        val first = track(name = "C", trackNumber = 1)
        val firstByName = track(name = "A", trackNumber = 1)
        val missingIndex = track(name = "D", trackNumber = null)

        val result = listOf(missingIndex, second, first, firstByName).sortedForPlaylistPlayback()

        assertEquals(listOf(firstByName, first, second, missingIndex), result)
    }

    @Test
    fun `queue item maps runtime resume and contributors`() {
        val sourceId = "media-source-1"
        val track =
            track(
                name = "Chapter 2",
                album = "The Book",
                artists = listOf("Narrator"),
                runtimeTicks = 3_000_000_000L,
                playbackPositionTicks = 900_000_000L,
                mediaSourceId = sourceId,
            )

        val result = track.toAudioQueueItem()

        assertEquals(track.id, result.id)
        assertEquals("Chapter 2", result.title)
        assertEquals("Narrator", result.subtitle)
        assertEquals("The Book", result.album)
        assertEquals(300_000L, result.durationMs)
        assertEquals(90_000L, result.resumePositionMs)
        assertEquals(sourceId, result.mediaSourceId)
    }

    @Test
    fun `loose audiobook tracks group by album metadata`() {
        val albumId = UUID.randomUUID()
        val trackTwo =
            track(
                name = "Part 02",
                album = "A Book",
                albumId = albumId,
                albumArtist = "Author",
                trackNumber = 2,
                isAudiobook = true,
            )
        val trackOne =
            track(
                name = "Part 01",
                album = "A Book",
                albumId = albumId,
                albumArtist = "Author",
                trackNumber = 1,
                isAudiobook = true,
            )
        val music = track(name = "Song", album = "Album", isAudiobook = false)

        val result = listOf(trackTwo, music, trackOne).filter { it.isAudiobook }.groupLooseAudioBooks()

        assertEquals(1, result.size)
        assertEquals(albumId, result.single().id)
        assertEquals("A Book", result.single().name)
        assertEquals("Author", result.single().author)
        assertTrue(result.single().synthetic)
        assertEquals(listOf(trackOne, trackTwo), result.single().tracks)
    }

    private fun track(
        name: String,
        discNumber: Int? = 1,
        trackNumber: Int? = 1,
        album: String? = "Album",
        albumId: UUID? = UUID.randomUUID(),
        albumArtist: String? = "Album Artist",
        artists: List<String> = emptyList(),
        runtimeTicks: Long = 0L,
        playbackPositionTicks: Long = 0L,
        mediaSourceId: String? = null,
        isAudiobook: Boolean = false,
    ): SpatialFinAudioTrack =
        SpatialFinAudioTrack(
            id = UUID.randomUUID(),
            name = name,
            originalTitle = null,
            overview = "",
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            sources = emptyList(),
            runtimeTicks = runtimeTicks,
            playbackPositionTicks = playbackPositionTicks,
            unplayedItemCount = null,
            images = SpatialFinImages(),
            chapters = emptyList(),
            ratings = emptyList(),
            album = album,
            albumId = albumId,
            albumArtist = albumArtist,
            artists = artists,
            artistItems = emptyList(),
            discNumber = discNumber,
            trackNumber = trackNumber,
            playlistItemId = null,
            mediaSourceId = mediaSourceId,
            container = null,
            hasLyrics = false,
            isAudiobook = isAudiobook,
        )
}
