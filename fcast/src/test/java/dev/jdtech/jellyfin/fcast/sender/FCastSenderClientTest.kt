package dev.jdtech.jellyfin.fcast.sender

import dev.jdtech.jellyfin.fcast.protocol.FCAST_PROTOCOL_VERSION
import dev.jdtech.jellyfin.fcast.protocol.FCastFrame
import dev.jdtech.jellyfin.fcast.protocol.FCastMessage
import dev.jdtech.jellyfin.fcast.protocol.FCastOpcode
import dev.jdtech.jellyfin.fcast.protocol.PlayMessage
import dev.jdtech.jellyfin.fcast.protocol.PlaybackUpdateMessage
import dev.jdtech.jellyfin.fcast.protocol.VersionMessage
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives [FCastSenderClient] against a real loopback [ServerSocket] running a hand-rolled fake
 * receiver. We accept exactly one connection, decode frames into a queue, and inject pre-built
 * frames back to the sender on demand.
 */
class FCastSenderClientTest {

    private lateinit var serverSocket: ServerSocket
    private val received: ConcurrentLinkedQueue<FCastMessage> = ConcurrentLinkedQueue()
    private val readyToSend: CountDownLatch = CountDownLatch(1)
    private var serverSocketHandle: Socket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before fun setup() {
        serverSocket = ServerSocket(0) // ephemeral
        Thread({ runFakeReceiver() }, "fake-fcast-receiver").start()
    }

    @After fun tearDown() {
        try { serverSocketHandle?.close() } catch (_: Exception) {}
        try { serverSocket.close() } catch (_: Exception) {}
        scope.cancel()
    }

    @Test fun `sender opens connection and writes Version then Initial`() = runBlocking {
        val client = FCastSenderClient(receiverFor(), parentScope = scope)
        client.connect()
        try {
            val first = waitForFrame()
            val second = waitForFrame()
            assertEquals(FCastOpcode.Version, first.opcode)
            assertEquals(FCAST_PROTOCOL_VERSION, (first as FCastMessage.Version).payload.version)
            assertEquals(FCastOpcode.Initial, second.opcode)
        } finally {
            client.close()
        }
    }

    @Test fun `play forwards a Play frame`() = runBlocking {
        val client = FCastSenderClient(receiverFor(), parentScope = scope)
        client.connect()
        try {
            // Drain handshake.
            waitForFrame(); waitForFrame()

            client.play(PlayMessage(container = "video/mp4", url = "https://e.org/m.mp4", time = 12.0))
            val frame = waitForFrame()
            assertTrue("expected Play, got ${frame.opcode}", frame is FCastMessage.Play)
            val payload = (frame as FCastMessage.Play).payload
            assertEquals("video/mp4", payload.container)
            assertEquals(12.0, payload.time!!, 0.0)
        } finally {
            client.close()
        }
    }

    @Test fun `inbound PlaybackUpdate is exposed via flow`() = runBlocking {
        val client = FCastSenderClient(receiverFor(), parentScope = scope)
        client.connect()
        try {
            waitForFrame(); waitForFrame() // drain handshake

            // Server-side: push a PlaybackUpdate to the connected sender.
            val update = PlaybackUpdateMessage(
                generationTime = 42L, state = 1, time = 5.0, duration = 100.0, speed = 1.0,
            )
            sendToClient(FCastMessage.PlaybackUpdate(update))

            val received = withTimeout(2_000) { client.playbackUpdates.first() }
            assertEquals(42L, received.generationTime)
            assertEquals(1, received.state)
        } finally {
            client.close()
        }
    }

    @Test fun `inbound Ping triggers a Pong response`() = runBlocking {
        val client = FCastSenderClient(receiverFor(), parentScope = scope)
        client.connect()
        try {
            waitForFrame(); waitForFrame() // drain handshake

            sendToClient(FCastMessage.Ping)
            val pong = waitForFrame()
            assertEquals(FCastOpcode.Pong, pong.opcode)
        } finally {
            client.close()
        }
    }

    @Test fun `inbound Version sets negotiated version to the lower bound`() = runBlocking {
        val client = FCastSenderClient(receiverFor(), parentScope = scope)
        client.connect()
        try {
            waitForFrame(); waitForFrame() // drain handshake

            sendToClient(FCastMessage.Version(VersionMessage(version = 2)))
            val negotiated = withTimeout(2_000) {
                var v: Int?
                while (true) { v = client.negotiatedVersion.value; if (v != null) break; Thread.sleep(10) }
                v
            }
            assertEquals(2, negotiated)
        } finally {
            client.close()
        }
    }

    private fun receiverFor() = FCastReceiver(
        host = "127.0.0.1",
        port = serverSocket.localPort,
        name = "fake",
    )

    private fun runFakeReceiver() {
        try {
            val sock = serverSocket.accept()
            serverSocketHandle = sock
            val input = DataInputStream(sock.getInputStream().buffered())
            val output = DataOutputStream(sock.getOutputStream().buffered())
            // expose output for tests via thread-local channel
            outputChannel = output
            readyToSend.countDown()
            while (true) {
                val frame = FCastFrame.read(input) ?: break
                received.add(frame)
            }
        } catch (_: Exception) {
            // Socket closed during teardown.
        }
    }

    private fun waitForFrame(timeoutMs: Long = 2_000L): FCastMessage {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            received.poll()?.let { return it }
            Thread.sleep(10)
        }
        error("no FCast frame received within ${timeoutMs}ms")
    }

    private fun sendToClient(message: FCastMessage) {
        readyToSend.await(2, TimeUnit.SECONDS)
        val out = outputChannel ?: error("server not ready")
        FCastFrame.write(out, message)
    }

    @Volatile private var outputChannel: DataOutputStream? = null
}
