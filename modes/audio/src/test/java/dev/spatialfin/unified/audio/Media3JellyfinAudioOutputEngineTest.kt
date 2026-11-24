package dev.spatialfin.unified.audio

import android.app.Application
import dev.jdtech.jellyfin.models.AudioQueueItem
import dev.jdtech.jellyfin.models.SpatialFinAudioTrack
import dev.jdtech.jellyfin.models.SpatialFinImages
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionMode
import dev.jdtech.jellyfin.session.SessionState
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Media3JellyfinAudioOutputEngineTest {

    private lateinit var app: Application
    private val testDispatcher = StandardTestDispatcher()
    private val engines = mutableListOf<Media3JellyfinAudioOutputEngine>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        app = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() {
        engines.forEach { runCatching { it.release() } }
        engines.clear()
        Dispatchers.resetMain()
    }

    private fun sampleQueueItem(id: UUID = UUID.randomUUID()): AudioQueueItem {
        val track = SpatialFinAudioTrack(
            id = id,
            name = "Track 1",
            originalTitle = null,
            overview = "",
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            sources = emptyList(),
            runtimeTicks = 600_000_000L,
            playbackPositionTicks = 0L,
            images = SpatialFinImages(),
            chapters = emptyList(),
            ratings = emptyList(),
            artists = listOf("Artist"),
            artistItems = emptyList(),
            albumArtist = "Artist",
            album = "Album",
            albumId = null,
            trackNumber = 1,
            discNumber = 1,
            playlistItemId = null,
            mediaSourceId = "src-1",
            container = "mp3",
            hasLyrics = false,
            isAudiobook = false,
        )
        return AudioQueueItem(
            id = id,
            title = "Track 1",
            subtitle = "Artist",
            album = "Album",
            artwork = null,
            durationMs = 60_000L,
            resumePositionMs = 0L,
            mediaSourceId = "src-1",
            chapters = emptyList(),
            track = track,
        )
    }

    private fun buildEngine(
        repository: JellyfinRepository = mockk(relaxed = true),
        sessionStateFlow: MutableStateFlow<SessionState>,
        lockStateFlow: MutableStateFlow<AppLockManager.LockState> =
            MutableStateFlow(AppLockManager.LockState.UNLOCKED),
        activating: AtomicBoolean = AtomicBoolean(false),
    ): Media3JellyfinAudioOutputEngine {
        val coordinator = mockk<SessionActivationCoordinator>(relaxed = true) {
            every { sessionState } returns sessionStateFlow
            every { isActivating } returns activating
        }
        val lockObserver = object : AppLockObserver {
            override val lockState = lockStateFlow
            override val enrollmentVersion = MutableStateFlow(0L)
        }
        return Media3JellyfinAudioOutputEngine(
            context = app,
            repository = repository,
            sessionActivationCoordinator = coordinator,
            appLockObserver = lockObserver,
        ).also { engines.add(it) }
    }

    @Test
    fun `playQueue is blocked while LOCKED or unauthenticated`() = runTest(testDispatcher) {
        val item = sampleQueueItem()
        val repository = mockk<JellyfinRepository>(relaxed = true)
        coEvery { repository.getAudioStreamUrl(any(), any()) } returns "http://192.168.1.50:8096/Audio/1/stream"

        val lockFlow = MutableStateFlow(AppLockManager.LockState.LOCKED)
        val userId = UUID.randomUUID()
        val sessionFlow = MutableStateFlow(
            SessionState(
                isLoading = false,
                sessionMode = SessionMode.SERVER_AUTHENTICATED,
                activeServerId = "srv-1",
                activeUserId = userId,
                sessionEpoch = 1L,
            ),
        )
        val engine = buildEngine(repository = repository, sessionStateFlow = sessionFlow, lockStateFlow = lockFlow)
        try {
            runCurrent()

            engine.playQueue(listOf(item), 0)
            runCurrent()
            assertTrue(engine.state.value.queue.isEmpty())
            coVerify(exactly = 0) { repository.getAudioStreamUrl(any(), any()) }

            // Unlock, but switch to unauthenticated standalone mode -> still blocked
            lockFlow.value = AppLockManager.LockState.UNLOCKED
            sessionFlow.value = SessionState(
                isLoading = false,
                sessionMode = SessionMode.STANDALONE_SOURCES,
                activeServerId = null,
                activeUserId = null,
                sessionEpoch = 2L,
            )
            runCurrent()
            engine.playQueue(listOf(item), 0)
            runCurrent()
            assertTrue(engine.state.value.queue.isEmpty())
            coVerify(exactly = 0) { repository.getAudioStreamUrl(any(), any()) }
        } finally {
            engine.release()
        }
    }

    @Test
    fun `LOCKED tears down active queue and blocks lyricsFor`() = runTest(testDispatcher) {
        val item = sampleQueueItem()
        val repository = mockk<JellyfinRepository>(relaxed = true)
        coEvery { repository.getAudioStreamUrl(any(), any()) } returns "http://192.168.1.50:8096/Audio/1/stream"

        val lockFlow = MutableStateFlow(AppLockManager.LockState.UNLOCKED)
        val userId = UUID.randomUUID()
        val sessionFlow = MutableStateFlow(
            SessionState(
                isLoading = false,
                sessionMode = SessionMode.SERVER_AUTHENTICATED,
                activeServerId = "srv-1",
                activeUserId = userId,
                sessionEpoch = 3L,
            ),
        )
        val engine = buildEngine(repository = repository, sessionStateFlow = sessionFlow, lockStateFlow = lockFlow)
        try {
            runCurrent()

            engine.playQueue(listOf(item), 0)
            runCurrent()
            assertEquals(1, engine.state.value.queue.size)

            lockFlow.value = AppLockManager.LockState.LOCKED
            runCurrent()
            assertTrue("Queue must clear when App Lock transitions to LOCKED", engine.state.value.queue.isEmpty())
            assertEquals(0, engine.player.mediaItemCount)
            assertNull(engine.lyricsFor(item.id))
        } finally {
            engine.release()
        }
    }

    @Test
    fun `same-user epoch bump preserves queue while user switch tears down`() = runTest(testDispatcher) {
        val item = sampleQueueItem()
        val repository = mockk<JellyfinRepository>(relaxed = true)
        coEvery { repository.getAudioStreamUrl(any(), any()) } returns "http://192.168.1.50:8096/Audio/1/stream"

        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        val sessionFlow = MutableStateFlow(
            SessionState(
                isLoading = false,
                sessionMode = SessionMode.SERVER_AUTHENTICATED,
                activeServerId = "srv-1",
                activeUserId = userA,
                sessionEpoch = 10L,
            ),
        )
        val engine = buildEngine(repository = repository, sessionStateFlow = sessionFlow)
        try {
            runCurrent()

            engine.playQueue(listOf(item), 0)
            runCurrent()
            assertEquals(1, engine.state.value.queue.size)

            // Same (serverId, userId) with incremented sessionEpoch keeps playing
            sessionFlow.value = sessionFlow.value.copy(sessionEpoch = 11L)
            runCurrent()
            assertEquals(1, engine.state.value.queue.size)

            // Switching to userB tears down immediately
            sessionFlow.value = sessionFlow.value.copy(activeUserId = userB, sessionEpoch = 12L)
            runCurrent()
            assertTrue(engine.state.value.queue.isEmpty())
            assertEquals(0, engine.player.mediaItemCount)
        } finally {
            engine.release()
        }
    }
}
