package dev.jdtech.jellyfin.presentation.setup.welcome

import android.app.Application
import android.content.Context
import androidx.work.WorkManager
import dev.jdtech.jellyfin.companion.CompanionCandidate
import dev.jdtech.jellyfin.companion.CompanionConfigImporter
import dev.jdtech.jellyfin.companion.CompanionImportOutcome
import dev.jdtech.jellyfin.companion.CompanionLiveSyncClient
import dev.jdtech.jellyfin.companion.ImportMode
import dev.jdtech.jellyfin.models.companion.CompanionConfig
import dev.jdtech.jellyfin.models.companion.CompanionDiscoveryPayload
import dev.jdtech.jellyfin.models.companion.CompanionEndpoint
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.setup.lan.LanSetupController
import dev.jdtech.jellyfin.setup.lan.LanSetupMode
import dev.jdtech.jellyfin.setup.lan.LanSetupUiState
import dev.jdtech.jellyfin.setup.lan.LeaseHandle
import dev.jdtech.jellyfin.setup.lan.PendingHandoffUserSelection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
class CompanionViewModelTest {

    private lateinit var app: Application
    private lateinit var prefs: AppPreferences
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        app = RuntimeEnvironment.getApplication()
        val sp = app.getSharedPreferences("companion_vm_test", Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        prefs = AppPreferences(sp)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `QR NeedsUserSelection survives onScreenEntered, completes selection, and disconnect clears state`() =
        runTest(testDispatcher) {
            val importer = mockk<CompanionConfigImporter>(relaxed = true)
            val lanController = mockk<LanSetupController>(relaxed = true) {
                every { uiState } returns MutableStateFlow(LanSetupUiState())
                every { pendingHandoffSelection } returns MutableStateFlow<PendingHandoffUserSelection?>(null)
                every { acquire(any(), any()) } answers { LeaseHandle(UUID.randomUUID(), firstArg()) }
            }
            val coordinator = mockk<SessionActivationCoordinator>(relaxed = true)
            val wm = mockk<WorkManager>(relaxed = true)

            val candidate = CompanionCandidate("srv-1", "Home", UUID.randomUUID(), "Alice")
            val endpoint = CompanionEndpoint.parse("http://192.168.1.20:1982", "sf-setup-token1234567890")
            val config = CompanionConfig(version = 1, setup_token = "sf-setup-token1234567890")

            coEvery {
                importer.importFromEndpoint(any(), ImportMode.PAIRING, any(), any(), any(), any(), any())
            } returns CompanionImportOutcome.NeedsUserSelection(
                config = config,
                endpoint = endpoint,
                policy = UrlTargetPolicy.LAN_ONLY,
                candidates = listOf(candidate),
            )
            coEvery {
                importer.completeUserSelection(config, endpoint, UrlTargetPolicy.LAN_ONLY, "srv-1", candidate.userId, any(), null)
            } returns CompanionImportOutcome.Activated("http://192.168.1.50:8096")

            val liveSync = mockk<CompanionLiveSyncClient>(relaxed = true) {
                every { configChangedEvents } returns MutableSharedFlow()
            }
            val vm = CompanionViewModel(
                appContext = app,
                appPreferences = prefs,
                importer = importer,
                lanSetupController = lanController,
                sessionActivationCoordinator = coordinator,
                workManager = dagger.Lazy { wm },
                companionLiveSyncClient = liveSync,
            )

            // Per-entryId one-shot clearCompletedDrain guard
            vm.acquireReceiverLease("entry-1", clearCompletedDrain = true)
            vm.acquireReceiverLease("entry-1", clearCompletedDrain = true)
            verify(exactly = 1) { lanController.acquire(LanSetupMode.COMPANION_RECEIVER, true) }
            verify(exactly = 1) { lanController.acquire(LanSetupMode.COMPANION_RECEIVER, false) }

            vm.fetchAndApplyConfig(
                CompanionDiscoveryPayload(
                    version = 1,
                    companion_url = "http://192.168.1.20:1982",
                    setup_token = "sf-setup-token1234567890",
                ),
            )
            advanceUntilIdle()
            assertTrue(vm.state.value is CompanionState.NeedsUserSelection)

            // Survives onScreenEntered()
            vm.onScreenEntered()
            assertTrue(vm.state.value is CompanionState.NeedsUserSelection)

            // Complete user selection emits Activated event
            vm.completeQrUserSelection("srv-1", candidate.userId)
            advanceUntilIdle()
            val event = vm.events.first()
            assertTrue(event is CompanionEvent.Activated)
            assertEquals("http://192.168.1.50:8096", (event as CompanionEvent.Activated).serverUrl)

            // Disconnect cancels WorkManager jobs and clears prefs + coordinator
            prefs.setValue(prefs.companionUrl, "http://192.168.1.20:1982")
            prefs.setValue(prefs.companionToken, "tok")
            vm.disconnect()
            advanceUntilIdle()
            verify { wm.cancelUniqueWork("CompanionSync") }
            verify { wm.cancelUniqueWork("companion_sync") }
            verify { wm.cancelUniqueWork("CompanionSyncPush") }
            coVerify { coordinator.clearCompanionPreferencesOnDisconnect() }
            assertNull(vm.connectedCompanionUrl.value)
        }

    @Test
    fun `syncNow uses BACKGROUND_SYNC and preserves typed error code`() = runTest(testDispatcher) {
        prefs.setValue(prefs.companionUrl, "http://192.168.1.20:1982")
        prefs.setValue(prefs.companionToken, "sf-setup-token1234567890")
        val importer = mockk<CompanionConfigImporter>(relaxed = true)
        coEvery {
            importer.importFromEndpoint(any(), ImportMode.BACKGROUND_SYNC, any(), any(), any(), any(), any())
        } returns CompanionImportOutcome.Failed(OnboardingFailureCode.URL_TIMEOUT, "Timed out")

        val lanController = mockk<LanSetupController>(relaxed = true) {
            every { uiState } returns MutableStateFlow(LanSetupUiState())
            every { pendingHandoffSelection } returns MutableStateFlow(null)
        }
        val liveSync = mockk<CompanionLiveSyncClient>(relaxed = true) {
            every { configChangedEvents } returns MutableSharedFlow()
        }
        val vm = CompanionViewModel(
            appContext = app,
            appPreferences = prefs,
            importer = importer,
            lanSetupController = lanController,
            sessionActivationCoordinator = mockk(relaxed = true),
            workManager = dagger.Lazy { mockk(relaxed = true) },
            companionLiveSyncClient = liveSync,
        )

        vm.syncNow()
        advanceUntilIdle()
        val err = vm.state.value as CompanionState.Error
        assertEquals(OnboardingFailureCode.URL_TIMEOUT, err.code)
    }

    @Test
    fun `configChangedEvents push automatically retries pairWithSavedCompanion when in Error`() =
        runTest(testDispatcher) {
            prefs.setValue(prefs.companionUrl, "http://192.168.1.20:1982")
            prefs.setValue(prefs.companionToken, "sf-setup-token1234567890")
            val importer = mockk<CompanionConfigImporter>(relaxed = true)
            coEvery {
                importer.importFromEndpoint(any(), ImportMode.PAIRING, any(), any(), any(), any(), any())
            } returnsMany listOf(
                CompanionImportOutcome.Failed(OnboardingFailureCode.URL_TIMEOUT, "Timed out"),
                CompanionImportOutcome.Activated("http://192.168.1.50:8096"),
            )

            val lanController = mockk<LanSetupController>(relaxed = true) {
                every { uiState } returns MutableStateFlow(LanSetupUiState())
                every { pendingHandoffSelection } returns MutableStateFlow(null)
            }
            val pushFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            val liveSync = mockk<CompanionLiveSyncClient>(relaxed = true) {
                every { configChangedEvents } returns pushFlow
            }
            val vm = CompanionViewModel(
                appContext = app,
                appPreferences = prefs,
                importer = importer,
                lanSetupController = lanController,
                sessionActivationCoordinator = mockk(relaxed = true),
                workManager = dagger.Lazy { mockk(relaxed = true) },
                companionLiveSyncClient = liveSync,
            )

            vm.pairWithSavedCompanion()
            advanceUntilIdle()
            assertTrue(vm.state.value is CompanionState.Error)

            pushFlow.tryEmit(Unit)
            advanceUntilIdle()
            assertTrue(vm.state.value is CompanionState.Success)
            val event = vm.events.first()
            assertTrue(event is CompanionEvent.Activated)
        }

    @Test
    fun `configChangedEvents push refreshes NeedsUserSelection when not completing`() =
        runTest(testDispatcher) {
            val u1 = UUID.randomUUID()
            val u2 = UUID.randomUUID()
            val endpoint = CompanionEndpoint.parse("http://192.168.1.20:1982", "sf-setup-token1234567890")
            val cfg = CompanionConfig(version = 1, setup_token = "sf-setup-token1234567890")
            val initialCandidates = listOf(
                CompanionCandidate("srv-1", "Home", u1, "ignacio", isVerified = true),
                CompanionCandidate("srv-1", "Home", u2, "emilie", isVerified = false, needsRelink = true),
            )
            val refreshedCandidates = listOf(
                CompanionCandidate("srv-1", "Home", u1, "ignacio", isVerified = true),
                CompanionCandidate("srv-1", "Home", u2, "emilie", isVerified = true, needsRelink = false),
            )
            val importer = mockk<CompanionConfigImporter>(relaxed = true)
            coEvery {
                importer.importFromEndpoint(any(), ImportMode.PAIRING, any(), any(), any(), any(), any())
            } returnsMany listOf(
                CompanionImportOutcome.NeedsUserSelection(cfg, endpoint, UrlTargetPolicy.LAN_ONLY, initialCandidates),
                CompanionImportOutcome.NeedsUserSelection(cfg, endpoint, UrlTargetPolicy.LAN_ONLY, refreshedCandidates),
            )

            val lanController = mockk<LanSetupController>(relaxed = true) {
                every { uiState } returns MutableStateFlow(LanSetupUiState())
                every { pendingHandoffSelection } returns MutableStateFlow(null)
            }
            val pushFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
            val liveSync = mockk<CompanionLiveSyncClient>(relaxed = true) {
                every { configChangedEvents } returns pushFlow
            }
            val vm = CompanionViewModel(
                appContext = app,
                appPreferences = prefs,
                importer = importer,
                lanSetupController = lanController,
                sessionActivationCoordinator = mockk(relaxed = true),
                workManager = dagger.Lazy { mockk(relaxed = true) },
                companionLiveSyncClient = liveSync,
            )

            vm.fetchAndApplyConfig(
                CompanionDiscoveryPayload(
                    version = 1,
                    companion_url = "http://192.168.1.20:1982",
                    setup_token = "sf-setup-token1234567890",
                ),
            )
            advanceUntilIdle()
            val firstSel = vm.state.value as CompanionState.NeedsUserSelection
            assertEquals(true, firstSel.candidates[1].needsRelink)

            pushFlow.tryEmit(Unit)
            advanceUntilIdle()
            val secondSel = vm.state.value as CompanionState.NeedsUserSelection
            assertEquals(false, secondSel.candidates[1].needsRelink)
            assertEquals(true, secondSel.candidates[1].isVerified)
        }
}
