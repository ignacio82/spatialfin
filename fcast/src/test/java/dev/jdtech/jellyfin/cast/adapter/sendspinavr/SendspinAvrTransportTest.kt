package dev.jdtech.jellyfin.cast.adapter.sendspinavr

import dev.jdtech.jellyfin.cast.splitav.AudioCodecTokens
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SendspinAvrTransportTest {

    private lateinit var server: MockWebServer
    private val json = SendspinAvrJson.codec

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun helloReq() = HelloRequestPayload(
        clientId = "client-test-1",
        clientName = "Test XR",
        formFactor = "xr_full_space",
        appVersion = "2.7.74",
        minProtocolVersion = 1,
        maxProtocolVersion = 1,
    )

    private fun helloOkJsonString(): String {
        val codecs = (AudioCodecTokens.ALL_PASSTHROUGH_TOKENS + AudioCodecTokens.UNIVERSAL_SOFTWARE)
            .joinToString(",") { "\"$it\"" }
        return """
            {
              "protocol_version": 1,
              "server_id": "avr-550e8400e29b41d4a716446655440000",
              "server_name": "Living Room Theater",
              "software_version": "0.1.0",
              "layout": "7.1.4",
              "bus_sample_rate_hz": 48000,
              "sendspin_buffer_us": 600000,
              "max_safe_volume": 0.9,
              "heartbeat_ttl_ms": 6000,
              "pause_idle_release_ms": 600000,
              "video_timing_cadence_hz": 2,
              "supported_audio_codecs": [$codecs],
              "capabilities": { "truehd_atmos_vbap": true, "dts_height_upmix": true }
            }
        """.trimIndent()
    }

    @Test
    fun `clock sync sliding window evicts aged samples and bounds uncertainty`() {
        val sync = SendspinAvrClockSync(maxSamples = 16, windowUs = 30_000_000L, driftPpmBound = 50.0)
        // Old low-RTT outlier at t=0 with theta=+5_000_000
        sync.recordSample(t1ClientUs = 0L, t2AvrRxUs = 5_000_100L, t3AvrTxUs = 5_000_100L, t4ClientRxUs = 200L)
        // Fresh samples after 35s with theta=+5_001_000 and varying RTT (1000..4000 us)
        var snap = sync.snapshot(0L)
        for (i in 1..16) {
            val t1 = 35_000_000L + i * 1_000_000L
            val rtt = if (i == 5) 1_000L else 4_000L
            val t4 = t1 + rtt
            val midClient = t1 + rtt / 2L
            val tServer = midClient + 5_001_000L
            snap = sync.recordSample(t1ClientUs = t1, t2AvrRxUs = tServer, t3AvrTxUs = tServer, t4ClientRxUs = t4)
        }
        assertEquals(16, snap.sampleCount)
        assertEquals(1_000L, snap.minRttUs)
        assertEquals(5_001_000L, snap.thetaUs)
        assertEquals((4_000L - 1_000L) / 2L + (11 * 50L), snap.uncertaintyUs)
    }

    @Test
    fun `connect authenticates runs hello and 8 pings then startSession signalReady and one-way notifications`() = runBlocking {
        val receivedFrames = CopyOnWriteArrayList<String>()
        var serverWs: WebSocket? = null

        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                            serverWs = webSocket
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            receivedFrames.add(text)
                            val obj = json.parseToJsonElement(text).jsonObject
                            val type = obj["type"]!!.jsonPrimitive.content
                            val id = obj["id"]?.jsonPrimitive?.content ?: return
                            val sessionId = obj["session_id"]?.jsonPrimitive?.content
                            when (type) {
                                "hello" -> webSocket.send(
                                    """{"v":1,"type":"rpc.ok","id":"$id","payload":${helloOkJsonString()}}""",
                                )
                                "clock.ping" -> {
                                    val t1 = obj["payload"]!!.jsonObject["t1_client_us"]!!.jsonPrimitive.long
                                    val tAvr = t1 + 1_000_000L
                                    webSocket.send(
                                        """{"v":1,"type":"rpc.ok","id":"$id","payload":{"t1_client_us":$t1,"t2_avr_rx_us":${tAvr + 50},"t3_avr_tx_us":${tAvr + 80}}}""",
                                    )
                                }
                                "session.start" -> webSocket.send(
                                    """{"v":1,"type":"rpc.ok","id":"$id","session_id":"$sessionId","payload":{"accepted":true,"barrier_seq":1}}""",
                                )
                                "playback.ready" -> webSocket.send(
                                    """{"v":1,"type":"rpc.ok","id":"$id","session_id":"$sessionId","payload":{"barrier_seq":1,"actual_pts_us":0,"t_play_avr_us":9000000,"lead_breakdown_us":{"decode_prime_us":12000,"dsp_group_delay_us":18500,"max_air_flight_us":9000,"sendspin_buffer_us":600000,"max_uncompensated_hw_us":0,"launch_margin_us":40000},"codec_active":"truehd","renderer_mode":"truehd_vbap","active_decode_chain":"truehd-atmos -> truehd+vbap(7.1.4)"}}""",
                                )
                            }
                        }
                    },
                )
                .build(),
        )

        var fakeNowUs = 10_000_000L
        val transport = SendspinAvrTransport(
            okHttpClient = OkHttpClient(),
            clockUs = {
                fakeNowUs += 250L
                fakeNowUs
            },
        )
        try {
            val endpoint = SendspinAvrHttpClient.Endpoint(server.hostName, server.port)
            val helloOk = transport.connect(
                endpoint = endpoint,
                bearerToken = "tok-avr-1",
                hello = helloReq(),
                initialClockSamples = 8,
                pingIntervalMs = 0L,
            )
            assertEquals(1, helloOk.protocolVersion)
            assertEquals(8, transport.clockSync.snapshot(fakeNowUs).sampleCount)

            val upgradeReq = server.takeRequest()
            assertEquals("Bearer tok-avr-1", upgradeReq.headers["Authorization"])
            assertEquals("/ws/v1/control", upgradeReq.target)

            val sessId = "550e8400-e29b-41d4-a716-446655440001"
            val startOk = transport.startSession(
                sessionId = sessId,
                payload = SessionStartPayload(
                    sourceType = "jellyfin",
                    streamUrl = "https://jellyfin.lan:8920/Audio/item1/stream?static=true",
                    streamLayout = "multi_stream_container",
                    mediaSourceId = "src-1",
                    audioStreamIndex = 1,
                    sourceCodec = "truehd",
                    targetPtsUs = 0L,
                    jellyfinToken = "jf-tok",
                ),
            )
            assertTrue(startOk.accepted)
            assertEquals(1L, startOk.barrierSeq)

            val readyOk = transport.signalReady(sessId, barrierSeq = 1L, actualPtsUs = 0L)
            assertEquals(9_000_000L, readyOk.tPlayAvrUs)
            assertEquals("truehd_vbap", readyOk.rendererMode)

            assertTrue(transport.sendHeartbeat(sessId, barrierSeq = 1L, state = "paused"))
            assertTrue(
                transport.sendVideoTiming(
                    sessionId = sessId,
                    barrierSeq = 1L,
                    mediaPtsUs = 40_000L,
                    visibleAvrUs = 10_040_000L,
                    clockUncertaintyUs = 250L,
                ),
            )

            // Wait until both one-way frames arrive at server listener
            withTimeout(2_000L) {
                while (receivedFrames.size < 13) kotlinx.coroutines.delay(10L)
            }

            // Ensure no WS payload ever contains session_id, and one-way frames omit id.
            val oneWayFrames = receivedFrames.takeLast(2).map { json.parseToJsonElement(it).jsonObject }
            oneWayFrames.forEach { frame ->
                assertFalse("One-way frame must omit id", "id" in frame)
                assertEquals(sessId, frame["session_id"]?.jsonPrimitive?.content)
                assertFalse("session_id forbidden in WS payload", "session_id" in frame["payload"]!!.jsonObject)
            }
            val startFrame = json.parseToJsonElement(receivedFrames[9]).jsonObject
            assertEquals(sessId, startFrame["session_id"]?.jsonPrimitive?.content)
            assertFalse("session_id forbidden in session.start payload", "session_id" in startFrame["payload"]!!.jsonObject)
            assertTrue(serverWs != null)
        } finally {
            transport.disconnect()
        }
    }

    @Test
    fun `push events rpc_error oversize close and preflight validation behave per Spec 0`() = runBlocking {
        var serverWs: WebSocket? = null
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                            serverWs = webSocket
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val obj = json.parseToJsonElement(text).jsonObject
                            val type = obj["type"]!!.jsonPrimitive.content
                            val id = obj["id"]?.jsonPrimitive?.content ?: return
                            when (type) {
                                "hello" -> webSocket.send(
                                    """{"v":1,"type":"rpc.ok","id":"$id","payload":${helloOkJsonString()}}""",
                                )
                                "session.start" -> webSocket.send(
                                    """{"v":1,"type":"rpc.error","id":"$id","payload":{"code":"session_busy","message":"Room busy","retryable":true,"details":{"owner":"other"}}}""",
                                )
                            }
                        }
                    },
                )
                .build(),
        )

        val transport = SendspinAvrTransport(OkHttpClient(), clockUs = { 1_000L })
        try {
            val endpoint = SendspinAvrHttpClient.Endpoint(server.hostName, server.port)
            transport.connect(endpoint, "tok-1", helloReq(), initialClockSamples = 0, pingIntervalMs = 0L)

            // Preflight validation rejects unsafe source/URL offline
            try {
                transport.startSession(
                    "sess-1",
                    SessionStartPayload(
                        sourceType = "smb",
                        streamUrl = "https://jellyfin.lan/Audio/1/stream",
                        streamLayout = "multi_stream_container",
                        mediaSourceId = "src",
                        audioStreamIndex = 1,
                        sourceCodec = "flac",
                        targetPtsUs = 0L,
                        jellyfinToken = "jf",
                    ),
                )
                fail("Expected source_ineligible exception")
            } catch (e: SendspinAvrRpcException) {
                assertEquals("source_ineligible", e.code)
            }

            // Server rpc.error propagates code + retryable
            try {
                transport.startSession(
                    "sess-1",
                    SessionStartPayload(
                        sourceType = "jellyfin",
                        streamUrl = "https://jellyfin.lan/Audio/1/stream",
                        streamLayout = "multi_stream_container",
                        mediaSourceId = "src",
                        audioStreamIndex = 1,
                        sourceCodec = "flac",
                        targetPtsUs = 0L,
                        jellyfinToken = "jf",
                    ),
                )
                fail("Expected session_busy RPC error")
            } catch (e: SendspinAvrRpcException) {
                assertEquals("session_busy", e.code)
                assertTrue(e.retryable)
            }

            // Connected RPC timeout raises TimeoutCancellationException
            try {
                transport.signalReady("sess-1", barrierSeq = 1L, actualPtsUs = 0L, timeoutMs = 80L)
                fail("Expected TimeoutCancellationException on silent signalReady")
            } catch (_: TimeoutCancellationException) {
            }

            // Subscribe UNDISPATCHED before sending frames so SharedFlow (replay=0) never misses events
            val collector = async(start = CoroutineStart.UNDISPATCHED) {
                transport.pushEvents.take(5).toList()
            }
            while (serverWs == null) delay(5L)
            val ws = checkNotNull(serverWs)
            ws.send("""{"v":1,"type":"session.released","session_id":"sess-1","payload":{"reason":"preempted"}}""")
            ws.send("""{"v":1,"type":"speaker.degraded","session_id":"sess-1","payload":{"role":"SR","client_id":"spk-2","rerouted_to_roles":["FR"],"reason":"timeout"}}""")
            ws.send("""{"v":1,"type":"decoder.fallback","session_id":"sess-1","payload":{"from_chain":"vbap","to_chain":"bed","codec_active":"truehd","renderer_mode":"bed","reason":"fallback"}}""")
            ws.send("""{"v":1,"type":"room.state_changed","session_id":"sess-1","payload":{"master_volume":0.5,"muted":false,"eq_bypass":true,"upmix_mode":"native"}}""")
            val bigText = "x".repeat(TheaterAudioProtocol.MAX_WS_FRAME_BYTES + 100)
            ws.send("""{"v":1,"type":"ma.state_changed","payload":{"ma_zone_state":"$bigText"}}""")

            val events = withTimeout(3_000L) { collector.await() }
            assertTrue(events[0] is SendspinAvrPushEvent.SessionReleased)
            assertTrue(events[1] is SendspinAvrPushEvent.SpeakerDegraded)
            assertTrue(events[2] is SendspinAvrPushEvent.DecoderFallback)
            assertTrue(events[3] is SendspinAvrPushEvent.RoomStateChanged)
            val disconnect = events[4] as SendspinAvrPushEvent.Disconnected
            assertEquals(SendspinAvrTransport.WS_CLOSE_MESSAGE_TOO_BIG, disconnect.code)
        } finally {
            serverWs?.close(1000, "test done")
            transport.disconnect()
        }
    }
}
