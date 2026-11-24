package dev.jdtech.jellyfin.player.local.presentation

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.player.core.domain.models.PlayerContentSource
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.local.domain.PlaylistManager
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.repository.LocalMediaRepository
import dev.jdtech.jellyfin.repository.NetworkMediaRepository
import dev.jdtech.jellyfin.session.ActiveSessionObserver
import dev.jdtech.jellyfin.session.ActiveSessionSnapshot
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.XrProcessPreferenceBridge
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import io.mockk.coEvery
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerViewModelSessionTeardownTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var app: Application
    private lateinit var prefs: AppPreferences
    private val testDispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        app = RuntimeEnvironment.getApplication()
        val sp = app.getSharedPreferences("pvm_teardown_test", Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        prefs = AppPreferences(sp)
    }

    @After
    fun tearDown() {
        stores.forEach { store -> runCatching { store.clear() } }
        stores.clear()
        Dispatchers.resetMain()
    }

    private fun buildViewModel(
        playlistManager: PlaylistManager = mockk(relaxed = true),
        repository: JellyfinRepository = mockk(relaxed = true),
        lockStateFlow: MutableStateFlow<AppLockManager.LockState> =
            MutableStateFlow(AppLockManager.LockState.UNLOCKED),
        sessionStateFlow: MutableStateFlow<ActiveSessionSnapshot>,
        bridge: XrProcessPreferenceBridge = XrProcessPreferenceBridge.NoOp,
        database: ServerDatabaseDao? = null,
    ): PlayerViewModel {
        val lockObserver = object : AppLockObserver {
            override val lockState = lockStateFlow
            override val enrollmentVersion = MutableStateFlow(0L)
        }
        val sessionObserver = object : ActiveSessionObserver {
            override val activeSessionState = sessionStateFlow
            override suspend fun ensureInitialized() = sessionStateFlow.value
        }
        val store = ViewModelStore().also { stores.add(it) }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return PlayerViewModel(
                    application = app,
                    playlistManager = playlistManager,
                    repository = repository,
                    localMediaRepository = mockk<LocalMediaRepository>(relaxed = true),
                    networkMediaRepository = mockk<NetworkMediaRepository>(relaxed = true),
                    appPreferences = prefs,
                    savedStateHandle = SavedStateHandle(),
                    activeSessionObserver = sessionObserver,
                    appLockObserver = lockObserver,
                    xrProcessPreferenceBridge = bridge,
                    database = database,
                ) as T
            }
        }
        return ViewModelProvider(store, factory)[PlayerViewModel::class.java]
    }

    private suspend fun kotlinx.coroutines.test.TestScope.awaitCondition(
        timeoutMs: Long = 3_000L,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate() && System.currentTimeMillis() < deadline) {
            runCurrent()
            if (predicate()) return
            Thread.sleep(10L)
        }
        runCurrent()
    }

    @Test
    fun `App Lock LOCKED transition sets forceClosed and emits ForceClose`() = runTest(testDispatcher) {
        val lockFlow = MutableStateFlow(AppLockManager.LockState.UNLOCKED)
        val sessionFlow = MutableStateFlow(
            ActiveSessionSnapshot(
                isLoading = false,
                isAuthenticated = true,
                serverId = "srv-1",
                userId = UUID.randomUUID(),
                sessionEpoch = 2L,
            ),
        )
        val vm = buildViewModel(lockStateFlow = lockFlow, sessionStateFlow = sessionFlow)
        try {
            runCurrent()
            assertFalse(vm.forceClosed.value)

            lockFlow.value = AppLockManager.LockState.LOCKED
            runCurrent()
            assertTrue(vm.forceClosed.value)
            assertEquals(PlayerEvents.ForceClose, vm.eventsChannelFlow.first())
        } finally {
            stores.forEach { runCatching { it.clear() } }
            stores.clear()
        }
    }

    @Test
    fun `unauthenticated HTTP stream fails closed while verified local download binds read-only offlineLocal`() =
        runTest(testDispatcher) {
            try {
                val itemId = UUID.randomUUID()
                val sessionFlow = MutableStateFlow(
                    ActiveSessionSnapshot(
                        isLoading = false,
                        isAuthenticated = false,
                        serverId = "srv-1",
                        userId = null,
                        sessionEpoch = 1L,
                    ),
                )
                val httpPlaylist = mockk<PlaylistManager>(relaxed = true)
                coEvery {
                    httpPlaylist.getInitialItem(any(), any(), any(), any(), any(), any(), any())
                } returns PlayerItem(
                    name = "Remote Movie",
                    itemId = itemId,
                    mediaSourceId = "src-1",
                    playbackPosition = 0L,
                    mediaSourceUri = "https://example.com/Videos/1/stream.mp4",
                    contentSource = PlayerContentSource.JELLYFIN,
                )

                val vmHttp = buildViewModel(playlistManager = httpPlaylist, sessionStateFlow = sessionFlow)
                vmHttp.initializePlayer(itemId, "Movie", startFromBeginning = true)
                awaitCondition { vmHttp.forceClosed.value }
                assertTrue("HTTP stream while unauthenticated must fail closed", vmHttp.forceClosed.value)

                // Verified local file under srv-1 binds read-only offlineLocal = true
                val localFile = tempFolder.newFile("downloaded.mp4").apply { writeBytes(ByteArray(32)) }
                val localPlaylist = mockk<PlaylistManager>(relaxed = true)
                coEvery {
                    localPlaylist.getInitialItem(any(), any(), any(), any(), any(), any(), any())
                } returns PlayerItem(
                    name = "Downloaded Movie",
                    itemId = itemId,
                    mediaSourceId = "src-local",
                    playbackPosition = 0L,
                    mediaSourceUri = "file://${localFile.absolutePath}",
                    contentSource = PlayerContentSource.JELLYFIN,
                )
                val vmLocal = buildViewModel(playlistManager = localPlaylist, sessionStateFlow = sessionFlow)
                vmLocal.initializePlayer(itemId, "Movie", startFromBeginning = true)
                awaitCondition { vmLocal.isOfflineLocalPlayback || vmLocal.forceClosed.value }
                assertFalse(vmLocal.forceClosed.value)
                assertTrue(vmLocal.isOfflineLocalPlayback)

                // Deleting/changing srv-1 tears down offline-local playback
                sessionFlow.value = sessionFlow.value.copy(serverId = null)
                runCurrent()
                assertTrue(vmLocal.forceClosed.value)
            } finally {
                stores.forEach { runCatching { it.clear() } }
                stores.clear()
            }
        }
}
