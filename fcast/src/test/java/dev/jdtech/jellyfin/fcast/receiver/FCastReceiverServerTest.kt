package dev.jdtech.jellyfin.fcast.receiver

import dev.jdtech.jellyfin.fcast.protocol.FCastFrame
import dev.jdtech.jellyfin.fcast.protocol.FCastMessage
import dev.jdtech.jellyfin.fcast.protocol.FCastOpcode
import dev.jdtech.jellyfin.fcast.protocol.PingMessage
import dev.jdtech.jellyfin.fcast.protocol.PlayMessage
import dev.jdtech.jellyfin.fcast.protocol.PlaybackUpdateMessage
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FCastReceiverServerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() { scope.cancel() }

    @Test fun `server accepts a sender, receives Play, routes through router`() = runBlocking {
        val received: ConcurrentLinkedQueue<PlayMessage> = ConcurrentLinkedQueue()
        val router = object : FCastIngressRouter {
            override fun onPlay(request: PlayMessage): FCastIngressRouter.IngressResult {
                received.add(request)
                return FCastIngressRouter.IngressResult.Accepted
            }
            override fun onPause() {}
            override fun onResume() {}
            override fun onStop() {}
            override fun onSeek(seconds: Double) {}
            override fun onSetVolume(volume: Double) {}
            override fun onSetSpeed(speed: Double) {}
        }
        val freePort = ServerSocket(0).use { it.localPort }

        val server = FCastReceiverServer(
            config = FCastReceiverServer.Config(port = freePort),
            routerFactory = { router },
            parentScope = scope,
        )
        server.start()
        try {
            val (input, output) = connectFakeSender(freePort)
            // Drain server-initiated handshake (Version + Initial).
            assertEquals(FCastOpcode.Version, FCastFrame.read(input)!!.opcode)
            assertEquals(FCastOpcode.Initial, FCastFrame.read(input)!!.opcode)

            // Send a Play.
            FCastFrame.write(
                output,
                FCastMessage.Play(PlayMessage(container = "video/mp4", url = "https://e.org/m.mp4")),
            )

            withTimeout(2_000) {
                while (received.isEmpty()) delay(20)
            }
            assertEquals("https://e.org/m.mp4", received.first().url)
        } finally {
            server.stop()
        }
    }

    @Test fun `Play rejection propagates as PlaybackError`() = runBlocking {
        val router = object : FCastIngressRouter {
            override fun onPlay(request: PlayMessage) =
                FCastIngressRouter.IngressResult.Rejected("nope")
            override fun onPause() {}
            override fun onResume() {}
            override fun onStop() {}
            override fun onSeek(seconds: Double) {}
            override fun onSetVolume(volume: Double) {}
            override fun onSetSpeed(speed: Double) {}
        }
        val freePort = ServerSocket(0).use { it.localPort }

        val server = FCastReceiverServer(
            config = FCastReceiverServer.Config(port = freePort),
            routerFactory = { router },
            parentScope = scope,
        )
        server.start()
        try {
            val (input, output) = connectFakeSender(freePort)
            FCastFrame.read(input); FCastFrame.read(input) // drain handshake

            FCastFrame.write(
                output,
                FCastMessage.Play(PlayMessage(container = "video/mp4", url = "u")),
            )

            val error = withTimeout(2_000) {
                var msg: FCastMessage? = null
                while (msg == null || msg !is FCastMessage.PlaybackError) {
                    msg = FCastFrame.read(input)
                }
                msg
            }
            assertEquals("nope", error.payload.message)
        } finally {
            server.stop()
        }
    }

    @Test fun `broadcastPlaybackUpdate reaches every connected sender`() = runBlocking {
        val router = FCastIngressRouter.NoOp
        val freePort = ServerSocket(0).use { it.localPort }
        val server = FCastReceiverServer(
            config = FCastReceiverServer.Config(port = freePort),
            routerFactory = { router },
            parentScope = scope,
        )
        server.start()
        try {
            val (input1, _) = connectFakeSender(freePort)
            val (input2, _) = connectFakeSender(freePort)
            // drain handshake on both
            FCastFrame.read(input1); FCastFrame.read(input1)
            FCastFrame.read(input2); FCastFrame.read(input2)

            // Wait for sessions to register before broadcasting (accept loop runs in scope).
            withTimeout(2_000) { while (server.sessionCount < 2) delay(20) }

            val update = PlaybackUpdateMessage(generationTime = 7L, state = 1, time = 5.0)
            server.broadcastPlaybackUpdate(update)

            val a = withTimeout(2_000) { FCastFrame.read(input1) as FCastMessage.PlaybackUpdate }
            val b = withTimeout(2_000) { FCastFrame.read(input2) as FCastMessage.PlaybackUpdate }
            assertEquals(7L, a.payload.generationTime)
            assertEquals(7L, b.payload.generationTime)
        } finally {
            server.stop()
        }
    }

    @Test fun `Ping triggers Pong from the receiver session`() = runBlocking {
        val freePort = ServerSocket(0).use { it.localPort }
        val server = FCastReceiverServer(
            config = FCastReceiverServer.Config(port = freePort),
            routerFactory = { FCastIngressRouter.NoOp },
            parentScope = scope,
        )
        server.start()
        try {
            val (input, output) = connectFakeSender(freePort)
            FCastFrame.read(input); FCastFrame.read(input) // handshake

            FCastFrame.write(output, FCastMessage.Ping())
            val pong = withTimeout(2_000) { readUntilPong(input) }
            assertEquals(FCastOpcode.Pong, pong.opcode)
            assertNull("body-less Ping → body-less Pong", pong.payload)
        } finally {
            server.stop()
        }
    }

    @Test fun `v4 Ping with timestamps gets an NTP Pong echo`() = runBlocking {
        val freePort = ServerSocket(0).use { it.localPort }
        val server = FCastReceiverServer(
            // Inject a deterministic clock: the real default is SystemClock.elapsedRealtime,
            // an Android stub that throws "Stub!" under plain JUnit — which on the v4 Ping
            // path would crash the session read loop instead of replying.
            config = FCastReceiverServer.Config(port = freePort, clock = { 777L }),
            routerFactory = { FCastIngressRouter.NoOp },
            parentScope = scope,
        )
        server.start()
        try {
            val (input, output) = connectFakeSender(freePort)
            FCastFrame.read(input); FCastFrame.read(input) // handshake

            FCastFrame.write(output, FCastMessage.Ping(PingMessage(t1 = 4_242L)))
            val pong = withTimeout(2_000) { readUntilPong(input) }
            val p = pong.payload
            assertNotNull("v4 Ping must get an NTP Pong body", p)
            assertEquals("t1 must be echoed unchanged", 4_242L, p!!.t1)
            assertEquals("t2 from injected clock", 777L, p.t2)
            assertEquals("t3 from injected clock", 777L, p.t3)
        } finally {
            server.stop()
        }
    }

    /**
     * Read frames until a Pong. Fails fast if the stream closes first (returns null) instead
     * of spinning on EOF forever — a closed-then-spin loop is uncancellable by withTimeout and
     * hangs the whole suite for ~15 min until the worker is force-killed.
     */
    private fun readUntilPong(input: DataInputStream): FCastMessage.Pong {
        while (true) {
            val msg = FCastFrame.read(input)
                ?: error("connection closed before a Pong arrived (receiver crashed?)")
            if (msg is FCastMessage.Pong) return msg
        }
    }

    private fun connectFakeSender(port: Int): Pair<DataInputStream, DataOutputStream> {
        val socket = Socket("127.0.0.1", port)
        socket.tcpNoDelay = true
        return DataInputStream(socket.getInputStream().buffered()) to
            DataOutputStream(socket.getOutputStream().buffered())
    }
}
