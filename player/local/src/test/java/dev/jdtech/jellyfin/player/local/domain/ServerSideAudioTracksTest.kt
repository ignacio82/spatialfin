package dev.jdtech.jellyfin.player.local.domain

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import dev.jdtech.jellyfin.models.SpatialFinMediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Media3's TrackGroup reaches into android.text.TextUtils in its constructor.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServerSideAudioTracksTest {

    private fun audioGroup(language: String?, label: String = "Audio"): Tracks.Group {
        val format = Format.Builder()
            .setLanguage(language)
            .setLabel(label)
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .build()
        return Tracks.Group(
            TrackGroup(format),
            false,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(true),
        )
    }

    private fun audioStream(
        index: Int,
        language: String = "",
        title: String = "",
        displayTitle: String? = null,
        isDefault: Boolean = false,
    ) = SpatialFinMediaStream(
        index = index,
        title = title,
        displayTitle = displayTitle,
        language = language,
        type = MediaStreamType.AUDIO,
        codec = "eac3",
        isExternal = false,
        path = null,
        channelLayout = "5.1",
        videoRangeType = null,
        height = null,
        width = null,
        videoDoViTitle = null,
        isDefault = isDefault,
    )

    /** Silo S3:E2 — Portuguese default, untagged English, delivered by transcode. */
    private val siloStreams = listOf(
        audioStream(
            index = 1,
            language = "por",
            title = "Brazilian",
            displayTitle = "Portuguese - Dolby Digital Plus - 5.1",
            isDefault = true,
        ),
        audioStream(
            index = 2,
            language = "",
            title = "English",
            displayTitle = "English - Dolby Digital Plus + Dolby Atmos - 5.1",
        ),
    )

    @Test
    fun `transcoded single track offers every source stream`() {
        val tracks = serverSideAudioTracks(
            audioTrackGroups = listOf(audioGroup(null, "")),
            mediaStreams = siloStreams,
            activeStreamIndex = 2,
        )

        assertEquals(2, tracks.size)
        assertEquals(listOf(1, 2), tracks.map { it.streamIndex })
        assertTrue("English label comes from displayTitle", tracks[1].label.contains("English"))
    }

    @Test
    fun `selection follows what the server said it delivered`() {
        // The delivered track has no language tag of its own, so the only
        // trustworthy answer is the index Jellyfin echoed back.
        val tracks = serverSideAudioTracks(listOf(audioGroup(null, "")), siloStreams, activeStreamIndex = 2)
        assertEquals(listOf(false, true), tracks.map { it.isSelected })

        val portuguese = serverSideAudioTracks(listOf(audioGroup(null, "")), siloStreams, activeStreamIndex = 1)
        assertEquals(listOf(true, false), portuguese.map { it.isSelected })
    }

    @Test
    fun `falls back to the container default when the server said nothing`() {
        val tracks = serverSideAudioTracks(listOf(audioGroup(null, "")), siloStreams, activeStreamIndex = null)
        assertEquals(listOf(true, false), tracks.map { it.isSelected })
    }

    @Test
    fun `direct play keeps local switching`() {
        // Both streams present as their own tracks: the track selector can
        // switch instantly, so no server round trip should be offered.
        val tracks = serverSideAudioTracks(
            audioTrackGroups = listOf(audioGroup("por"), audioGroup(null, "English")),
            mediaStreams = siloStreams,
            activeStreamIndex = 2,
        )
        assertTrue(tracks.isEmpty())
    }

    @Test
    fun `a single audio stream needs no switching at all`() {
        val tracks = serverSideAudioTracks(
            audioTrackGroups = listOf(audioGroup("eng")),
            mediaStreams = listOf(siloStreams[1]),
            activeStreamIndex = 2,
        )
        assertTrue(tracks.isEmpty())
    }

    @Test
    fun `paired streams are dropped when the counts disagree`() {
        // One delivered track, two source streams: attributing the track to
        // stream 0 would label the English transcode "Portuguese".
        val paired = listOf(audioGroup(null, "")).pairedStreams(siloStreams)
        assertEquals(listOf(null), paired)
    }

    @Test
    fun `paired streams line up when direct playing`() {
        val groups = listOf(audioGroup("por"), audioGroup(null, "English"))
        val paired = groups.pairedStreams(siloStreams)
        assertEquals(listOf(1, 2), paired.map { it?.index })
    }
}
