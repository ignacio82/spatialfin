package dev.spatialfin.unified

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.spatialfin.test.SpatialFinTestApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpatialFinTestApplication::class)
class PlayerVideoTrackSupportTest {
    private val viewModelStore = ViewModelStore()
    private val player = mockk<Player>(relaxed = true)
    private lateinit var viewModel: PlayerViewModel

    @Before
    fun setUp() {
        val application = RuntimeEnvironment.getApplication()
        val repository = mockk<JellyfinRepository>(relaxed = true)
        every { repository.observePlayStateMessages() } returns emptyFlow()
        every { repository.observeSyncPlayCommandMessages() } returns emptyFlow()
        every { repository.observeSyncPlayGroupUpdates() } returns emptyFlow()
        every { repository.observeGeneralCommandMessages() } returns emptyFlow()
        every { repository.observeSocketState() } returns emptyFlow()
        every { player.currentMediaItem } returns null
        viewModel = PlayerViewModel(
            application = application,
            playlistManager = mockk(relaxed = true),
            repository = repository,
            localMediaRepository = mockk(relaxed = true),
            networkMediaRepository = mockk(relaxed = true),
            appPreferences = AppPreferences(application.getSharedPreferences("video_tracks", 0)),
            savedStateHandle = SavedStateHandle(),
        )
        viewModelStore.put("player", viewModel)
        viewModel.replacePlayer(player)
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
    }

    @Test
    fun selectedVideoExceedingAdvertisedCapabilitiesKeepsPlaying() {
        viewModel.onTracksChanged(
            tracks(MimeTypes.VIDEO_H264, C.FORMAT_EXCEEDS_CAPABILITIES, selected = true),
        )

        verify(exactly = 0) { player.pause() }
        assertNull(viewModel.uiState.value.playbackError)
    }

    @Test
    fun unsupportedUnselectedVideoStillPausesAndReportsAnError() {
        viewModel.onTracksChanged(
            tracks(MimeTypes.VIDEO_DOLBY_VISION, C.FORMAT_UNSUPPORTED_SUBTYPE, selected = false),
        )

        verify(exactly = 1) { player.pause() }
        assertEquals("NO_SUPPORTED_VIDEO_TRACK", viewModel.uiState.value.playbackError?.detail)
    }

    @Test
    fun audioOnlyMediaKeepsPlaying() {
        viewModel.onTracksChanged(tracks(MimeTypes.AUDIO_AAC, C.FORMAT_HANDLED, selected = true))

        verify(exactly = 0) { player.pause() }
        assertNull(viewModel.uiState.value.playbackError)
    }

    private fun tracks(mimeType: String, support: Int, selected: Boolean): Tracks = Tracks(
        listOf(
            Tracks.Group(
                TrackGroup(Format.Builder().setSampleMimeType(mimeType).build()),
                false,
                intArrayOf(support),
                booleanArrayOf(selected),
            ),
        ),
    )
}
