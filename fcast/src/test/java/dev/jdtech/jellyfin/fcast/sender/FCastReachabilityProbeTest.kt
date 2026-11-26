package dev.jdtech.jellyfin.fcast.sender

import dev.jdtech.jellyfin.fcast.protocol.FCAST_PROTOCOL_VERSION
import dev.jdtech.jellyfin.fcast.protocol.FCastFrame
import dev.jdtech.jellyfin.fcast.protocol.FCastMessage
import dev.jdtech.jellyfin.fcast.protocol.InitialReceiverMessage
import dev.jdtech.jellyfin.fcast.protocol.VersionMessage
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FCastReachabilityProbeTest {

    @Test
    fun `probe reads Version and Initial frames for SpatialFin identity`() = runTest {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val worker = thread {
                server.accept().use { client ->
                    val out = DataOutputStream(client.getOutputStream())
                    FCastFrame.write(out, FCastMessage.Version(VersionMessage(FCAST_PROTOCOL_VERSION)))
                    FCastFrame.write(
                        out,
                        FCastMessage.Initial(
                            InitialReceiverMessage(
                                displayName = "Pixel 11 Pro Fold",
                                appName = "SpatialFin",
                                appVersion = "2.7.74",
                            ),
                        ),
                    )
                }
            }

            val result = probeFCastReceiverDetails(
                host = InetAddress.getLoopbackAddress().hostAddress!!,
                port = server.localPort,
                timeoutMs = 1_000,
            )
            worker.join(2_000)

            assertTrue(result.reachable)
            assertEquals("Pixel 11 Pro Fold", result.displayName)
            assertEquals("SpatialFin", result.appName)
            assertEquals("2.7.74", result.appVersion)
        }
    }

    @Test
    fun `probe stays reachable even when peer sends no Initial frame`() = runTest {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val worker = thread {
                server.accept().use { /* closes cleanly right after accept */ }
            }

            val result = probeFCastReceiverDetails(
                host = InetAddress.getLoopbackAddress().hostAddress!!,
                port = server.localPort,
                timeoutMs = 600,
            )
            worker.join(2_000)

            assertTrue(result.reachable)
            assertNull(result.appName)
            assertNull(result.appVersion)
        }
    }

    @Test
    fun `probe returns unreachable when connection refused`() = runTest {
        val unusedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val result = probeFCastReceiverDetails(
            host = InetAddress.getLoopbackAddress().hostAddress!!,
            port = unusedPort,
            timeoutMs = 400,
        )
        assertFalse(result.reachable)
    }
}
