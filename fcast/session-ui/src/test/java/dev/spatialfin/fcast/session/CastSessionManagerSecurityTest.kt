package dev.spatialfin.fcast.session

import android.content.Context
import dagger.Lazy
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrHttpClient
import dev.jdtech.jellyfin.core.util.XrPlayerSessionClient
import dev.jdtech.jellyfin.fcast.sender.FCastCastingController
import dev.jdtech.jellyfin.fcast.sender.FCastReceiver
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.network.SafeUrlBlockedException
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.network.withSsrfGuard
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.session.ActiveSessionObserver
import dev.jdtech.jellyfin.session.ActiveSessionSnapshot
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.TheaterAvrTokenStore
import dev.spatialfin.fcast.session.calibration.CalibrationOrchestrator
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class CastSessionManagerSecurityTest {

    private lateinit var server: MockWebServer
    private lateinit var server2: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server2 = MockWebServer()
        server2.start()
    }

    @After
    fun tearDown() {
        server.close()
        server2.close()
    }

    private fun createManager(
        baseUrl: String,
        accessToken: String = "jf-secret-token",
        allowLoopbackForTests: Boolean = true,
        lockStateFlow: MutableStateFlow<AppLockManager.LockState> =
            MutableStateFlow(AppLockManager.LockState.UNLOCKED),
        sessionStateFlow: MutableStateFlow<ActiveSessionSnapshot> = MutableStateFlow(
            ActiveSessionSnapshot(
                isLoading = false,
                isAuthenticated = true,
                serverId = "srv-1",
                serverAddress = baseUrl,
                userId = UUID.randomUUID(),
                sessionEpoch = 1L,
            ),
        ),
        controller: FCastCastingController = mockk(relaxed = true),
        splitAvController: SplitAvController = mockk(relaxed = true),
    ): CastSessionManager {
        val repo = mockk<JellyfinRepository>(relaxed = true) {
            every { getBaseUrl() } returns baseUrl
            every { getAccessToken() } returns accessToken
        }
        val client = OkHttpClient.Builder()
            .withSsrfGuard(UrlTargetPolicy.PUBLIC_OR_LAN, allowLoopbackForTests = allowLoopbackForTests)
            .build()
        val appLockObserver = object : AppLockObserver {
            override val lockState = lockStateFlow
            override val enrollmentVersion = MutableStateFlow(0L)
        }
        val activeSessionObserver = object : ActiveSessionObserver {
            override val activeSessionState = sessionStateFlow
            override suspend fun ensureInitialized() = sessionStateFlow.value
        }
        return CastSessionManager(
            appContext = mockk<Context>(relaxed = true),
            controller = controller,
            repository = repo,
            appPreferences = mockk<AppPreferences>(relaxed = true),
            rememberedReceiversStore = mockk<RememberedReceiversStore>(relaxed = true),
            splitAvController = splitAvController,
            calibrationOrchestrator = mockk<CalibrationOrchestrator>(relaxed = true),
            okHttpClient = client,
            appLockObserver = appLockObserver,
            activeSessionObserver = activeSessionObserver,
            theaterAvrTokenStore = mockk<TheaterAvrTokenStore>(relaxed = true),
            sendspinAvrHttpClient = SendspinAvrHttpClient(client),
        )
    }

    @Test
    fun fetchTextSamplePinsOriginSendsEmbyHeaderBlocksCrossOriginRedirectsAndCapsBytes() = runBlocking {
        val baseUrl = server.url("/jellyfin").toString()
        val manager = createManager(baseUrl = baseUrl)

        server.enqueue(MockResponse(code = 200, body = "ABCDEFGHIJKLMNOP"))
        val sample = manager.fetchTextSample(
            server.url("/jellyfin/Videos/1/Subtitles/0/Stream.ass").toString(),
            maxBytes = 8,
        )
        assertEquals("ABCDEFGH", sample)
        val req = server.takeRequest()
        assertEquals("jf-secret-token", req.headers["X-Emby-Token"])
        assertFalse(req.target.contains("api_key", ignoreCase = true))

        try {
            manager.fetchTextSample(server.url("/jellyfin2/Subtitles/0/Stream.ass").toString(), maxBytes = 8)
            fail("Expected origin/basePath mismatch to throw SafeUrlBlockedException")
        } catch (_: SafeUrlBlockedException) {
        }

        try {
            manager.fetchTextSample(server2.url("/jellyfin/Subtitles/0/Stream.ass").toString(), maxBytes = 8)
            fail("Expected different origin to throw SafeUrlBlockedException")
        } catch (_: SafeUrlBlockedException) {
        }

        server.enqueue(
            MockResponse(
                code = 302,
                headers = okhttp3.Headers.headersOf("Location", server2.url("/steal.ass").toString()),
            ),
        )
        server2.enqueue(MockResponse(code = 200, body = "stolen"))
        try {
            manager.fetchTextSample(
                server.url("/jellyfin/Videos/1/Subtitles/0/Stream.ass").toString(),
                maxBytes = 8,
            )
            fail("Expected cross-origin redirect to fail")
        } catch (_: SafeUrlBlockedException) {
        }
        assertEquals(0, server2.requestCount)
    }

    @Test
    fun castSpatialItemRejectsWhenLockedOrUnauthenticatedAndStopsOnLockTransition() = runBlocking {
        val baseUrl = server.url("/").toString()
        val lockFlow = MutableStateFlow(AppLockManager.LockState.UNLOCKED)
        val sessionFlow = MutableStateFlow(
            ActiveSessionSnapshot(
                isLoading = false,
                isAuthenticated = true,
                serverId = "srv-1",
                serverAddress = baseUrl,
                userId = UUID.randomUUID(),
                sessionEpoch = 1L,
            ),
        )
        val controller = mockk<FCastCastingController>(relaxed = true)
        val splitAv = mockk<SplitAvController>(relaxed = true)
        val manager = createManager(
            baseUrl = baseUrl,
            lockStateFlow = lockFlow,
            sessionStateFlow = sessionFlow,
            controller = controller,
            splitAvController = splitAv,
        )

        val movie = mockk<SpatialFinMovie>(relaxed = true)
        manager.pickReceiver(FCastReceiver(host = "192.168.1.55", port = 46899, name = "TV"))

        // Locking tears down active cast & Split-A/V
        lockFlow.value = AppLockManager.LockState.LOCKED
        delay(150)
        assertFalse(manager.castSpatialItem(movie))
        coVerify(atLeast = 1) { controller.stopCast() }
        coVerify(atLeast = 1) { splitAv.stop() }

        // Unauthenticated session also refuses casting
        lockFlow.value = AppLockManager.LockState.UNLOCKED
        sessionFlow.value = sessionFlow.value.copy(isAuthenticated = false, userId = null)
        delay(100)
        assertFalse(manager.castSpatialItem(movie))
    }
}
