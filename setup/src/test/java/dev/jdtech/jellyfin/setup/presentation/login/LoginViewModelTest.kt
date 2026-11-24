package dev.jdtech.jellyfin.setup.presentation.login

import androidx.lifecycle.SavedStateHandle
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.quickconnect.PollResult
import dev.jdtech.jellyfin.quickconnect.QuickConnectClient
import dev.jdtech.jellyfin.quickconnect.QuickConnectInitiated
import dev.jdtech.jellyfin.session.ActivationResult
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionChangeReason
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import dev.jdtech.jellyfin.setup.lan.LanSetupController
import dev.jdtech.jellyfin.setup.lan.LanSetupMode
import dev.jdtech.jellyfin.setup.lan.LanSetupUiState
import dev.jdtech.jellyfin.setup.lan.LeaseHandle
import dev.jdtech.jellyfin.setup.quickconnect.QuickConnectController
import dev.jdtech.jellyfin.setup.quickconnect.QuickConnectState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.UserDto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `bindTarget is idempotent for same target, pins serverId on local QC, and canonicalizes srv- prefix`() =
        runTest(testDispatcher) {
            val userId = UUID.randomUUID()
            val qcClient = mockk<QuickConnectClient>()
            coEvery { qcClient.checkEnabled(any(), any()) } returns true
            coEvery { qcClient.initiate(any(), any()) } returns QuickConnectInitiated("111222", "sec-1")
            coEvery { qcClient.pollOnce(any(), "sec-1", any()) } returns PollResult.Authenticated

            val authMismatch = mockk<AuthenticationResult>(relaxed = true) {
                every { serverId } returns "different-server-id"
                every { accessToken } returns "tok"
                every { user } returns mockk<UserDto>(relaxed = true) {
                    every { id } returns userId
                    every { name } returns "Alice"
                }
            }
            coEvery { qcClient.authenticate(any(), "sec-1", any()) } returns authMismatch

            val repo = mockk<SetupRepository>(relaxed = true)
            val coordinator = mockk<SessionActivationCoordinator>(relaxed = true)
            val lanController = mockk<LanSetupController>(relaxed = true) {
                every { uiState } returns MutableStateFlow(LanSetupUiState())
                every { isActiveSession(any()) } returns false
                every { acquire(any(), any()) } answers { LeaseHandle(UUID.randomUUID(), firstArg()) }
            }

            val factory = QuickConnectController.Factory(
                quickConnectClient = qcClient,
                elapsedRealtimeMs = { 10_000L },
                clockEpochMs = { 1_700_000_000_000L },
                pollIntervalMs = 50L,
            )
            val vm = LoginViewModel(SavedStateHandle(), repo, coordinator, factory, lanController)

            vm.bindTarget("canonical-id-1", "Home", "http://192.168.1.50:8096")
            vm.onAction(LoginAction.OnStartQuickConnect)
            runCurrent()
            // Re-binding identical target must NOT reset QC state
            vm.bindTarget("canonical-id-1", "Home", "http://192.168.1.50:8096")
            advanceTimeBy(100L)
            advanceUntilIdle()
            assertEquals(OnboardingFailureCode.SERVER_IDENTITY_MISMATCH, vm.state.value.failureCode)

            // Legacy srv-* placeholder canonicalizes to authResult.serverId
            val authCanonical = mockk<AuthenticationResult>(relaxed = true) {
                every { serverId } returns "canonical-real-id"
                every { accessToken } returns "tok-real"
                every { user } returns mockk<UserDto>(relaxed = true) {
                    every { id } returns userId
                    every { name } returns "Alice"
                }
            }
            coEvery { qcClient.authenticate(any(), "sec-1", any()) } returns authCanonical
            coEvery {
                coordinator.activateServer(
                    serverId = "canonical-real-id",
                    addressUrl = "http://192.168.1.50:8096",
                    reason = SessionChangeReason.QUICK_CONNECT,
                    user = any(),
                    serverName = "Home",
                    addresses = any(),
                    legacyServerIds = listOf("srv-legacy"),
                    handoffDeadlineElapsedMs = any(),
                    isDedicatedToken = true,
                    overwriteUserPreferences = false,
                    companionBaseline = any(),
                    deviceOverrodeSeerrUrl = any(),
                    companionCredentials = any(),
                    shares = any(),
                )
            } returns ActivationResult.Activated("canonical-real-id", userId, "http://192.168.1.50:8096", 1L, 1L)

            vm.bindTarget("srv-legacy", "Home", "http://192.168.1.50:8096")
            vm.onAction(LoginAction.OnStartQuickConnect)
            advanceTimeBy(100L)
            advanceUntilIdle()
            assertNull(vm.state.value.failureCode)
            coVerify(exactly = 1) {
                coordinator.activateServer(
                    serverId = "canonical-real-id",
                    addressUrl = any(),
                    reason = SessionChangeReason.QUICK_CONNECT,
                    user = any(),
                    serverName = any(),
                    addresses = any(),
                    legacyServerIds = listOf("srv-legacy"),
                    handoffDeadlineElapsedMs = any(),
                    isDedicatedToken = true,
                    overwriteUserPreferences = false,
                    companionBaseline = any(),
                    deviceOverrodeSeerrUrl = any(),
                    companionCredentials = any(),
                    shares = any(),
                )
            }
        }

    @Test
    fun `LAN session delegates QC actions, manual login, keep-alive lease, and degrades when session ends`() =
        runTest(testDispatcher) {
            val lanFlow = MutableStateFlow(
                LanSetupUiState(
                    activeSessionId = "sess-1",
                    quickConnectState = QuickConnectState.Active("123456", "s", 0, 1L),
                ),
            )
            val lanController = mockk<LanSetupController>(relaxed = true) {
                every { uiState } returns lanFlow
                every { isActiveSession("sess-1") } returns true
                every { acquire(LanSetupMode.BROWSER_URL_ENTRY, false) } returns
                    LeaseHandle(UUID.randomUUID(), LanSetupMode.BROWSER_URL_ENTRY)
                coEvery { performManualLogin("sess-1", any(), any(), any(), any(), any()) } returns
                    ActivationResult.Activated("srv-1", UUID.randomUUID(), "http://192.168.1.50:8096", 1L, 1L)
            }
            val qcFactory = QuickConnectController.Factory(mockk(relaxed = true))
            val vm = LoginViewModel(
                SavedStateHandle(),
                mockk(relaxed = true),
                mockk(relaxed = true),
                qcFactory,
                lanController,
            )

            vm.bindTarget("srv-1", "Home", "http://192.168.1.50:8096", lanSetupSessionId = "sess-1")
            runCurrent()
            assertTrue(vm.state.value.quickConnectState is QuickConnectState.Active)

            // Keep-alive lease uses clearCompletedDrain = false
            vm.acquireBrowserKeepAliveLease()
            verify { lanController.acquire(LanSetupMode.BROWSER_URL_ENTRY, false) }

            // ON_STOP does NOT pause LAN session
            vm.onPauseOrStop()
            assertTrue(vm.state.value.quickConnectState is QuickConnectState.Active)

            vm.onAction(LoginAction.OnCancelQuickConnect)
            verify { lanController.cancelQuickConnect("sess-1") }
            vm.onAction(LoginAction.OnRetryQuickConnect)
            verify { lanController.retryQuickConnect("sess-1") }
            vm.onAction(LoginAction.OnLoginClick("alice", "pw"))
            advanceUntilIdle()
            coVerify { lanController.performManualLogin("sess-1", "srv-1", "Home", "http://192.168.1.50:8096", "alice", "pw") }

            // Reactive degradation when activeSessionId clears
            lanFlow.value = LanSetupUiState(activeSessionId = null)
            runCurrent()
            assertEquals(QuickConnectState.Idle, vm.state.value.quickConnectState)
        }
}
