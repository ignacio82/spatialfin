package dev.jdtech.jellyfin.quickconnect

import android.provider.Settings
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.onboarding.OnboardingFailureException
import io.mockk.every
import io.mockk.spyk
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QuickConnectClientTest {

    @Test
    fun `checkEnabled, initiate, pollOnce, and approve map Jellyfin Quick Connect wire responses`() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ANDROID_ID, "test-android-id-qc")
        val jellyfinApi = JellyfinApi(app, allowLoopbackForTests = true)
        val client = QuickConnectClient(jellyfinApi)

        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("/").toString().trimEnd('/')

            // checkEnabled -> true, then 401 -> false
            server.enqueue(MockResponse(code = 200, body = "true", headers = okhttp3.Headers.headersOf("Content-Type", "application/json")))
            assertTrue(client.checkEnabled(baseUrl))

            server.enqueue(MockResponse(code = 401, body = """{"error":"disabled"}"""))
            assertFalse(client.checkEnabled(baseUrl))

            // initiate -> returns code + secret
            server.enqueue(
                MockResponse(
                    code = 200,
                    headers = okhttp3.Headers.headersOf("Content-Type", "application/json"),
                    body = """{"Secret":"sec-123","Code":"123456","DeviceId":"dev1","DeviceName":"XR","AppName":"SpatialFin","AppVersion":"2.7","DateAdded":"2026-11-24T00:00:00Z","Authenticated":false}""",
                ),
            )
            val init = client.initiate(baseUrl)
            assertEquals("123456", init.code)
            assertEquals("sec-123", init.secret)

            // initiate 401 -> QC_DISABLED_ON_SERVER
            server.enqueue(MockResponse(code = 401, body = ""))
            val disabledErr = runCatching { client.initiate(baseUrl) }.exceptionOrNull() as OnboardingFailureException
            assertEquals(OnboardingFailureCode.QC_DISABLED_ON_SERVER, disabledErr.code)

            // pollOnce -> Pending, Authenticated, 404 NotFound, 401 DisabledOnServer
            server.enqueue(
                MockResponse(
                    code = 200,
                    headers = okhttp3.Headers.headersOf("Content-Type", "application/json"),
                    body = """{"Secret":"sec-123","Code":"123456","DeviceId":"dev1","DeviceName":"XR","AppName":"SpatialFin","AppVersion":"2.7","DateAdded":"2026-11-24T00:00:00Z","Authenticated":false}""",
                ),
            )
            assertEquals(PollResult.Pending, client.pollOnce(baseUrl, "sec-123"))

            server.enqueue(
                MockResponse(
                    code = 200,
                    headers = okhttp3.Headers.headersOf("Content-Type", "application/json"),
                    body = """{"Secret":"sec-123","Code":"123456","DeviceId":"dev1","DeviceName":"XR","AppName":"SpatialFin","AppVersion":"2.7","DateAdded":"2026-11-24T00:00:00Z","Authenticated":true}""",
                ),
            )
            assertEquals(PollResult.Authenticated, client.pollOnce(baseUrl, "sec-123"))

            server.enqueue(MockResponse(code = 404, body = ""))
            assertEquals(PollResult.NotFound, client.pollOnce(baseUrl, "sec-123"))

            server.enqueue(MockResponse(code = 401, body = ""))
            assertEquals(PollResult.DisabledOnServer, client.pollOnce(baseUrl, "sec-123"))

            // approve format validation (no network call)
            assertEquals(
                ApproveResult.Failed(OnboardingFailureCode.PAIRING_CODE_FORMAT),
                client.approve(baseUrl, "tok", "12ab56"),
            )

            // approve happy path with whitespace stripped
            server.enqueue(MockResponse(code = 200, body = "true", headers = okhttp3.Headers.headersOf("Content-Type", "application/json")))
            assertEquals(ApproveResult.Approved, client.approve(baseUrl, "tok", "123 456"))

            // approve 404 -> PAIRING_CODE_EXPIRED
            server.enqueue(MockResponse(code = 404, body = ""))
            assertEquals(
                ApproveResult.Failed(OnboardingFailureCode.PAIRING_CODE_EXPIRED),
                client.approve(baseUrl, "tok", "654321"),
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `all QuickConnectClient methods execute off caller main dispatcher and trim trailing slash`() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ANDROID_ID, "test-android-id-qc-io")
        val observedThreads = CopyOnWriteArrayList<String>()
        val observedBaseUrls = CopyOnWriteArrayList<String>()
        val spyApi = spyk(JellyfinApi(app, allowLoopbackForTests = true))
        every { spyApi.createApi(any(), any(), any(), any()) } answers {
            observedThreads.add(Thread.currentThread().name)
            observedBaseUrls.add(firstArg())
            callOriginal()
        }
        val client = QuickConnectClient(spyApi)
        val fakeMainDispatcher = Executors.newSingleThreadExecutor { r ->
            Thread(r, "fake-main-thread")
        }.asCoroutineDispatcher()

        val server = MockWebServer()
        server.start()
        try {
            val baseUrlWithSlash = server.url("/").toString()
            val expectedNormalized = baseUrlWithSlash.trimEnd('/')
            val jsonHeaders = okhttp3.Headers.headersOf("Content-Type", "application/json")
            val qcBody =
                """{"Secret":"sec-io","Code":"654321","DeviceId":"dev1","DeviceName":"Phone","AppName":"SpatialFin","AppVersion":"2.7","DateAdded":"2026-11-24T00:00:00Z","Authenticated":true}"""
            val authBody =
                """{"AccessToken":"tok-abc","ServerId":"fd6ec3df44f74b4cb2d7cd721991de1b"}"""

            server.enqueue(MockResponse(code = 200, body = "true", headers = jsonHeaders))
            server.enqueue(MockResponse(code = 200, body = qcBody, headers = jsonHeaders))
            server.enqueue(MockResponse(code = 200, body = qcBody, headers = jsonHeaders))
            server.enqueue(MockResponse(code = 200, body = authBody, headers = jsonHeaders))
            server.enqueue(MockResponse(code = 200, body = "true", headers = jsonHeaders))

            withContext(fakeMainDispatcher) {
                assertTrue(Thread.currentThread().name.startsWith("fake-main-thread"))
                assertTrue(client.checkEnabled(baseUrlWithSlash))
                assertEquals("654321", client.initiate(baseUrlWithSlash).code)
                assertEquals(PollResult.Authenticated, client.pollOnce(baseUrlWithSlash, "sec-io"))
                assertEquals("tok-abc", client.authenticate(baseUrlWithSlash, "sec-io").accessToken)
                assertEquals(ApproveResult.Approved, client.approve(baseUrlWithSlash, "tok-abc", "654321"))
            }

            assertEquals(5, observedThreads.size)
            assertTrue(
                "Expected all QuickConnect calls to run off fake-main-thread, got: $observedThreads",
                observedThreads.none { it.startsWith("fake-main-thread") },
            )
            assertTrue(
                "Expected all baseUrls to be normalized without trailing slash, got: $observedBaseUrls",
                observedBaseUrls.all { it == expectedNormalized },
            )
        } finally {
            server.close()
            fakeMainDispatcher.close()
        }
    }
}
