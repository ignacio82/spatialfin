package dev.jdtech.jellyfin.cast.adapter.sendspinavr

import java.io.File
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SendspinAvrHttpClientTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var redirectTarget: MockWebServer
    private lateinit var client: SendspinAvrHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        redirectTarget = MockWebServer()
        redirectTarget.start()
        client = SendspinAvrHttpClient(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.close()
        redirectTarget.close()
    }

    private fun endpoint(): SendspinAvrHttpClient.Endpoint =
        SendspinAvrHttpClient.Endpoint(host = server.hostName, port = server.port, useTls = false)

    @Test
    fun `health and pairClaim round-trip JSON without Bearer auth`() = runBlocking {
        server.enqueue(MockResponse(code = 200, body = """{"status":"ok","uptime_s":42}"""))
        val health = client.health(endpoint())
        assertTrue(health is SendspinAvrHttpClient.HttpResult.Success)
        assertEquals(42L, (health as SendspinAvrHttpClient.HttpResult.Success).value.uptimeS)
        val healthReq = server.takeRequest()
        assertEquals("/health", healthReq.target)
        assertNull(healthReq.headers["Authorization"])

        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"avr_token":"savr_tok_123","server_id":"avr-550e8400e29b41d4a716446655440000","client_id":"client-1","protocol_version":1}""",
            ),
        )
        val claim = client.pairClaim(
            endpoint(),
            PairClaimRequest(pin = "482913", clientId = "client-1", clientName = "Galaxy XR"),
        )
        assertTrue(claim is SendspinAvrHttpClient.HttpResult.Success)
        assertEquals("savr_tok_123", (claim as SendspinAvrHttpClient.HttpResult.Success).value.avrToken)
        assertEquals("/api/v1/pair/claim", server.takeRequest().target)
    }

    @Test
    fun `getRoomProfile captures ETag and putRoomProfile surfaces 409 version_conflict`() = runBlocking {
        val profileJson = """
            {
              "schema_version": 1,
              "room_profile_version": 4,
              "room_name": "Theater",
              "layout": "5.1",
              "lcr_screen_plane": null,
              "speakers": []
            }
        """.trimIndent()
        server.enqueue(MockResponse(code = 200, headers = headersOf("ETag", "\"4\""), body = profileJson))

        val fetched = client.getRoomProfile(endpoint(), "tok-valid")
        assertTrue(fetched is SendspinAvrHttpClient.HttpResult.Success)
        val success = fetched as SendspinAvrHttpClient.HttpResult.Success
        assertEquals("\"4\"", success.etag)
        assertEquals("Bearer tok-valid", server.takeRequest().headers["Authorization"])

        server.enqueue(
            MockResponse(
                code = 409,
                body = """{"error_code":"version_conflict","message":"Stale profile version","retryable":true}""",
            ),
        )
        val conflict = client.putRoomProfile(endpoint(), "tok-valid", "\"3\"", success.value)
        assertTrue(conflict is SendspinAvrHttpClient.HttpResult.Error)
        val err = conflict as SendspinAvrHttpClient.HttpResult.Error
        assertEquals(409, err.httpStatus)
        assertEquals("version_conflict", err.errorCode)
        assertTrue(err.retryable)
        val putReq = server.takeRequest()
        assertEquals("\"3\"", putReq.headers["If-Match"])
    }

    @Test
    fun `uploadSweep streams wav calibration txt and metadata_json multipart fields`() = runBlocking {
        val wavFile: File = tempFolder.newFile("sweep.wav").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"calibration_id":"cal-1","ready_to_apply":true,"clock_ratio_ppm":-2.5,"dsp_group_delay_us":18500,"channels":[]}""",
            ),
        )

        val result = client.uploadSweep(
            endpoint = endpoint(),
            bearerToken = "tok-valid",
            sweepId = "sweep-9",
            recordingWavFile = wavFile,
            umikCalText = "Sens Factor = -1.2 dB\n",
            metadata = CalibrationSweepUploadMetadata(
                micAngleDeg = 90,
                micSerial = "7123456",
                capturePlatform = "android_otg",
            ),
        )
        assertTrue(result is SendspinAvrHttpClient.HttpResult.Success)
        val req = server.takeRequest()
        assertEquals("/api/v1/calibration/sweeps/sweep-9/upload", req.target)
        val bodyText = req.body!!.utf8()
        assertTrue(bodyText.contains("name=\"recording_wav\""))
        assertTrue(bodyText.contains("name=\"umik_cal_txt\""))
        assertTrue(bodyText.contains("name=\"metadata_json\""))
        assertTrue(bodyText.contains("\"mic_serial\":\"7123456\""))
    }

    @Test
    fun `redirects are disabled so Bearer token never leaks cross-origin`() = runBlocking {
        server.enqueue(
            MockResponse(
                code = 302,
                headers = headersOf("Location", redirectTarget.url("/steal").toString()),
                body = "",
            ),
        )

        val result = client.getInfo(endpoint(), "tok-secret")
        assertTrue(result is SendspinAvrHttpClient.HttpResult.Error)
        val err = result as SendspinAvrHttpClient.HttpResult.Error
        assertEquals(302, err.httpStatus)
        assertEquals("url_rejected", err.errorCode)
        assertEquals(0, redirectTarget.requestCount)
    }

    @Test
    fun `rate_limited parses Retry-After and unsafe chime gain or CRLF token fails preflight`() = runBlocking {
        server.enqueue(
            MockResponse(
                code = 429,
                headers = headersOf("Retry-After", "17"),
                body = """{"error_code":"rate_limited","message":"Slow down","retryable":true}""",
            ),
        )
        val rateLimited = client.getStatus(endpoint(), "tok-valid")
        assertTrue(rateLimited is SendspinAvrHttpClient.HttpResult.Error)
        assertEquals(17L, (rateLimited as SendspinAvrHttpClient.HttpResult.Error).retryAfterSeconds)

        val unsafeGain = client.playChime(
            endpoint(),
            "tok-valid",
            CalibrationChimeRequest(role = "FL", signal = "chime", gainDbfs = -6.0, durationMs = 400),
        )
        assertTrue(unsafeGain is SendspinAvrHttpClient.HttpResult.Error)
        assertEquals("invalid_request", (unsafeGain as SendspinAvrHttpClient.HttpResult.Error).errorCode)

        val crlfToken = client.getInfo(endpoint(), "tok\r\nevil")
        assertTrue(crlfToken is SendspinAvrHttpClient.HttpResult.Error)
        assertEquals("invalid_request", (crlfToken as SendspinAvrHttpClient.HttpResult.Error).errorCode)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `endpoint parses host-colon-port scheme ipv6 and pairClaim rejects invalid host without throwing`() = runBlocking {
        val ipv4WithPort = SendspinAvrHttpClient.Endpoint.parse("192.168.1.90:1986", fallbackPort = 1984)
        assertEquals("192.168.1.90", ipv4WithPort.host)
        assertEquals(1986, ipv4WithPort.port)
        assertEquals("http://192.168.1.90:1986", ipv4WithPort.baseHttpUrl())
        assertEquals("ws://192.168.1.90:1986/ws/v1/control", ipv4WithPort.baseWsUrl())

        val withScheme = SendspinAvrHttpClient.Endpoint.parse("https://avr.local:8443/health", fallbackPort = 1984)
        assertEquals("avr.local", withScheme.host)
        assertEquals(8443, withScheme.port)
        assertTrue(withScheme.useTls)
        assertEquals("https://avr.local:8443", withScheme.baseHttpUrl())

        val ipv6 = SendspinAvrHttpClient.Endpoint.parse("[fe80::1]:1986", fallbackPort = 1984)
        assertEquals("fe80::1", ipv6.host)
        assertEquals(1986, ipv6.port)
        assertEquals("http://[fe80::1]:1986", ipv6.baseHttpUrl())

        val bad = client.pairClaim(
            SendspinAvrHttpClient.Endpoint("bad host with spaces", 1986),
            PairClaimRequest(pin = "123456", clientId = "c1", clientName = "XR"),
        )
        assertTrue(bad is SendspinAvrHttpClient.HttpResult.Error)
        assertEquals("invalid_request", (bad as SendspinAvrHttpClient.HttpResult.Error).errorCode)
    }
}
