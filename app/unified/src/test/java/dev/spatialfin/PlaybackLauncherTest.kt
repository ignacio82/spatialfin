package dev.spatialfin

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.player.xr.MultitaskPlayerActivity
import dev.jdtech.jellyfin.player.xr.XrPlayerActivity
import dev.jdtech.jellyfin.presentation.player.PlayRequest
import dev.spatialfin.fcast.session.CastSessionManager
import dev.spatialfin.test.SpatialFinTestApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpatialFinTestApplication::class)
class PlaybackLauncherTest {

    private fun testContext(events: MutableList<String>, started: MutableList<Intent>): Context =
        mockk(relaxed = true) {
            every { packageName } returns "dev.spatialfin.debug"
            every { startActivity(any()) } answers {
                events += "startActivity"
                started += firstArg<Intent>()
            }
        }

    @Test
    fun `non-immersive library item prepares multitask before starting MultitaskPlayerActivity`() = runTest {
        val events = mutableListOf<String>()
        val started = mutableListOf<Intent>()
        val context = testContext(events, started)
        val itemId = UUID.randomUUID()
        val movie = mockk<SpatialFinMovie>(relaxed = true) { every { id } returns itemId }
        val fcastSession = mockk<CastSessionManager>(relaxed = true) {
            every { hasCastIntent() } returns false
        }

        launchPlayRequest(
            context = context,
            fcastSession = fcastSession,
            scope = this,
            request = PlayRequest.LibraryItem(
                itemId = itemId,
                itemKind = "Movie",
                item = movie,
                immersive = false,
            ),
            onPrepareMultitaskLaunch = { events += "prepareMultitask" },
        )

        assertEquals(listOf("prepareMultitask", "startActivity"), events)
        assertEquals(1, started.size)
        assertEquals(MultitaskPlayerActivity::class.java.name, started.single().component?.className)
    }

    @Test
    fun `immersive library item targets XrPlayerActivity without preparing multitask`() = runTest {
        val events = mutableListOf<String>()
        val started = mutableListOf<Intent>()
        val context = testContext(events, started)
        val itemId = UUID.randomUUID()
        val fcastSession = mockk<CastSessionManager>(relaxed = true) {
            every { hasCastIntent() } returns false
        }

        launchPlayRequest(
            context = context,
            fcastSession = fcastSession,
            scope = this,
            request = PlayRequest.LibraryItem(
                itemId = itemId,
                itemKind = "Movie",
                immersive = true,
            ),
            onPrepareMultitaskLaunch = { events += "prepareMultitask" },
        )

        assertEquals(listOf("startActivity"), events)
        assertEquals(XrPlayerActivity::class.java.name, started.single().component?.className)
    }

    @Test
    fun `openSyncPlayDialogOnStart library item targets XrPlayerActivity without preparing multitask`() = runTest {
        val events = mutableListOf<String>()
        val started = mutableListOf<Intent>()
        val context = testContext(events, started)
        val itemId = UUID.randomUUID()

        launchPlayRequest(
            context = context,
            fcastSession = null,
            scope = this,
            request = PlayRequest.LibraryItem(
                itemId = itemId,
                itemKind = "Movie",
                immersive = false,
                openSyncPlayDialogOnStart = true,
            ),
            onPrepareMultitaskLaunch = { events += "prepareMultitask" },
        )

        assertEquals(listOf("startActivity"), events)
        assertEquals(XrPlayerActivity::class.java.name, started.single().component?.className)
        assertTrue(started.single().getBooleanExtra("openSyncPlayDialogOnStart", false))
    }

    @Test
    fun `non-immersive library item with active FCast intent does not prepare multitask or launch locally`() = runTest {
        val events = mutableListOf<String>()
        val started = mutableListOf<Intent>()
        val context = testContext(events, started)
        val itemId = UUID.randomUUID()
        val movie = mockk<SpatialFinMovie>(relaxed = true) { every { id } returns itemId }
        val fcastSession = mockk<CastSessionManager>(relaxed = true) {
            every { hasCastIntent() } returns true
            every { splitAvMode } returns MutableStateFlow(false)
            coEvery { castSpatialItem(any(), any()) } returns true
        }

        launchPlayRequest(
            context = context,
            fcastSession = fcastSession,
            scope = this,
            request = PlayRequest.LibraryItem(
                itemId = itemId,
                itemKind = "Movie",
                item = movie,
                immersive = false,
                allowFcastRouting = true,
            ),
            onPrepareMultitaskLaunch = { events += "prepareMultitask" },
        )

        assertTrue(events.isEmpty())
        assertTrue(started.isEmpty())
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun `non-immersive local network and uri media prepare multitask before starting MultitaskPlayerActivity`() = runTest {
        val requests = listOf(
            PlayRequest.LocalMedia(
                mediaStoreId = 42L,
                startFromBeginning = false,
                immersive = false,
            ),
            PlayRequest.NetworkMedia(
                networkVideoId = "smb://nas/video.mkv",
                startFromBeginning = true,
                immersive = false,
            ),
            PlayRequest.UriMedia(
                uri = Uri.parse("content://example.provider/video.mp4"),
                title = "Sample",
                startFromBeginning = false,
                immersive = false,
            ),
        )

        for (request in requests) {
            val events = mutableListOf<String>()
            val started = mutableListOf<Intent>()
            val context = testContext(events, started)

            launchPlayRequest(
                context = context,
                fcastSession = null,
                scope = this,
                request = request,
                onPrepareMultitaskLaunch = { events += "prepareMultitask" },
            )

            assertEquals(listOf("prepareMultitask", "startActivity"), events)
            assertEquals(MultitaskPlayerActivity::class.java.name, started.single().component?.className)
        }
    }

    @Test
    fun `immersive local network and uri media target XrPlayerActivity without preparing multitask`() = runTest {
        val requests = listOf(
            PlayRequest.LocalMedia(
                mediaStoreId = 42L,
                startFromBeginning = false,
                immersive = true,
            ),
            PlayRequest.NetworkMedia(
                networkVideoId = "smb://nas/video.mkv",
                startFromBeginning = true,
                immersive = true,
            ),
            PlayRequest.UriMedia(
                uri = Uri.parse("content://example.provider/video.mp4"),
                title = "Sample",
                startFromBeginning = false,
                immersive = true,
            ),
        )

        for (request in requests) {
            val events = mutableListOf<String>()
            val started = mutableListOf<Intent>()
            val context = testContext(events, started)

            launchPlayRequest(
                context = context,
                fcastSession = null,
                scope = this,
                request = request,
                onPrepareMultitaskLaunch = { events += "prepareMultitask" },
            )

            assertEquals(listOf("startActivity"), events)
            assertEquals(XrPlayerActivity::class.java.name, started.single().component?.className)
        }
    }
}
