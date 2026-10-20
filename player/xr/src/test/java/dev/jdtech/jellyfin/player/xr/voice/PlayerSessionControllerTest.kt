package dev.jdtech.jellyfin.player.xr.voice

import androidx.media3.common.C
import androidx.media3.common.IllegalSeekPositionException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import dev.jdtech.jellyfin.player.session.voice.PlayerSessionController
import dev.jdtech.jellyfin.player.session.voice.XrPlayerAction
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerSessionControllerTest {

    private val player = mockk<Player>(relaxed = true)
    private val viewModel = mockk<PlayerViewModel>(relaxed = true)
    private lateinit var controller: PlayerSessionController

    @Before
    fun setUp() {
        every { viewModel.player } returns player

        controller = PlayerSessionController(
            viewModel = viewModel,
            player = player,
            onControlsVisibilityChange = {},
            onNavigateBack = {},
            onShowVoiceSearch = { _, _, _ -> },
            onShowSyncPlayDialog = {},
            onGoHome = { false },
            onCloseApp = {},
            onLaunchSearchResult = {},
            onSearchQuery = { emptyList() },
            getAvailableSyncPlayGroups = { emptyList() },
            setPassthroughEnabled = {},
            getPassthroughEnabled = { false },
        )
    }

    @Test
    fun `SeekForward clamps target position to duration when near end`() = runTest {
        every { player.duration } returns 60_000L
        every { player.currentPosition } returns 50_000L

        val feedback = controller.dispatch(XrPlayerAction.SeekForward(15))

        assertEquals("Skipped forward 15s", feedback)
        // 50s + 15s = 65s > 60s duration -> clamped to 60s (60_000 ms)
        verify { player.seekTo(60_000L) }
    }

    @Test
    fun `SeekForward handles negative or unset currentPosition safely without overflow`() = runTest {
        every { player.duration } returns 120_000L
        every { player.currentPosition } returns C.TIME_UNSET // -Long.MAX_VALUE

        val feedback = controller.dispatch(XrPlayerAction.SeekForward(15))

        assertEquals("Skipped forward 15s", feedback)
        // coerceAtLeast(0) -> 0 + 15s = 15s (15_000 ms)
        verify { player.seekTo(15_000L) }
    }

    @Test
    fun `SeekBackward clamps to zero`() = runTest {
        every { player.duration } returns 60_000L
        every { player.currentPosition } returns 5_000L

        val feedback = controller.dispatch(XrPlayerAction.SeekBackward(10))

        assertEquals("Rewound 10s", feedback)
        // 5s - 10s = -5s -> coerced to 0 ms
        verify { player.seekTo(0L) }
    }

    @Test
    fun `SeekTo clamps between 0 and duration`() = runTest {
        every { player.duration } returns 100_000L

        controller.dispatch(XrPlayerAction.SeekTo(150L))
        verify { player.seekTo(100_000L) }

        controller.dispatch(XrPlayerAction.SeekTo(-10L))
        verify { player.seekTo(0L) }
    }

    @Test
    fun `SeekForward does not crash when player throws IllegalSeekPositionException`() = runTest {
        every { player.duration } returns 60_000L
        every { player.currentPosition } returns 10_000L
        every { player.seekTo(any()) } throws IllegalSeekPositionException(Timeline.EMPTY, 0, 25_000L)

        // Must not rethrow exception
        val feedback = controller.dispatch(XrPlayerAction.SeekForward(15))
        assertEquals("Skipped forward 15s", feedback)
    }
}
