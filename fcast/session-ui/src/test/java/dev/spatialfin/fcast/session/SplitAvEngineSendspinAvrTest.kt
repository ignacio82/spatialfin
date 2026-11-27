package dev.spatialfin.fcast.session

import dev.jdtech.jellyfin.cast.CastCapability
import dev.jdtech.jellyfin.cast.CastProtocol
import dev.jdtech.jellyfin.cast.CastReceiver
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrJson
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrTransport
import dev.jdtech.jellyfin.cast.splitav.AudioCodecTokens
import dev.jdtech.jellyfin.player.core.splitav.SplitAvFirstFrameReady
import dev.jdtech.jellyfin.player.core.splitav.SplitAvMediaItemTransition
import dev.jdtech.jellyfin.player.core.splitav.SplitAvTrackSwitchRequest
import dev.jdtech.jellyfin.player.core.splitav.SplitAvVideoMaster
import dev.jdtech.jellyfin.player.core.splitav.SplitAvVideoTiming
import dev.jdtech.jellyfin.player.core.splitav.TheaterClientEvent
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SplitAvEngineSendspinAvrTest {

    private class FakeVideoMaster : SplitAvVideoMaster {
        private val _isPlaying = MutableStateFlow(false)
        override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

        private val _userSeeks = MutableSharedFlow<Long>(extraBufferCapacity = 16)
        override val userSeeks: SharedFlow<Long> = _userSeeks.asSharedFlow()

        private val _firstFrameReady = MutableSharedFlow<SplitAvFirstFrameReady>(extraBufferCapacity = 16)
        override val firstFrameReady: SharedFlow<SplitAvFirstFrameReady> = _firstFrameReady.asSharedFlow()

        private val _isBufferingStalled = MutableStateFlow(false)
        override val isBufferingStalled: StateFlow<Boolean> = _isBufferingStalled.asStateFlow()

        private val _trackSwitches = MutableSharedFlow<SplitAvTrackSwitchRequest>(extraBufferCapacity = 16)
        override val trackSwitches: SharedFlow<SplitAvTrackSwitchRequest> = _trackSwitches.asSharedFlow()

        private val _mediaItemTransitions = MutableSharedFlow<SplitAvMediaItemTransition>(extraBufferCapacity = 16)
        override val mediaItemTransitions: SharedFlow<SplitAvMediaItemTransition> = _mediaItemTransitions.asSharedFlow()

        @Volatile var positionMs: Long = 5_000L
        @Volatile var mutedCalls = mutableListOf<Boolean>()
        val speedCalls = CopyOnWriteArrayList<Float>()
        val holdRequests = CopyOnWriteArrayList<Pair<Long, Long>>()
        val startRequests = CopyOnWriteArrayList<Pair<Long, Long>>()
        @Volatile var foldBackCount: Int = 0
        @Volatile var pauseCount: Int = 0
        @Volatile var stopCount: Int = 0
        @Volatile var timing: SplitAvVideoTiming? = null

        override fun currentPositionMs(): Long = positionMs
        override fun renderedVideoTiming(): SplitAvVideoTiming? = timing
        override fun setPlaybackSpeed(factor: Float) {
            speedCalls += factor
        }
        override fun seekTo(positionMs: Long) {
            this.positionMs = positionMs
        }
        override fun pauseFromMaster() {
            pauseCount++
            _isPlaying.value = false
        }
        override fun resumeFromMaster() {
            _isPlaying.value = true
        }
        override fun stopFromMaster() {
            stopCount++
            _isPlaying.value = false
        }
        override fun setAudioMuted(muted: Boolean) {
            mutedCalls += muted
        }
        override fun foldBackToLocal() {
            foldBackCount++
        }
        override fun prepareAndHoldAt(barrierSeq: Long, targetPositionMs: Long) {
            holdRequests += barrierSeq to targetPositionMs
            positionMs = targetPositionMs
            _isPlaying.value = false
        }
        override fun startAtBoottimeUs(barrierSeq: Long, tPlayClientUs: Long) {
            startRequests += barrierSeq to tPlayClientUs
            _isPlaying.value = true
        }

        fun emitFirstFrameReady(barrierSeq: Long, ptsUs: Long = positionMs * 1_000L) {
            _firstFrameReady.tryEmit(SplitAvFirstFrameReady(barrierSeq, ptsUs))
        }
    }

    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private val openedServerSockets = CopyOnWriteArrayList<WebSocket>()
    private val json = SendspinAvrJson.codec

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        httpClient = OkHttpClient()
    }

    @After
    fun tearDown() {
        openedServerSockets.forEach { socket ->
            runCatching { socket.close(1000, "teardown") }
        }
        openedServerSockets.clear()
        server.close()
        if (::httpClient.isInitialized) {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        }
    }

    private fun testConfig(): SendspinAvrSessionConfig = SendspinAvrSessionConfig(
        receiver = CastReceiver(
            id = "SendspinAvr:avr-550e8400e29b41d4a716446655440000",
            name = "Theater Room",
            host = server.hostName,
            port = server.port,
            protocol = CastProtocol.SendspinAvr,
            capabilities = setOf(
                CastCapability.Audio,
                CastCapability.Volume,
                CastCapability.SplitAv,
                CastCapability.TheaterAudioV1,
            ),
        ),
        bearerToken = "tok-avr-1",
        clientId = "client-1",
        clientName = "Galaxy XR",
        formFactor = "xr_full_space",
        appVersion = "2.7.74",
        sessionId = "sess-test-1",
        streamUrl = "https://jellyfin.lan:8920/Audio/item1/stream?static=true",
        streamLayout = "multi_stream_container",
        mediaSourceId = "src-1",
        audioStreamIndex = 1,
        sourceCodec = "truehd",
        jellyfinToken = "jf-tok-1",
        initialTargetPtsUs = 5_000_000L,
    )

    private fun helloOkPayload(): String {
        val codecs = (AudioCodecTokens.ALL_PASSTHROUGH_TOKENS + AudioCodecTokens.UNIVERSAL_SOFTWARE)
            .joinToString(",") { "\"$it\"" }
        return """{"protocol_version":1,"server_id":"avr-550e8400e29b41d4a716446655440000","server_name":"Theater","software_version":"0.1.0","layout":"7.1.4","bus_sample_rate_hz":48000,"sendspin_buffer_us":600000,"max_safe_volume":0.9,"heartbeat_ttl_ms":6000,"pause_idle_release_ms":600000,"video_timing_cadence_hz":2,"supported_audio_codecs":[$codecs],"capabilities":{"truehd_atmos_vbap":true,"dts_height_upmix":true}}"""
    }

    private suspend fun waitUntil(timeoutMs: Long = 3_000L, predicate: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!predicate()) delay(10L)
        }
    }

    @Test
    fun `cold start heartbeats before master binds T_play launches at 1x emits beacons and USER_EXIT stops`() = runBlocking {
        val frames = CopyOnWriteArrayList<String>()
        var serverWs: WebSocket? = null
        val nowUs = 10_000_000L

        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            openedServerSockets += webSocket
                            serverWs = webSocket
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, reason)
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            frames += text
                            val obj = json.parseToJsonElement(text).jsonObject
                            val type = obj["type"]!!.jsonPrimitive.content
                            val id = obj["id"]?.jsonPrimitive?.content ?: return
                            val sessionId = obj["session_id"]?.jsonPrimitive?.content
                            when (type) {
                                "hello" -> webSocket.send("""{"v":1,"type":"rpc.ok","id":"$id","payload":${helloOkPayload()}}""")
                                "clock.ping" -> {
                                    val t1 = obj["payload"]!!.jsonObject["t1_client_us"]!!.jsonPrimitive.long
                                    webSocket.send("""{"v":1,"type":"rpc.ok","id":"$id","payload":{"t1_client_us":$t1,"t2_avr_rx_us":$t1,"t3_avr_tx_us":$t1}}""")
                                }
                                "session.start" -> webSocket.send("""{"v":1,"type":"rpc.ok","id":"$id","session_id":"$sessionId","payload":{"accepted":true,"barrier_seq":1}}""")
                                "playback.ready" -> {
                                    val seq = obj["payload"]!!.jsonObject["barrier_seq"]!!.jsonPrimitive.long
                                    val pts = obj["payload"]!!.jsonObject["actual_pts_us"]!!.jsonPrimitive.long
                                    webSocket.send(
                                        """{"v":1,"type":"rpc.ok","id":"$id","session_id":"$sessionId","payload":{"barrier_seq":$seq,"actual_pts_us":$pts,"t_play_avr_us":${nowUs + 5_000_000L},"lead_breakdown_us":{"decode_prime_us":10000,"dsp_group_delay_us":18500,"max_air_flight_us":9000,"sendspin_buffer_us":600000,"max_uncompensated_hw_us":0,"launch_margin_us":40000},"codec_active":"truehd","renderer_mode":"truehd_vbap","active_decode_chain":"truehd+vbap"}}""",
                                    )
                                }
                                "session.stop" -> webSocket.send("""{"v":1,"type":"rpc.ok","id":"$id","session_id":"$sessionId","payload":{"stopped":true}}""")
                            }
                        }
                    },
                )
                .build(),
        )

        val masterFlow = MutableStateFlow<SplitAvVideoMaster?>(null)
        val engine = SendspinAvrSplitAvEngine(
            transportFactory = { SendspinAvrTransport(httpClient, clockUs = { nowUs }) },
            clockUs = { nowUs },
            masterFlow = masterFlow,
            heartbeatIntervalMs = 30L,
            videoTimingIntervalMs = 30L,
            barrierTimeoutMs = 2_000L,
            initialClockSamples = 2,
            pingIntervalMs = 0L,
        )
        try {
            engine.start(testConfig())
            // Before any master binds, state-independent starting heartbeats must already fire.
            waitUntil {
                frames.any {
                    val o = json.parseToJsonElement(it).jsonObject
                    o["type"]?.jsonPrimitive?.content == "session.heartbeat" &&
                        o["payload"]!!.jsonObject["state"]?.jsonPrimitive?.content == "starting"
                }
            }

            val master = FakeVideoMaster().apply {
                timing = SplitAvVideoTiming(5_000L, nowUs / 1_000L, nowUs / 1_000L, 5_001_500L, nowUs + 500L)
            }
            masterFlow.value = master
            waitUntil { master.holdRequests.isNotEmpty() }
            assertTrue(master.speedCalls.isNotEmpty() && master.speedCalls.all { it == 1.0f })
            assertEquals(true, master.mutedCalls.last())

            master.emitFirstFrameReady(barrierSeq = 1L, ptsUs = 5_001_500L)
            waitUntil { engine.state.value == SplitAvController.State.Playing }
            assertEquals(1, master.startRequests.size)
            assertTrue(master.speedCalls.all { it == 1.0f })

            // Verify 2 Hz 4-field beacons arrive with session_id strictly on envelope.
            waitUntil {
                frames.any {
                    val o = json.parseToJsonElement(it).jsonObject
                    o["type"]?.jsonPrimitive?.content == "playback.video_timing" &&
                        "session_id" !in o["payload"]!!.jsonObject
                }
            }

            // USER_EXIT tears down immediately (0 ms grace) via session.stop("activity_exit")
            engine.onMasterDisconnected(userExit = true)
            waitUntil {
                engine.state.value == SplitAvController.State.Idle &&
                    frames.any {
                        val o = json.parseToJsonElement(it).jsonObject
                        o["type"]?.jsonPrimitive?.content == "session.stop" &&
                            o["payload"]!!.jsonObject["reason"]?.jsonPrimitive?.content == "activity_exit"
                    }
            }
        } finally {
            serverWs?.close(1000, "done")
            engine.shutdown()
        }
    }

    @Test
    fun `missed T_play lead retries 3 times then folds back locally`() = runBlocking {
        var serverWs: WebSocket? = null
        val readyBarrierSeqs = CopyOnWriteArrayList<Long>()
        val nowUs = 10_000_000L

        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            openedServerSockets += webSocket
                            serverWs = webSocket
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, reason)
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val obj = json.parseToJsonElement(text).jsonObject
                            val type = obj["type"]!!.jsonPrimitive.content
                            val id = obj["id"]?.jsonPrimitive?.content ?: return
                            val sessionId = obj["session_id"]?.jsonPrimitive?.content
                            when (type) {
                                "hello" -> webSocket.send("""{"v":1,"type":"rpc.ok","id":"$id","payload":${helloOkPayload()}}""")
                                "session.start" -> webSocket.send("""{"v":1,"type":"rpc.ok","id":"$id","session_id":"$sessionId","payload":{"accepted":true,"barrier_seq":1}}""")
                                "playback.ready" -> {
                                    val seq = obj["payload"]!!.jsonObject["barrier_seq"]!!.jsonPrimitive.long
                                    readyBarrierSeqs += seq
                                    // Always return past timestamp (< 1_000 us lead) so client retries.
                                    webSocket.send(
                                        """{"v":1,"type":"rpc.ok","id":"$id","session_id":"$sessionId","payload":{"barrier_seq":$seq,"actual_pts_us":5000000,"t_play_avr_us":${nowUs - 5_000L},"lead_breakdown_us":{"decode_prime_us":10000,"dsp_group_delay_us":18500,"max_air_flight_us":9000,"sendspin_buffer_us":600000,"max_uncompensated_hw_us":0,"launch_margin_us":40000},"codec_active":"truehd","renderer_mode":"truehd_vbap","active_decode_chain":"truehd+vbap"}}""",
                                    )
                                }
                                "session.stop" -> webSocket.send("""{"v":1,"type":"rpc.ok","id":"$id","session_id":"$sessionId","payload":{"stopped":true}}""")
                            }
                        }
                    },
                )
                .build(),
        )

        val master = FakeVideoMaster()
        val masterFlow = MutableStateFlow<SplitAvVideoMaster?>(master)
        val engine = SendspinAvrSplitAvEngine(
            transportFactory = { SendspinAvrTransport(httpClient, clockUs = { nowUs }) },
            clockUs = { nowUs },
            masterFlow = masterFlow,
            heartbeatIntervalMs = 5_000L,
            initialClockSamples = 0,
            pingIntervalMs = 0L,
        )
        try {
            engine.start(testConfig())
            // Feed firstFrameReady for initial barrier (1) and 3 retries (2, 3, 4).
            for (expectedSeq in 1L..4L) {
                waitUntil { master.holdRequests.any { it.first == expectedSeq } }
                master.emitFirstFrameReady(expectedSeq)
            }
            waitUntil { engine.state.value == SplitAvController.State.Idle }
            assertEquals(listOf(1L, 2L, 3L, 4L), readyBarrierSeqs.toList())
            assertEquals(1, master.foldBackCount)
            assertEquals(false, master.mutedCalls.last())
        } finally {
            serverWs?.close(1000, "done")
            engine.shutdown()
        }
    }

    @Test
    fun `session_busy takeover 4th resync_required foldback and handoff grace pause behave per Spec 0`() = runBlocking {
        var serverWs: WebSocket? = null
        val stopReasons = CopyOnWriteArrayList<String>()
        val pauseCountRemote = CopyOnWriteArrayList<Long>()
        val nowUs = 20_000_000L
        var startAttempts = 0

        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            openedServerSockets += webSocket
                            serverWs = webSocket
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, reason)
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val obj = json.parseToJsonElement(text).jsonObject
                            val type = obj["type"]!!.jsonPrimitive.content
                            val id = obj["id"]?.jsonPrimitive?.content ?: return
                            val sessionId = obj["session_id"]?.jsonPrimitive?.content
                            when (type) {
                                "hello" -> webSocket.send("""{"v":1,"type":"rpc.ok","id":"$id","payload":${helloOkPayload()}}""")
                                "session.start" -> {
                                    startAttempts++
                                    if (startAttempts == 1) {
                                        webSocket.send("""{"v":1,"type":"rpc.error","id":"$id","payload":{"code":"session_busy","message":"Busy","retryable":true}}""")
                                    } else {
                                        webSocket.send("""{"v":1,"type":"rpc.ok","id":"$id","session_id":"$sessionId","payload":{"accepted":true,"barrier_seq":1}}""")
                                    }
                                }
                                "playback.ready" -> {
                                    val seq = obj["payload"]!!.jsonObject["barrier_seq"]!!.jsonPrimitive.long
                                    webSocket.send(
                                        """{"v":1,"type":"rpc.ok","id":"$id","session_id":"$sessionId","payload":{"barrier_seq":$seq,"actual_pts_us":5000000,"t_play_avr_us":${nowUs + 5_000_000L},"lead_breakdown_us":{"decode_prime_us":10000,"dsp_group_delay_us":18500,"max_air_flight_us":9000,"sendspin_buffer_us":600000,"max_uncompensated_hw_us":0,"launch_margin_us":40000},"codec_active":"truehd","renderer_mode":"truehd_vbap","active_decode_chain":"truehd+vbap"}}""",
                                    )
                                }
                                "playback.pause" -> {
                                    pauseCountRemote += obj["payload"]!!.jsonObject["barrier_seq"]!!.jsonPrimitive.long
                                    webSocket.send("""{"v":1,"type":"rpc.ok","id":"$id","session_id":"$sessionId","payload":{"barrier_seq":1,"flushed":true}}""")
                                }
                                "session.stop" -> {
                                    stopReasons += obj["payload"]!!.jsonObject["reason"]!!.jsonPrimitive.content
                                    webSocket.send("""{"v":1,"type":"rpc.ok","id":"$id","session_id":"$sessionId","payload":{"stopped":true}}""")
                                }
                            }
                        }
                    },
                )
                .build(),
        )
        // Enqueue 2nd socket upgrade for ConfirmTakeover restart.
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            openedServerSockets += webSocket
                            serverWs = webSocket
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, reason)
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val obj = json.parseToJsonElement(text).jsonObject
                            val type = obj["type"]!!.jsonPrimitive.content
                            val id = obj["id"]?.jsonPrimitive?.content ?: return
                            val sessionId = obj["session_id"]?.jsonPrimitive?.content
                            when (type) {
                                "hello" -> webSocket.send("""{"v":1,"type":"rpc.ok","id":"$id","payload":${helloOkPayload()}}""")
                                "session.start" -> webSocket.send("""{"v":1,"type":"rpc.ok","id":"$id","session_id":"$sessionId","payload":{"accepted":true,"barrier_seq":1}}""")
                                "playback.ready" -> {
                                    val seq = obj["payload"]!!.jsonObject["barrier_seq"]!!.jsonPrimitive.long
                                    webSocket.send(
                                        """{"v":1,"type":"rpc.ok","id":"$id","session_id":"$sessionId","payload":{"barrier_seq":$seq,"actual_pts_us":5000000,"t_play_avr_us":${nowUs + 5_000_000L},"lead_breakdown_us":{"decode_prime_us":10000,"dsp_group_delay_us":18500,"max_air_flight_us":9000,"sendspin_buffer_us":600000,"max_uncompensated_hw_us":0,"launch_margin_us":40000},"codec_active":"truehd","renderer_mode":"truehd_vbap","active_decode_chain":"truehd+vbap"}}""",
                                    )
                                }
                                "playback.pause" -> {
                                    pauseCountRemote += obj["payload"]!!.jsonObject["barrier_seq"]!!.jsonPrimitive.long
                                    webSocket.send("""{"v":1,"type":"rpc.ok","id":"$id","session_id":"$sessionId","payload":{"barrier_seq":1,"flushed":true}}""")
                                }
                                "session.stop" -> {
                                    stopReasons += obj["payload"]!!.jsonObject["reason"]!!.jsonPrimitive.content
                                    webSocket.send("""{"v":1,"type":"rpc.ok","id":"$id","session_id":"$sessionId","payload":{"stopped":true}}""")
                                }
                            }
                        }
                    },
                )
                .build(),
        )

        val master = FakeVideoMaster()
        val masterFlow = MutableStateFlow<SplitAvVideoMaster?>(master)
        val engine = SendspinAvrSplitAvEngine(
            transportFactory = { SendspinAvrTransport(httpClient, clockUs = { nowUs }) },
            clockUs = { nowUs },
            masterFlow = masterFlow,
            heartbeatIntervalMs = 5_000L,
            videoTimingIntervalMs = 5_000L,
            disconnectGraceMs = 70L,
            initialClockSamples = 0,
            pingIntervalMs = 0L,
        )
        try {
            val started = engine.start(testConfig())
            assertFalse(started)
            waitUntil { engine.theaterSnapshot.value?.banner?.requiresTakeoverConfirm == true }
            assertEquals("session_busy", engine.theaterSnapshot.value?.banner?.code)

            engine.handleTheaterClientEvent(TheaterClientEvent.ConfirmTakeover(true))
            waitUntil { master.holdRequests.any { it.first == 1L } }
            master.emitFirstFrameReady(1L)
            waitUntil { engine.state.value == SplitAvController.State.Playing }

            // 1..3 resync_required triggers re-barrier; 4th in 30s folds back & stops.
            val ws = checkNotNull(serverWs)
            for (seq in 1L..3L) {
                ws.send("""{"v":1,"type":"playback.resync_required","session_id":"sess-test-1","payload":{"barrier_seq":$seq,"drift_us":90000}}""")
                val nextSeq = seq + 1L
                waitUntil { master.holdRequests.any { it.first == nextSeq } }
                master.emitFirstFrameReady(nextSeq)
                waitUntil { engine.state.value == SplitAvController.State.Playing }
            }
            ws.send("""{"v":1,"type":"playback.resync_required","session_id":"sess-test-1","payload":{"barrier_seq":4,"drift_us":90000}}""")
            waitUntil { engine.state.value == SplitAvController.State.Idle }
            assertEquals(1, master.foldBackCount)
            assertTrue("resync_cap" in stopReasons)
        } finally {
            serverWs?.close(1000, "done")
            engine.shutdown()
        }
    }

    @Test
    fun `auth_invalid triggers callback and unexpected detach pauses then stops after grace`() = runBlocking {
        var serverWs: WebSocket? = null
        val authInvalidCalls = CopyOnWriteArrayList<String>()
        val eventsLog = CopyOnWriteArrayList<String>()
        val nowUs = 30_000_000L

        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            openedServerSockets += webSocket
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val obj = json.parseToJsonElement(text).jsonObject
                            val type = obj["type"]!!.jsonPrimitive.content
                            val id = obj["id"]?.jsonPrimitive?.content ?: return
                            if (type == "hello") {
                                webSocket.send("""{"v":1,"type":"rpc.error","id":"$id","payload":{"code":"auth_invalid","message":"Revoked","retryable":false}}""")
                            }
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, reason)
                        }
                    },
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            openedServerSockets += webSocket
                            serverWs = webSocket
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, reason)
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val obj = json.parseToJsonElement(text).jsonObject
                            val type = obj["type"]!!.jsonPrimitive.content
                            val id = obj["id"]?.jsonPrimitive?.content ?: return
                            val sessionId = obj["session_id"]?.jsonPrimitive?.content
                            when (type) {
                                "hello" -> webSocket.send("""{"v":1,"type":"rpc.ok","id":"$id","payload":${helloOkPayload()}}""")
                                "session.start" -> webSocket.send("""{"v":1,"type":"rpc.ok","id":"$id","session_id":"$sessionId","payload":{"accepted":true,"barrier_seq":1}}""")
                                "playback.ready" -> webSocket.send(
                                    """{"v":1,"type":"rpc.ok","id":"$id","session_id":"$sessionId","payload":{"barrier_seq":1,"actual_pts_us":5000000,"t_play_avr_us":${nowUs + 5_000_000L},"lead_breakdown_us":{"decode_prime_us":10000,"dsp_group_delay_us":18500,"max_air_flight_us":9000,"sendspin_buffer_us":600000,"max_uncompensated_hw_us":0,"launch_margin_us":40000},"codec_active":"truehd","renderer_mode":"truehd_vbap","active_decode_chain":"truehd+vbap"}}""",
                                )
                                "playback.pause" -> {
                                    eventsLog += "pause"
                                    webSocket.send("""{"v":1,"type":"rpc.ok","id":"$id","session_id":"$sessionId","payload":{"barrier_seq":2,"flushed":true}}""")
                                }
                                "session.stop" -> {
                                    eventsLog += "stop:${obj["payload"]!!.jsonObject["reason"]!!.jsonPrimitive.content}"
                                    webSocket.send("""{"v":1,"type":"rpc.ok","id":"$id","session_id":"$sessionId","payload":{"stopped":true}}""")
                                }
                            }
                        }
                    },
                )
                .build(),
        )

        val masterFlow = MutableStateFlow<SplitAvVideoMaster?>(null)
        val engine = SendspinAvrSplitAvEngine(
            transportFactory = { SendspinAvrTransport(httpClient, clockUs = { nowUs }) },
            clockUs = { nowUs },
            masterFlow = masterFlow,
            onAuthInvalid = { authInvalidCalls += it },
            heartbeatIntervalMs = 5_000L,
            videoTimingIntervalMs = 5_000L,
            disconnectGraceMs = 70L,
            initialClockSamples = 0,
            pingIntervalMs = 0L,
        )
        try {
            // auth_invalid fires callback + requiresRepair banner
            engine.start(testConfig())
            waitUntil { engine.state.value == SplitAvController.State.Idle && authInvalidCalls.isNotEmpty() }
            assertEquals(listOf("avr-550e8400e29b41d4a716446655440000"), authInvalidCalls.toList())
            assertTrue(engine.theaterSnapshot.value?.banner?.requiresRepair == true)

            // Valid second connection + unexpected binder death pauses then stops after grace
            val master = FakeVideoMaster()
            masterFlow.value = master
            engine.start(testConfig())
            waitUntil { master.holdRequests.isNotEmpty() }
            master.emitFirstFrameReady(1L)
            waitUntil { engine.state.value == SplitAvController.State.Playing }

            masterFlow.value = null
            waitUntil { engine.state.value == SplitAvController.State.Idle }
            assertFalse(eventsLog.isEmpty())
            assertEquals("pause", eventsLog.first())
            assertTrue("stop:master_lost" in eventsLog)
        } finally {
            serverWs?.close(1000, "done")
            engine.shutdown()
        }
    }

    @Test
    fun `initial firstFrameReady timeout folds back to local playback and resumes player`() = runBlocking {
        var serverWs: WebSocket? = null
        val stopReasons = CopyOnWriteArrayList<String>()
        val nowUs = 10_000_000L

        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            openedServerSockets += webSocket
                            serverWs = webSocket
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, reason)
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val obj = json.parseToJsonElement(text).jsonObject
                            val type = obj["type"]!!.jsonPrimitive.content
                            val id = obj["id"]?.jsonPrimitive?.content ?: return
                            val sessionId = obj["session_id"]?.jsonPrimitive?.content
                            when (type) {
                                "hello" -> webSocket.send("""{"v":1,"type":"rpc.ok","id":"$id","payload":${helloOkPayload()}}""")
                                "session.start" -> webSocket.send("""{"v":1,"type":"rpc.ok","id":"$id","session_id":"$sessionId","payload":{"accepted":true,"barrier_seq":1}}""")
                                "session.stop" -> {
                                    stopReasons += obj["payload"]!!.jsonObject["reason"]!!.jsonPrimitive.content
                                    webSocket.send("""{"v":1,"type":"rpc.ok","id":"$id","session_id":"$sessionId","payload":{"stopped":true}}""")
                                }
                            }
                        }
                    },
                )
                .build(),
        )

        val masterFlow = MutableStateFlow<SplitAvVideoMaster?>(null)
        val engine = SendspinAvrSplitAvEngine(
            transportFactory = { SendspinAvrTransport(httpClient, clockUs = { nowUs }) },
            clockUs = { nowUs },
            masterFlow = masterFlow,
            heartbeatIntervalMs = 5_000L,
            videoTimingIntervalMs = 5_000L,
            barrierTimeoutMs = 120L,
            initialClockSamples = 0,
            pingIntervalMs = 0L,
        )
        try {
            val master = FakeVideoMaster()
            masterFlow.value = master
            engine.start(testConfig())
            waitUntil { master.holdRequests.isNotEmpty() }
            // Never emit firstFrameReady — barrier timeout must NOT be swallowed by CancellationException.
            waitUntil { engine.state.value == SplitAvController.State.Idle }
            assertEquals("seek_timeout", engine.theaterSnapshot.value?.banner?.code)
            assertEquals(1, master.foldBackCount)
            assertEquals(true, master.isPlaying.value)
            assertTrue("barrier_timeout" in stopReasons)
        } finally {
            serverWs?.close(1000, "done")
            engine.shutdown()
        }
    }
}
