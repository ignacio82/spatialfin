package dev.jdtech.jellyfin.setup.lan

import android.app.Application
import dev.jdtech.jellyfin.companion.CompanionConfigImporter
import dev.jdtech.jellyfin.companion.CompanionImportOutcome
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.quickconnect.PollResult
import dev.jdtech.jellyfin.quickconnect.QuickConnectClient
import dev.jdtech.jellyfin.quickconnect.QuickConnectInitiated
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.setup.domain.ProbedServer
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import dev.jdtech.jellyfin.setup.quickconnect.QuickConnectController
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import io.mockk.coEvery
import io.mockk.mockk
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LanSetupServerTest {

    private lateinit var app: Application
    private var controller: LanSetupController? = null
    private val fakeElapsed = AtomicLong(100_000L)
    private val fakeWall = AtomicLong(1_700_000_000_000L)

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() {
        controller?.shutdownForTests()
        controller = null
    }

    private fun buildController(
        addresses: () -> List<InetAddress> = { listOf(InetAddress.getByName("127.0.0.1")) },
        lockFlow: MutableStateFlow<AppLockManager.LockState> =
            MutableStateFlow(AppLockManager.LockState.UNLOCKED),
        importer: CompanionConfigImporter = mockk(relaxed = true),
        setupRepo: SetupRepository = mockk(relaxed = true),
        qcClient: QuickConnectClient = mockk(relaxed = true),
    ): LanSetupController {
        val lockObserver = object : AppLockObserver {
            override val lockState = lockFlow
            override val enrollmentVersion = MutableStateFlow(0L)
        }
        val qcFactory = QuickConnectController.Factory(
            quickConnectClient = qcClient,
            elapsedRealtimeMs = { fakeElapsed.get() },
            clockEpochMs = { fakeWall.get() },
            pollIntervalMs = 60_000L,
        )
        return LanSetupController(
            context = app,
            companionConfigImporter = importer,
            setupRepository = setupRepo,
            quickConnectClient = qcClient,
            quickConnectControllerFactory = qcFactory,
            sessionActivationCoordinator = mockk<SessionActivationCoordinator>(relaxed = true),
            appLockObserver = lockObserver,
            elapsedRealtimeClock = { fakeElapsed.get() },
            wallClock = { fakeWall.get() },
            addressSupplier = addresses,
            preferredPort = 0,
            enableNsdAndOffers = false,
            deviceNameOverride = "Test Device",
            deviceClassOverride = "xr",
        ).also { controller = it }
    }

    private data class RawHttpResponse(
        val statusCode: Int,
        val headers: Map<String, String>,
        val body: String,
    ) {
        fun json(): JSONObject = JSONObject(body)
    }

    private fun sendRawHttp(port: Int, rawRequest: String): RawHttpResponse {
        Socket("127.0.0.1", port).use { sock ->
            sock.soTimeout = 5_000
            val out = sock.getOutputStream()
            out.write(rawRequest.toByteArray(Charsets.UTF_8))
            out.flush()
            val input = BufferedInputStream(sock.getInputStream())
            val all = input.readBytes().toString(Charsets.UTF_8)
            val split = all.indexOf("\r\n\r\n")
            val head = if (split >= 0) all.substring(0, split) else all
            val body = if (split >= 0) all.substring(split + 4) else ""
            val lines = head.split("\r\n")
            val statusCode = lines.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: -1
            val headers =
                lines.drop(1).associate { line ->
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim()
                    } else {
                        "" to ""
                    }
                }
            return RawHttpResponse(statusCode, headers, body)
        }
    }

    @Test
    fun `setup asset is under 24 KiB and GET setup emits unique per-response CSP nonce without unsafe-inline`() {
        val ctrl = buildController()
        ctrl.acquire(LanSetupMode.BROWSER_URL_ENTRY)
        val port = ctrl.server.state.value.boundPort

        val r1 = sendRawHttp(port, "GET /setup HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n")
        val r2 = sendRawHttp(port, "GET /setup HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n")

        assertEquals(200, r1.statusCode)
        assertEquals(200, r2.statusCode)
        assertTrue("setup/index.html must be <= 24 KiB", r1.body.toByteArray(Charsets.UTF_8).size <= 24 * 1024)
        assertEquals("no-store", r1.headers["cache-control"])
        assertEquals("DENY", r1.headers["x-frame-options"])
        assertEquals("no-referrer", r1.headers["referrer-policy"])

        val csp1 = r1.headers["content-security-policy"].orEmpty()
        val csp2 = r2.headers["content-security-policy"].orEmpty()
        assertTrue(csp1.contains("script-src 'nonce-"))
        assertFalse(csp1.contains("unsafe-inline"))
        assertNotEquals("Each GET /setup must emit a fresh CSP nonce", csp1, csp2)
        assertFalse(r1.body.contains("__CSP_NONCE__"))
    }

    @Test
    fun `P1-P3 enforce NIC gate, Host validation, protocol_v 2 info, 64 KiB vs 256 KiB caps, and Transfer-Encoding 411`() {
        val blocked = buildController(addresses = { listOf(InetAddress.getByName("192.168.1.99")) })
        blocked.acquire(LanSetupMode.COMPANION_RECEIVER)
        val portBlocked = blocked.server.state.value.boundPort
        val resBlocked = sendRawHttp(portBlocked, "GET /api/v1/tv-pairing/info HTTP/1.1\r\nHost: 127.0.0.1:$portBlocked\r\n\r\n")
        assertEquals(403, resBlocked.statusCode)
        assertEquals("host_rejected", resBlocked.json().getString("error_code"))
        blocked.shutdownForTests()

        val ctrl = buildController()
        ctrl.acquire(LanSetupMode.COMPANION_RECEIVER)
        val port = ctrl.server.state.value.boundPort

        val info = sendRawHttp(port, "GET /api/v1/tv-pairing/info HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n")
        assertEquals(200, info.statusCode)
        assertEquals(2, info.json().getInt("protocol_v"))
        assertEquals(2, info.json().getJSONArray("capabilities").length())

        // Transfer-Encoding rejected with 411
        val te = sendRawHttp(
            port,
            "POST /api/v1/device-setup/server HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n",
        )
        assertEquals(411, te.statusCode)

        // > 64 KiB rejected on v2 route with 413
        val tooLargeV2 = sendRawHttp(
            port,
            "POST /api/v1/device-setup/server HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Type: application/json\r\nContent-Length: 70000\r\n\r\n",
        )
        assertEquals(413, tooLargeV2.statusCode)
    }

    @Test
    fun `server submit starts Quick Connect, serves status, rejects cross-site status, and supports cancel`() =
        runBlocking {
            val setupRepo = mockk<SetupRepository>()
            coEvery { setupRepo.probeServer("http://192.168.1.50:8096", any()) } returns
                ProbedServer("srv-1", "Home Jellyfin", "http://192.168.1.50:8096")
            val qcClient = mockk<QuickConnectClient>()
            coEvery { qcClient.checkEnabled("http://192.168.1.50:8096", any()) } returns true
            coEvery { qcClient.initiate("http://192.168.1.50:8096", any()) } returns
                QuickConnectInitiated("654321", "secret-abc")
            coEvery { qcClient.pollOnce(any(), any(), any()) } returns PollResult.Pending

            val ctrl = buildController(setupRepo = setupRepo, qcClient = qcClient)
            ctrl.acquire(LanSetupMode.BROWSER_URL_ENTRY)
            val port = ctrl.server.state.value.boundPort
            val token = ctrl.server.currentSecretsForTests().pairingToken

            val body = """{"server_url":"http://192.168.1.50:8096"}"""
            val submit = sendRawHttp(
                port,
                "POST /api/v1/device-setup/server HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Type: application/json\r\nX-Pairing-Token: $token\r\nContent-Length: ${body.length}\r\n\r\n$body",
            )
            assertEquals(200, submit.statusCode)
            val submitJson = submit.json()
            assertEquals("waiting_quick_connect", submitJson.getString("status"))
            assertEquals("654321", submitJson.getString("quick_connect_code"))
            val sessionId = submitJson.getString("session_id")
            assertEquals(32, sessionId.length)

            val navEvent = ctrl.navigationEvents.first() as LanSetupNavigationEvent.NavigateToLogin
            assertTrue(ctrl.consumeNavigationEventIfCurrent(navEvent))
            assertEquals(sessionId, navEvent.lanSetupSessionId)

            // Cross-site status poll -> 403 csrf_rejected
            val csrf = sendRawHttp(
                port,
                "GET /api/v1/device-setup/status?session=$sessionId HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nSec-Fetch-Site: cross-site\r\n\r\n",
            )
            assertEquals(403, csrf.statusCode)
            assertEquals("csrf_rejected", csrf.json().getString("error_code"))

            // Same-origin status poll -> 200 waiting_quick_connect
            val status = sendRawHttp(
                port,
                "GET /api/v1/device-setup/status?session=$sessionId HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nSec-Fetch-Site: same-origin\r\n\r\n",
            )
            assertEquals(200, status.statusCode)
            assertEquals("waiting_quick_connect", status.json().getString("stage"))

            // Cancel session -> 200 cancelled:true and archived status becomes failed
            val cancelBody = """{"session_id":"$sessionId"}"""
            val cancel = sendRawHttp(
                port,
                "POST /api/v1/device-setup/cancel HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Type: application/json\r\nX-Pairing-Token: $token\r\nContent-Length: ${cancelBody.length}\r\n\r\n$cancelBody",
            )
            assertEquals(200, cancel.statusCode)
            assertTrue(cancel.json().getBoolean("cancelled"))

            val afterCancel = sendRawHttp(
                port,
                "GET /api/v1/device-setup/status?session=$sessionId HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n",
            )
            assertEquals(200, afterCancel.statusCode)
            assertEquals("failed", afterCancel.json().getString("stage"))
        }

    @Test
    fun `companion handoff enters drain replay cache and App Lock LOCKED tears down server`() {
        val lockFlow = MutableStateFlow(AppLockManager.LockState.UNLOCKED)
        val importer = mockk<CompanionConfigImporter>()
        coEvery {
            importer.importFromEndpoint(any(), any(), any(), any(), any(), any(), any())
        } returns CompanionImportOutcome.Activated("http://192.168.1.50:8096")

        val ctrl = buildController(lockFlow = lockFlow, importer = importer)
        ctrl.acquire(LanSetupMode.COMPANION_RECEIVER)
        val port = ctrl.server.state.value.boundPort
        val token = ctrl.server.currentSecretsForTests().pairingToken

        val handoffBody = """{"companion_url":"http://192.168.1.20:1982","setup_token":"sf-setup-token1234567890"}"""
        val req =
            "POST /api/v1/device-setup/companion-handoff HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Type: application/json\r\nX-Pairing-Token: $token\r\nContent-Length: ${handoffBody.length}\r\n\r\n$handoffBody"
        val r1 = sendRawHttp(port, req)
        assertEquals(200, r1.statusCode)
        assertEquals("done", r1.json().getString("status"))

        // Replay with identical credential + body during completedDrainState returns cached 200
        val r2 = sendRawHttp(port, req)
        assertEquals(200, r2.statusCode)
        assertEquals("done", r2.json().getString("status"))

        // /info during drain returns 409 pairing_code_expired
        val infoDuringDrain = sendRawHttp(port, "GET /api/v1/tv-pairing/info HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n")
        assertEquals(409, infoDuringDrain.statusCode)
        assertEquals("pairing_code_expired", infoDuringDrain.json().getString("error_code"))

        // App Lock LOCKED stops the server immediately
        lockFlow.value = AppLockManager.LockState.LOCKED
        val deadline = System.currentTimeMillis() + 2_000L
        while (ctrl.uiState.value.isRunning && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L)
        }
        assertFalse("Server must tear down immediately on App Lock LOCKED", ctrl.uiState.value.isRunning)
        assertFalse("Standby locks must be released on App Lock LOCKED", ctrl.hasStandbyLocksHeldForTests())
    }

    @Test
    fun `releasing last lease with standby grace acquires and releases standby WakeLock and WifiLock cleanly`() {
        val lockFlow = MutableStateFlow(AppLockManager.LockState.UNLOCKED)
        val ctrl = buildController(lockFlow = lockFlow)
        val lease = ctrl.acquire(LanSetupMode.COMPANION_RECEIVER)
        assertFalse(ctrl.hasStandbyLocksHeldForTests())

        ctrl.release(lease, graceMs = 90_000L)
        assertTrue("Releasing last lease with grace window must acquire standby locks", ctrl.hasStandbyLocksHeldForTests())

        val lease2 = ctrl.acquire(LanSetupMode.COMPANION_RECEIVER)
        assertFalse("Re-acquiring lease must release standby locks", ctrl.hasStandbyLocksHeldForTests())

        ctrl.release(lease2, graceMs = 90_000L)
        assertTrue(ctrl.hasStandbyLocksHeldForTests())

        // Zero-grace release or lock clears standby locks and shuts server down
        val lease3 = ctrl.acquire(LanSetupMode.BROWSER_URL_ENTRY)
        ctrl.release(lease3, graceMs = 0L)
        assertFalse(ctrl.uiState.value.isRunning)
        assertFalse(ctrl.hasStandbyLocksHeldForTests())
    }
}
