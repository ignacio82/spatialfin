package dev.jdtech.jellyfin.player.local.presentation

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.local.domain.getTrackNames
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import android.content.Context
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerTrackSelectorTest {

    private val application = RuntimeEnvironment.getApplication()
    private val sharedPreferences = application.getSharedPreferences("test_prefs", Context.MODE_PRIVATE)
    private val appPreferences = AppPreferences(sharedPreferences)
    private val host = mockk<PlayerTrackSelector.Host>(relaxed = true)
    private val player = mockk<Player>(relaxed = true)

    private lateinit var selector: PlayerTrackSelector
    private var visualSubtitlesEnabled = false

    @Before
    fun setUp() {
        sharedPreferences.edit().clear().commit()
        visualSubtitlesEnabled = false

        every { host.player } returns player
        every { host.setVisualSubtitlesEnabled(any()) } answers {
            visualSubtitlesEnabled = firstArg()
        }

        appPreferences.setValue(appPreferences.smartSpokenLanguages, "en,eng")
        appPreferences.setValue(appPreferences.smartForcedSubtitles, true)
        appPreferences.setValue(appPreferences.smartPreferOriginalAudio, false)
        appPreferences.setValue(appPreferences.nonAnimeSubtitleDisabled, false)

        selector = PlayerTrackSelector(application, appPreferences, host)
    }

    private fun createMediaItem(id: String = "item1"): MediaItem {
        return MediaItem.Builder().setMediaId(id).build()
    }

    private fun createPlayerItem(
        id: String = "item1",
        genres: List<String> = emptyList(),
    ): PlayerItem {
        return PlayerItem(
            name = "Test Media",
            itemId = UUID.randomUUID(),
            mediaSourceId = "ms1",
            playbackPosition = 0L,
            genres = genres,
        )
    }

    private fun createAudioGroup(language: String?, label: String = "Audio"): Tracks.Group {
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

    private fun createSubtitleGroup(
        language: String?,
        label: String,
        selectionFlags: Int = 0,
    ): Tracks.Group {
        val format = Format.Builder()
            .setLanguage(language)
            .setLabel(label)
            .setSelectionFlags(selectionFlags)
            .setSampleMimeType(MimeTypes.TEXT_VTT)
            .build()
        return Tracks.Group(
            TrackGroup(format),
            false,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(true),
        )
    }

    @Test
    fun `autoPickForcedSubtitle selects English forced track when audio is English`() {
        val mediaItem = createMediaItem("item1")
        every { player.currentMediaItem } returns mediaItem
        every { host.currentPlayerItem() } returns createPlayerItem()

        val audioGroup = createAudioGroup("eng", "English")
        val fullSubGroup = createSubtitleGroup("eng", "English", 0)
        val forcedSubGroup = createSubtitleGroup("eng", "English (Forced)", C.SELECTION_FLAG_FORCED)

        val tracks = Tracks(listOf(audioGroup, fullSubGroup, forcedSubGroup))
        every { player.currentTracks } returns tracks
        every { player.trackSelectionParameters } returns TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT

        selector.applySmart()

        assertTrue("Visual subtitles should be enabled for forced track", visualSubtitlesEnabled)
        val slot = slot<TrackSelectionParameters>()
        verify { player.trackSelectionParameters = capture(slot) }
        assertFalse(slot.captured.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
    }

    @Test
    fun `autoPickForcedSubtitle selects track labeled Foreign Dialogue`() {
        val mediaItem = createMediaItem("item2")
        every { player.currentMediaItem } returns mediaItem
        every { host.currentPlayerItem() } returns createPlayerItem()

        val audioGroup = createAudioGroup("eng", "English")
        val fullSubGroup = createSubtitleGroup("eng", "English Full Dialogue", 0)
        val foreignPartsGroup = createSubtitleGroup("eng", "Foreign Dialogue", 0)

        val tracks = Tracks(listOf(audioGroup, fullSubGroup, foreignPartsGroup))
        every { player.currentTracks } returns tracks
        every { player.trackSelectionParameters } returns TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT

        selector.applySmart()

        assertTrue("Visual subtitles should be enabled for Foreign Dialogue", visualSubtitlesEnabled)
    }

    @Test
    fun `autoPickForcedSubtitle works when audio track language is missing`() {
        val mediaItem = createMediaItem("item3")
        every { player.currentMediaItem } returns mediaItem
        every { host.currentPlayerItem() } returns createPlayerItem()

        val untaggedAudioGroup = createAudioGroup(null, "Stereo")
        val forcedSubGroup = createSubtitleGroup("eng", "English (Forced)", C.SELECTION_FLAG_FORCED)

        val tracks = Tracks(listOf(untaggedAudioGroup, forcedSubGroup))
        every { player.currentTracks } returns tracks
        every { player.trackSelectionParameters } returns TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT

        selector.applySmart()

        assertTrue("Forced subtitle should be enabled even when audio is untagged", visualSubtitlesEnabled)
    }

    @Test
    fun `autoPickForcedSubtitle selects untagged forced track when audio is understood`() {
        val mediaItem = createMediaItem("item4")
        every { player.currentMediaItem } returns mediaItem
        every { host.currentPlayerItem() } returns createPlayerItem()

        val audioGroup = createAudioGroup("eng", "English")
        val untaggedForcedGroup = createSubtitleGroup(null, "Forced", C.SELECTION_FLAG_FORCED)

        val tracks = Tracks(listOf(audioGroup, untaggedForcedGroup))
        every { player.currentTracks } returns tracks
        every { player.trackSelectionParameters } returns TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT

        selector.applySmart()

        assertTrue("Untagged forced track should be selected when audio is understood", visualSubtitlesEnabled)
    }

    @Test
    fun `full dialogue subtitles remain off when audio is understood and no forced track exists`() {
        val mediaItem = createMediaItem("item5")
        every { player.currentMediaItem } returns mediaItem
        every { host.currentPlayerItem() } returns createPlayerItem()

        val audioGroup = createAudioGroup("eng", "English")
        val fullSubGroup = createSubtitleGroup("eng", "English", 0)

        val tracks = Tracks(listOf(audioGroup, fullSubGroup))
        every { player.currentTracks } returns tracks
        every { player.trackSelectionParameters } returns TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT

        selector.applySmart()

        assertFalse("Full dialogue subtitles should stay off when audio is understood", visualSubtitlesEnabled)
    }

    @Test
    fun `full dialogue subtitles are selected when main audio is foreign`() {
        val mediaItem = createMediaItem("item6")
        every { player.currentMediaItem } returns mediaItem
        every { host.currentPlayerItem() } returns createPlayerItem()

        val audioGroup = createAudioGroup("jpn", "Japanese")
        val englishSubGroup = createSubtitleGroup("eng", "English", 0)

        val tracks = Tracks(listOf(audioGroup, englishSubGroup))
        every { player.currentTracks } returns tracks
        every { player.trackSelectionParameters } returns TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT

        selector.applySmart()

        assertTrue("Full dialogue subtitles should be enabled when main audio is foreign", visualSubtitlesEnabled)
    }

    @Test
    fun `forced subtitles are skipped if smartForcedSubtitles preference is disabled`() {
        appPreferences.setValue(appPreferences.smartForcedSubtitles, false)

        val mediaItem = createMediaItem("item7")
        every { player.currentMediaItem } returns mediaItem
        every { host.currentPlayerItem() } returns createPlayerItem()

        val audioGroup = createAudioGroup("eng", "English")
        val forcedSubGroup = createSubtitleGroup("eng", "English (Forced)", C.SELECTION_FLAG_FORCED)

        val tracks = Tracks(listOf(audioGroup, forcedSubGroup))
        every { player.currentTracks } returns tracks
        every { player.trackSelectionParameters } returns TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT

        selector.applySmart()

        assertFalse("Forced subtitles should not be enabled if disabled in preferences", visualSubtitlesEnabled)
    }

    @Test
    fun `auto selects audio track using MediaStreams metadata when container language tag is missing`() {
        val mediaItem = createMediaItem("item8")
        every { player.currentMediaItem } returns mediaItem
        every { host.currentPlayerItem() } returns createPlayerItem()

        // Track 1 is Portuguese; Track 2 has no language/label in container (like MKV with missing lang tag)
        val audioGroup1 = createAudioGroup("por", "Brazilian")
        val audioGroup2 = createAudioGroup(null, "")

        val tracks = Tracks(listOf(audioGroup1, audioGroup2))
        every { player.currentTracks } returns tracks
        every { player.trackSelectionParameters } returns TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT

        val stream1 = createMediaStream(index = 1, language = "por", title = "Brazilian", displayTitle = "Brazilian - Portuguese")
        val stream2 = createMediaStream(index = 2, language = "eng", title = "", displayTitle = "English - Dolby Digital Plus")
        every { host.currentMediaSourceStreams } returns listOf(stream1, stream2)

        selector.applySmart()

        val slot = slot<TrackSelectionParameters>()
        verify { player.trackSelectionParameters = capture(slot) }
        val overrides = slot.captured.overrides
        assertEquals("Should select audio track override for English stream", 1, overrides.size)
        assertEquals(audioGroup2.mediaTrackGroup, overrides.keys.first())
    }

    @Test
    fun `getTrackNames falls back to MediaStreams metadata when container format is missing language and label`() {
        val audioGroup1 = createAudioGroup("por", "Brazilian")
        val audioGroup2 = createAudioGroup(null, "")

        val stream1 = createMediaStream(index = 1, language = "por", title = "Brazilian", displayTitle = "Brazilian - Portuguese")
        val stream2 = createMediaStream(index = 2, language = "eng", title = "", displayTitle = "English - Dolby Digital Plus")
        val streams = listOf(stream1, stream2)

        val trackNames = listOf(audioGroup1, audioGroup2).getTrackNames(streams)
        assertEquals(2, trackNames.size)
        assertTrue("Track 1 should include Brazilian or Portuguese", trackNames[0].contains("Brazilian") || trackNames[0].contains("Portuguese"))
        assertTrue("Track 2 should resolve English from MediaStreams", trackNames[1].contains("English"))
    }

    private fun createMediaStream(
        index: Int,
        language: String,
        title: String = "",
        displayTitle: String? = null,
        type: org.jellyfin.sdk.model.api.MediaStreamType = org.jellyfin.sdk.model.api.MediaStreamType.AUDIO,
        codec: String = "eac3",
    ): dev.jdtech.jellyfin.models.SpatialFinMediaStream {
        return dev.jdtech.jellyfin.models.SpatialFinMediaStream(
            index = index,
            title = title,
            displayTitle = displayTitle,
            language = language,
            type = type,
            codec = codec,
            isExternal = false,
            path = null,
            channelLayout = "5.1",
            videoRangeType = null,
            height = null,
            width = null,
            videoDoViTitle = null,
        )
    }
}
