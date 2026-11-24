package dev.jdtech.jellyfin.setup.quickconnect

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.onboarding.OnboardingFailureException
import dev.jdtech.jellyfin.quickconnect.ApproveResult
import dev.jdtech.jellyfin.quickconnect.PollResult
import dev.jdtech.jellyfin.quickconnect.QuickConnectClient
import dev.jdtech.jellyfin.quickconnect.QuickConnectInitiated
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionMode
import dev.jdtech.jellyfin.session.SessionState
import dev.jdtech.jellyfin.setup.onboarding.OnboardingFailureMessages
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
class QuickConnectControllerTest {

    private lateinit var app: Application
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        app = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `OnboardingFailureMessages maps all 26 OnboardingFailureCode entries to non-blank strings`() {
        assertEquals(26, OnboardingFailureCode.entries.size)
        for (code in OnboardingFailureCode.entries) {
            val msg = OnboardingFailureMessages.message(app, code)
            assertTrue("Missing message for $code", msg.isNotBlank())
        }
    }

    @Test
    fun `QuickConnectController rotates up to 2 times then expires and scrubs SavedStateHandle`() =
        runTest(testDispatcher) {
            val elapsed = AtomicLong(10_000L)
            val wall = AtomicLong(1_700_000_000_000L)
            val client = mockk<QuickConnectClient>()
            var initCount = 0
            coEvery { client.checkEnabled(any(), any()) } returns true
            coEvery { client.initiate(any(), any()) } answers {
                initCount++
                QuickConnectInitiated(code = "10000$initCount", secret = "sec-$initCount")
            }
            coEvery { client.pollOnce(any(), any(), any()) } returns PollResult.NotFound

            val handle = SavedStateHandle()
            val factory =
                QuickConnectController.Factory(
                    quickConnectClient = client,
                    elapsedRealtimeMs = { elapsed.get() },
                    clockEpochMs = { wall.get() },
                    pollIntervalMs = 100L,
                )
            val controller = factory.create(handle, backgroundScope, "srv-1|http://192.168.1.50:8096")
            controller.start("http://192.168.1.50:8096")
            runCurrent()
            val active0 = controller.state.value as QuickConnectState.Active
            assertEquals("100001", active0.code)
            assertEquals(0, active0.rotation)
            assertTrue(handle.keys().isNotEmpty())

            advanceTimeBy(100L)
            runCurrent()
            val active1 = controller.state.value as QuickConnectState.Active
            assertEquals("100002", active1.code)
            assertEquals(1, active1.rotation)
            assertTrue(active1.rotatedNotice)

            advanceTimeBy(100L)
            runCurrent()
            val active2 = controller.state.value as QuickConnectState.Active
            assertEquals("100003", active2.code)
            assertEquals(2, active2.rotation)

            advanceTimeBy(100L)
            runCurrent()
            assertEquals(QuickConnectState.Expired, controller.state.value)
            assertTrue("SavedStateHandle must be scrubbed on terminal state", handle.keys().isEmpty())
        }

    @Test
    fun `pre-start cancel permanently blocks startWithInitiated and pauseOnStop preserves in-flight exchange`() =
        runTest(testDispatcher) {
            val client = mockk<QuickConnectClient>()
            val factory = QuickConnectController.Factory(
                quickConnectClient = client,
                pollIntervalMs = 100L,
            )

            // Sticky atomic isCancelled
            val cancelled = factory.create(null, backgroundScope, "k1")
            cancelled.cancel()
            cancelled.startWithInitiated("http://192.168.1.50:8096", QuickConnectInitiated("123456", "sec-1"))
            runCurrent()
            assertEquals(QuickConnectState.Idle, cancelled.state.value)

            // pauseOnStop preserves in-flight exchangeJob
            val exchangeGate = CompletableDeferred<AuthenticationResult>()
            val authResult = mockk<AuthenticationResult>(relaxed = true)
            coEvery { client.pollOnce(any(), "sec-live", any()) } returns PollResult.Authenticated
            coEvery { client.authenticate(any(), "sec-live", any()) } coAnswers { exchangeGate.await() }

            val live = factory.create(null, backgroundScope, "k2")
            live.startWithInitiated("http://192.168.1.50:8096", QuickConnectInitiated("654321", "sec-live"))
            advanceTimeBy(150L)
            runCurrent()
            live.pauseOnStop()
            exchangeGate.complete(authResult)
            runCurrent()
            assertTrue(live.state.value is QuickConnectState.Authenticated)
        }

    @Test
    fun `QuickConnectController maps 401 to DisabledOnServer and restores hashed SavedStateHandle`() =
        runTest(testDispatcher) {
            val client = mockk<QuickConnectClient>()
            coEvery { client.checkEnabled(any(), any()) } returns true
            coEvery { client.initiate(any(), any()) } throws
                OnboardingFailureException(OnboardingFailureCode.QC_DISABLED_ON_SERVER)

            val factory = QuickConnectController.Factory(client, pollIntervalMs = 100L)
            val c1 = factory.create(null, backgroundScope, "srv-1|http://host")
            c1.start("http://host")
            runCurrent()
            assertEquals(QuickConnectState.DisabledOnServer, c1.state.value)

            // SavedStateHandle restore across process death
            val handle = SavedStateHandle()
            val wall = AtomicLong(1_700_000_000_000L)
            val client2 = mockk<QuickConnectClient>()
            coEvery { client2.checkEnabled(any(), any()) } returns true
            coEvery { client2.initiate(any(), any()) } returns QuickConnectInitiated("777888", "sec-saved")
            coEvery { client2.pollOnce(any(), any(), any()) } returns PollResult.Pending
            val factory2 = QuickConnectController.Factory(
                quickConnectClient = client2,
                clockEpochMs = { wall.get() },
                pollIntervalMs = 5_000L,
            )
            val first = factory2.create(handle, backgroundScope, "srv-1|http://host")
            first.start("http://host")
            runCurrent()
            first.pauseOnStop()

            val restored = factory2.create(handle, backgroundScope, "srv-1|http://host")
            restored.start("http://host")
            runCurrent()
            val restoredState = restored.state.value as QuickConnectState.Active
            assertEquals("777888", restoredState.code)
            assertEquals("sec-saved", restoredState.secret)
            coVerify(exactly = 1) { client2.initiate(any(), any()) }
            restored.cancel()
        }

    @Test
    fun `QuickConnectApprovalViewModel guards double-tap while Submitting and resets cleanly`() =
        runTest(testDispatcher) {
            val userId = UUID.randomUUID()
            val sessionFlow = MutableStateFlow(
                SessionState(
                    isLoading = false,
                    sessionMode = SessionMode.SERVER_AUTHENTICATED,
                    activeServerId = "srv-1",
                    activeServerAddress = "http://192.168.1.50:8096",
                    activeUserId = userId,
                ),
            )
            val coordinator = mockk<SessionActivationCoordinator>(relaxed = true) {
                every { sessionState } returns sessionFlow
            }
            val db = mockk<ServerDatabaseDao>(relaxed = true) {
                every { getUser(userId) } returns User(userId, "Alice", "srv-1", "tok-1")
            }
            val gate = CompletableDeferred<ApproveResult>()
            val qcClient = mockk<QuickConnectClient>()
            coEvery { qcClient.approve("http://192.168.1.50:8096", "tok-1", "123456", any()) } coAnswers {
                gate.await()
            }

            val vm = QuickConnectApprovalViewModel(coordinator, db, qcClient)
            vm.approve("123456")
            runCurrent()
            assertEquals(QuickConnectApprovalState.Submitting, vm.state.value)

            // Second tap while Submitting is ignored
            vm.approve("123456")
            runCurrent()
            gate.complete(ApproveResult.Approved)
            val deadline = System.currentTimeMillis() + 3_000L
            while (vm.state.value == QuickConnectApprovalState.Submitting && System.currentTimeMillis() < deadline) {
                runCurrent()
                Thread.sleep(10L)
            }
            assertEquals(QuickConnectApprovalState.Approved, vm.state.value)
            coVerify(exactly = 1) { qcClient.approve(any(), any(), any(), any()) }

            vm.reset()
            assertEquals(QuickConnectApprovalState.Idle, vm.state.value)
        }
}
