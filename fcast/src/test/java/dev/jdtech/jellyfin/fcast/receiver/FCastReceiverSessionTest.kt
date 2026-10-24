package dev.jdtech.jellyfin.fcast.receiver

import dev.jdtech.jellyfin.fcast.protocol.InitialReceiverMessage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FCastReceiverSessionTest {

    @Test
    fun `session closing on immediate EOF does not throw NullPointerException`() = runBlocking {
        val fakeSocket = object : Socket() {
            private val out = ByteArrayOutputStream()
            override fun getOutputStream(): OutputStream = out
            override fun getRemoteSocketAddress(): SocketAddress = InetSocketAddress("127.0.0.1", 1234)
            override fun close() {}
        }

        val info = InitialReceiverMessage(displayName = "Test", appName = "Test", appVersion = "1.0")

        // Passing empty ByteArrayInputStream causes FCastFrame.read to immediately return null (EOF)
        val session = FCastReceiverSession(
            socket = fakeSocket,
            inputStream = ByteArrayInputStream(byteArrayOf()),
            router = FCastIngressRouter.NoOp,
            receiverInfo = info,
            parentScope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
        )

        assertTrue(session.isClosed)
    }

    @Test
    fun `session closing on initial write failure does not throw NullPointerException`() = runBlocking {
        val failingOutputStream = object : OutputStream() {
            override fun write(b: Int) {
                throw IOException("Simulated network failure on write")
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                throw IOException("Simulated network failure on write")
            }
        }

        val fakeSocket = object : Socket() {
            override fun getOutputStream(): OutputStream = failingOutputStream
            override fun getRemoteSocketAddress(): SocketAddress = InetSocketAddress("127.0.0.1", 1234)
            override fun close() {}
        }

        val info = InitialReceiverMessage(displayName = "Test", appName = "Test", appVersion = "1.0")

        val session = FCastReceiverSession(
            socket = fakeSocket,
            inputStream = ByteArrayInputStream(byteArrayOf()),
            router = FCastIngressRouter.NoOp,
            receiverInfo = info,
            parentScope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
        )

        assertTrue(session.isClosed)
    }

    @Test
    fun `manual close is idempotent and does not throw`() = runBlocking {
        val fakeSocket = object : Socket() {
            private val out = ByteArrayOutputStream()
            override fun getOutputStream(): OutputStream = out
            override fun getRemoteSocketAddress(): SocketAddress = InetSocketAddress("127.0.0.1", 1234)
            override fun close() {}
        }

        val info = InitialReceiverMessage(displayName = "Test", appName = "Test", appVersion = "1.0")

        val session = FCastReceiverSession(
            socket = fakeSocket,
            inputStream = ByteArrayInputStream(byteArrayOf()),
            router = FCastIngressRouter.NoOp,
            receiverInfo = info,
            parentScope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
        )

        session.close()
        session.close()
        assertTrue(session.isClosed)
    }
}
