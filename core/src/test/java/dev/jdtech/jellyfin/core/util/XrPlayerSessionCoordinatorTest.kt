package dev.jdtech.jellyfin.core.util

import android.app.Application
import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Process
import dagger.Lazy
import dev.jdtech.jellyfin.security.ContentKeyManager
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionState
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.XrProcessPreferenceBridge
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.SystemModalGraceState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class XrPlayerSessionCoordinatorTest {

    private lateinit var app: Application
    private lateinit var prefs: AppPreferences
    private lateinit var lockStateFlow: MutableStateFlow<AppLockManager.LockState>
    private lateinit var appLockManager: AppLockManager
    private lateinit var sessionEpoch: AtomicLong
    private lateinit var sessionActivationCoordinator: SessionActivationCoordinator
    private lateinit var systemModalGraceState: SystemModalGraceState

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        val sp = app.getSharedPreferences("xr_session_coord_main", Context.MODE_PRIVATE)
        val xrSp = app.getSharedPreferences("xr_session_coord_xr", Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        xrSp.edit().clear().commit()
        prefs = AppPreferences(sp, xrSp, isMainProcess = true, isDebuggable = true)

        lockStateFlow = MutableStateFlow(AppLockManager.LockState.UNLOCKED)
        appLockManager = mockk(relaxed = true) {
            every { lockState } returns lockStateFlow
            every { onAppBackgrounded() } answers {
                lockStateFlow.value = AppLockManager.LockState.LOCKED
            }
        }
        sessionEpoch = AtomicLong(1L)
        sessionActivationCoordinator = mockk(relaxed = true) {
            every { this@mockk.sessionEpoch } returns this@XrPlayerSessionCoordinatorTest.sessionEpoch
            coEvery { ensureInitialized() } returns SessionState(
                isLoading = false,
                sessionEpoch = 1L,
                activeServerAddress = "http://192.168.1.50:8096",
            )
        }
        systemModalGraceState = SystemModalGraceState()
    }

    private fun buildCoordinator(): XrPlayerSessionCoordinator =
        XrPlayerSessionCoordinator(
            context = app,
            appLockManager = Lazy { appLockManager },
            sessionActivationCoordinator = Lazy { sessionActivationCoordinator },
            contentKeyManager = Lazy { mockk<ContentKeyManager>(relaxed = true) },
            systemModalGraceState = systemModalGraceState,
            appPreferences = prefs,
            xrProcessPreferenceBridge = Lazy { mockk<XrProcessPreferenceBridge>(relaxed = true) },
        )

    private fun fakeClientBinder(): IBinder = mockk<Binder>(relaxed = true) {
        every { linkToDeath(any(), any()) } returns Unit
        every { unlinkToDeath(any(), any()) } returns true
        every { transact(any(), any(), any(), any()) } returns true
    }

    @Test
    fun `non-finishing unregisterSession evaluates AppLock synchronously and returns true when locked`() {
        val coordinator = buildCoordinator()
        val token = fakeClientBinder()
        val pid = Process.myPid() + 100

        val reg = coordinator.registerSession(token, pid)
        assertTrue(reg is RegisterSessionResult.Registered)

        val shouldFinish = coordinator.unregisterSession(token, isFinishing = false)
        assertTrue(shouldFinish)
        verify(exactly = 1) { appLockManager.onAppBackgrounded() }
        assertEquals(AppLockManager.LockState.LOCKED, lockStateFlow.value)
    }

    @Test
    fun `non-finishing unregisterSession while permissionPromptPending is true defers lock and returns false`() {
        val coordinator = buildCoordinator()
        val token = fakeClientBinder()
        val pid = Process.myPid() + 101

        assertTrue(coordinator.registerSession(token, pid) is RegisterSessionResult.Registered)
        coordinator.setPermissionPromptPending(token, true)

        val shouldFinish = coordinator.unregisterSession(token, isFinishing = false)
        assertFalse(shouldFinish)
        verify(exactly = 0) { appLockManager.onAppBackgrounded() }
        assertEquals(AppLockManager.LockState.UNLOCKED, lockStateFlow.value)
    }

    @Test
    fun `forceCloseAllXrClients kills only surviving revoked token PID and spares unregistered or replaced PIDs`(): Unit =
        runBlocking {
            val coordinator = buildCoordinator()
            coordinator.forceCloseFallbackDelayMs = 40L

            // Case 1: unresponsive revoked token gets killed after delay
            run {
                val killedLatch = CountDownLatch(1)
                var killedPids: Set<Int> = emptySet()
                coordinator.killPidsAction = { pids ->
                    killedPids = pids
                    killedLatch.countDown()
                }
                val staleToken = fakeClientBinder()
                val stalePid = Process.myPid() + 201
                assertTrue(coordinator.registerSession(staleToken, stalePid) is RegisterSessionResult.Registered)

                coordinator.forceCloseAllXrClients(expectedEpoch = 1L)
                assertTrue(killedLatch.await(2, TimeUnit.SECONDS))
                assertEquals(setOf(stalePid), killedPids)
                coordinator.unregisterSession(staleToken, isFinishing = true)
            }

            // Case 2: revoked token that unregisters before fallback delay is NOT killed
            run {
                var killedCalled = false
                coordinator.killPidsAction = { killedCalled = true }
                val cleanToken = fakeClientBinder()
                val cleanPid = Process.myPid() + 202
                assertTrue(coordinator.registerSession(cleanToken, cleanPid) is RegisterSessionResult.Registered)

                coordinator.forceCloseAllXrClients(expectedEpoch = 1L)
                coordinator.unregisterSession(cleanToken, isFinishing = true)
                Thread.sleep(100L)
                assertFalse(killedCalled)
            }

            // Case 3: new token registering with the same PID before fallback delay spares that PID
            run {
                var killedCalled = false
                coordinator.killPidsAction = { killedCalled = true }
                val oldToken = fakeClientBinder()
                val newToken = fakeClientBinder()
                val sharedPid = Process.myPid() + 203
                assertTrue(coordinator.registerSession(oldToken, sharedPid) is RegisterSessionResult.Registered)

                coordinator.forceCloseAllXrClients(expectedEpoch = 1L)
                sessionEpoch.set(2L)
                assertTrue(coordinator.registerSession(newToken, sharedPid) is RegisterSessionResult.Registered)
                Thread.sleep(100L)
                assertFalse("Newer registration sharing PID must not be assassinated", killedCalled)
                coordinator.unregisterSession(newToken, isFinishing = true)
            }
        }
}
