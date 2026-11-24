package dev.jdtech.jellyfin.offline

import android.app.Application
import android.content.Context
import androidx.work.WorkManager
import dagger.Lazy
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.network.newBuilderWithSsrfGuard
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.work.SyncWorker
import dev.spatialfin.unified.applock.AppLockObserver
import io.mockk.mockk
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.cancellation.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServerConnectionFailureTest {
    @Test fun `direct IOException is a connection failure`() {
        assertTrue(isApparentConnectionFailure(IOException("boom")))
    }

    @Test fun `direct SocketTimeoutException is a connection failure`() {
        assertTrue(isApparentConnectionFailure(SocketTimeoutException("timeout")))
    }

    @Test fun `direct UnknownHostException is a connection failure`() {
        assertTrue(isApparentConnectionFailure(UnknownHostException("no dns")))
    }

    @Test fun `direct SSLHandshakeException is a connection failure`() {
        assertTrue(isApparentConnectionFailure(SSLHandshakeException("captive portal")))
    }

    @Test fun `RuntimeException wrapping IOException is a connection failure`() {
        val wrapped = RuntimeException("interceptor failure", SocketTimeoutException("timeout"))
        assertTrue(isApparentConnectionFailure(wrapped))
    }

    @Test fun `deeply nested cause chain still resolves to true`() {
        val deep =
            IllegalStateException(
                "outer",
                RuntimeException(
                    "middle",
                    Exception("inner", IOException("buried")),
                ),
            )
        assertTrue(isApparentConnectionFailure(deep))
    }

    @Test fun `non-network exceptions are not connection failures`() {
        assertFalse(isApparentConnectionFailure(IllegalArgumentException("bad arg")))
        assertFalse(isApparentConnectionFailure(NullPointerException("oops")))
        assertFalse(isApparentConnectionFailure(IllegalStateException("state")))
    }

    @Test fun `HTTP 401 is not a connection failure`() {
        assertFalse(isApparentConnectionFailure(InvalidStatusException(401)))
    }

    @Test fun `HTTP 403 is not a connection failure`() {
        assertFalse(isApparentConnectionFailure(InvalidStatusException(403)))
    }

    @Test fun `HTTP 401 wrapped in a cause chain is still not a connection failure`() {
        val wrapped = RuntimeException("call failed", InvalidStatusException(401))
        assertFalse(isApparentConnectionFailure(wrapped))
    }

    @Test fun `HTTP 500 is still a connection failure`() {
        assertTrue(isApparentConnectionFailure(InvalidStatusException(500)))
    }

    @Test fun `HTTP 404 is still a connection failure`() {
        assertTrue(isApparentConnectionFailure(InvalidStatusException(404)))
    }

    @Test fun `bare ApiClientException is still a connection failure`() {
        assertTrue(isApparentConnectionFailure(ApiClientException()))
    }

    @Test fun `CancellationException is never a connection failure`() {
        assertFalse(isApparentConnectionFailure(CancellationException("cancelled")))
    }

    @Test fun `HTTP 404 and 400 are unrecoverable userdata sync errors`() {
        assertTrue(SyncWorker.isUnrecoverableUserDataSyncError(InvalidStatusException(404)))
        assertTrue(SyncWorker.isUnrecoverableUserDataSyncError(InvalidStatusException(400)))
        assertTrue(SyncWorker.isUnrecoverableUserDataSyncError(InvalidStatusException(410)))
        assertTrue(
            SyncWorker.isUnrecoverableUserDataSyncError(
                RuntimeException("wrapped", InvalidStatusException(404)),
            ),
        )
    }

    @Test fun `HTTP 500, 401, and IOException are retryable userdata sync errors`() {
        assertFalse(SyncWorker.isUnrecoverableUserDataSyncError(InvalidStatusException(500)))
        assertFalse(SyncWorker.isUnrecoverableUserDataSyncError(InvalidStatusException(401)))
        assertFalse(SyncWorker.isUnrecoverableUserDataSyncError(SocketTimeoutException("timeout")))
    }

    @Test fun `probeServer enforces maxRedirects 0, JSON parsing, canonical server ID pinning, and srv- productName`() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = AppPreferences(app.getSharedPreferences("scm_probe_test", Context.MODE_PRIVATE))
        val dao = mockk<ServerDatabaseDao>(relaxed = true)
        val wmGets = AtomicInteger(0)
        val lazyWm = Lazy<WorkManager> {
            wmGets.incrementAndGet()
            mockk(relaxed = true)
        }
        val testHttp =
            OkHttpClient()
                .newBuilderWithSsrfGuard(
                    policy = UrlTargetPolicy.PUBLIC_OR_LAN,
                    allowLoopbackForTests = true,
                )
                .build()

        // Simulate :xrplayer process so init does not spin up background loops or dereference WorkManager
        ShadowApplication.setProcessName("${app.packageName}:xrplayer")
        try {
            val coordinator = mockk<SessionActivationCoordinator>(relaxed = true)
            val monitor = ServerConnectionMonitor(
                context = app,
                appPreferences = prefs,
                database = dao,
                workManager = lazyWm,
                okHttpClient = testHttp,
                coordinator = coordinator,
                appLockObserver = AppLockObserver.NoOp,
            )
            assertEquals("WorkManager must not be dereferenced in :xrplayer", 0, wmGets.get())

            val server = MockWebServer()
            server.start()
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')

                // 1. 302 captive portal redirect rejected (maxRedirects = 0)
                server.enqueue(MockResponse(code = 302, headers = okhttp3.Headers.headersOf("Location", "$baseUrl/portal")))
                assertFalse(monitor.probeServer("abc123def456", baseUrl))

                // 2. HTML response rejected
                server.enqueue(MockResponse(code = 200, body = "<html><body>Captive Portal</body></html>"))
                assertFalse(monitor.probeServer("abc123def456", baseUrl))

                // 3. Canonical server ID mismatch rejected
                server.enqueue(MockResponse(code = 200, body = """{"Id":"999999999999","ProductName":"Jellyfin Server"}"""))
                assertFalse(monitor.probeServer("abc123def456", baseUrl))

                // 4. Canonical server ID match accepted (hyphen-insensitive)
                server.enqueue(MockResponse(code = 200, body = """{"Id":"abc1-23de-f456","ProductName":"Jellyfin Server"}"""))
                assertTrue(monitor.probeServer("abc123def456", baseUrl))

                // 5. Legacy srv-* server rejects non-Jellyfin productName
                server.enqueue(MockResponse(code = 200, body = """{"Id":"anything","ProductName":"Router Admin"}"""))
                assertFalse(monitor.probeServer("srv-1234", baseUrl))

                // 6. Legacy srv-* server accepts Jellyfin Server productName
                server.enqueue(MockResponse(code = 200, body = """{"Id":"anything","ProductName":"Jellyfin Server"}"""))
                assertTrue(monitor.probeServer("srv-1234", baseUrl))

                // 7. Marking server accessible in :xrplayer never dereferences WorkManager
                monitor.markServerInaccessible(expectedEpoch = 0L)
                monitor.markServerAccessible(expectedEpoch = 0L)
                assertEquals(0, wmGets.get())
            } finally {
                server.close()
            }
        } finally {
            ShadowApplication.setProcessName(app.packageName)
        }
    }
}
