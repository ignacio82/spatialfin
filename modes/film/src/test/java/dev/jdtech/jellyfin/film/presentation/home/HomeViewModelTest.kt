package dev.jdtech.jellyfin.film.presentation.home

import android.app.Application
import android.content.Context
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.film.repository.MusicAssistantRepository
import dev.jdtech.jellyfin.models.SpatialFinImages
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.offline.OfflineSyncStatus
import dev.jdtech.jellyfin.offline.OfflineSyncStatusMonitor
import dev.jdtech.jellyfin.offline.ServerConnectionMonitor
import dev.jdtech.jellyfin.offline.ServerConnectionState
import dev.jdtech.jellyfin.plugins.repository.PluginContentRepository
import dev.jdtech.jellyfin.plugins.repository.PluginRepository
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.repository.NetworkMediaRepository
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionMode
import dev.jdtech.jellyfin.session.SessionState
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.HomeRowPreferences
import dev.jdtech.jellyfin.watchnext.WatchNextScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class HomeViewModelTest {

    private lateinit var app: Application
    private lateinit var prefs: AppPreferences
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        app = RuntimeEnvironment.getApplication()
        val sp = app.getSharedPreferences("home_vm_test", Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        prefs = AppPreferences(sp)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun sampleMovie(): SpatialFinMovie =
        SpatialFinMovie(
            id = UUID.randomUUID(),
            name = "MA Track",
            originalTitle = null,
            overview = "",
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            sources = emptyList(),
            runtimeTicks = 0L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            people = emptyList(),
            genres = emptyList(),
            communityRating = null,
            officialRating = null,
            status = "",
            productionYear = null,
            endDate = null,
            trailer = null,
            images = SpatialFinImages(),
            chapters = emptyList(),
            trickplayInfo = null,
        )

    private fun buildViewModel(
        sessionFlow: MutableStateFlow<SessionState>,
        repository: JellyfinRepository = mockk(relaxed = true),
        connectionMonitor: ServerConnectionMonitor = mockk(relaxed = true),
        musicAssistantRepository: MusicAssistantRepository = mockk(relaxed = true),
        networkMediaRepository: NetworkMediaRepository = mockk(relaxed = true),
        pluginContentRepository: PluginContentRepository = mockk(relaxed = true),
        watchNextScheduler: WatchNextScheduler = mockk(relaxed = true),
    ): HomeViewModel {
        every { repository.observeRealtimeEvents() } returns emptyFlow()
        val connState = MutableStateFlow(ServerConnectionState(serverAccessible = true))
        every { connectionMonitor.state } returns connState
        val syncMonitor = mockk<OfflineSyncStatusMonitor>(relaxed = true) {
            every { state } returns MutableStateFlow(OfflineSyncStatus())
        }
        val coordinator = mockk<SessionActivationCoordinator>(relaxed = true) {
            every { sessionState } returns sessionFlow
        }
        val db = mockk<ServerDatabaseDao>(relaxed = true) {
            every { observeNetworkShareCount() } returns MutableStateFlow(0)
            every { observeNetworkVideoCount() } returns MutableStateFlow(0)
        }
        val pluginRepo = mockk<PluginRepository>(relaxed = true) {
            every { settingsChanges } returns MutableSharedFlow()
        }
        coEvery { pluginContentRepository.getHomeByPlugin() } returns emptyList()
        coEvery { networkMediaRepository.getShares() } returns emptyList()
        return HomeViewModel(
            repository = repository,
            jellyfinApi = mockk<JellyfinApi>(relaxed = true),
            appPreferences = prefs,
            homeRowPreferences = HomeRowPreferences(prefs),
            homeStateCache = HomeStateCache { 0L },
            database = db,
            connectionMonitor = connectionMonitor,
            offlineSyncStatusMonitor = syncMonitor,
            watchNextScheduler = watchNextScheduler,
            sessionActivationCoordinator = coordinator,
            pluginContentRepository = pluginContentRepository,
            pluginRepository = pluginRepo,
            musicAssistantRepository = musicAssistantRepository,
            networkMediaRepository = networkMediaRepository,
            appContext = app,
        )
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
    fun `STANDALONE_SOURCES skips Jellyfin online fetches and loads Music Assistant`() =
        runTest(testDispatcher) {
            val sessionFlow = MutableStateFlow(
                SessionState(
                    isLoading = false,
                    sessionMode = SessionMode.STANDALONE_SOURCES,
                    sessionEpoch = 1L,
                ),
            )
            val repo = mockk<JellyfinRepository>(relaxed = true)
            val maRepo = mockk<MusicAssistantRepository>(relaxed = true) {
                coEvery { getRecentlyPlayed() } returns listOf(sampleMovie())
            }
            val watchNext = mockk<WatchNextScheduler>(relaxed = true)

            val vm = buildViewModel(
                sessionFlow = sessionFlow,
                repository = repo,
                musicAssistantRepository = maRepo,
                watchNextScheduler = watchNext,
            )
            vm.loadData(forceRefresh = true)
            awaitCondition { vm.state.value.musicAssistantSections.isNotEmpty() }

            assertFalse(vm.state.value.isLoading)
            assertNull(vm.state.value.error)
            assertEquals(1, vm.state.value.musicAssistantSections.size)
            coVerify(exactly = 0) { repo.getResumeItems() }
            coVerify(exactly = 0) { repo.getSuggestions() }
            verify(exactly = 0) { watchNext.syncNow(any()) }

            // Transition to SERVER_NEEDS_AUTH must clear musicAssistantSections
            sessionFlow.value = SessionState(
                isLoading = false,
                sessionMode = SessionMode.SERVER_NEEDS_AUTH,
                activeServerId = "srv-1",
                sessionEpoch = 2L,
            )
            awaitCondition { vm.state.value.musicAssistantSections.isEmpty() }
            assertTrue(vm.state.value.musicAssistantSections.isEmpty())
        }

    @Test
    fun `SERVER_AUTHENTICATED failure passes expectedEpoch to markServerInaccessible`() =
        runTest(testDispatcher) {
            val userId = UUID.randomUUID()
            val sessionFlow = MutableStateFlow(
                SessionState(
                    isLoading = false,
                    sessionMode = SessionMode.SERVER_AUTHENTICATED,
                    activeServerId = "srv-1",
                    activeUserId = userId,
                    sessionEpoch = 7L,
                ),
            )
            val repo = mockk<JellyfinRepository>(relaxed = true)
            val failure = IOException("Connection refused")
            coEvery { repo.getResumeItems() } throws failure

            val monitor = mockk<ServerConnectionMonitor>(relaxed = true) {
                every { shouldUseOfflineRepository() } returns false
                every { isConnectionFailure(any()) } returns true
            }

            val vm = buildViewModel(
                sessionFlow = sessionFlow,
                repository = repo,
                connectionMonitor = monitor,
            )
            vm.loadData(forceRefresh = true)
            awaitCondition { vm.state.value.error != null }

            verify(atLeast = 1) { monitor.markServerInaccessible(7L) }
        }
}
