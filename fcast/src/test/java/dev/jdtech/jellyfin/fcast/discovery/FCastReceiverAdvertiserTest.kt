package dev.jdtech.jellyfin.fcast.discovery

import android.content.Context
import android.net.wifi.WifiManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FCastReceiverAdvertiserTest {

    private fun mockNetworkInterface(
        name: String,
        isUp: Boolean = true,
        isLoopback: Boolean = false,
        supportsMulticast: Boolean = true,
        addresses: List<InetAddress> = emptyList(),
    ): NetworkInterface {
        val iface = mockk<NetworkInterface>(relaxed = true)
        every { iface.name } returns name
        every { iface.isUp } returns isUp
        every { iface.isLoopback } returns isLoopback
        every { iface.supportsMulticast() } returns supportsMulticast
        every { iface.inetAddresses } returns java.util.Collections.enumeration(addresses)
        return iface
    }

    private fun mockIpv4(ip: String): Inet4Address {
        val addr = mockk<Inet4Address>(relaxed = true)
        every { addr.hostAddress } returns ip
        every { addr.isLoopbackAddress } returns false
        every { addr.isAnyLocalAddress } returns false
        every { addr.isLinkLocalAddress } returns false
        return addr
    }

    @Test
    fun `isExcludedInterface identifies cellular dummy and p2p interfaces`() {
        val advertiser = FCastReceiverAdvertiser(mockk(relaxed = true))
        assertTrue(advertiser.isExcludedInterface("rmnet_data0"))
        assertTrue(advertiser.isExcludedInterface("rmnet0"))
        assertTrue(advertiser.isExcludedInterface("ccmni0"))
        assertTrue(advertiser.isExcludedInterface("pdp0"))
        assertTrue(advertiser.isExcludedInterface("wwan0"))
        assertTrue(advertiser.isExcludedInterface("clat4"))
        assertTrue(advertiser.isExcludedInterface("radio0"))
        assertTrue(advertiser.isExcludedInterface("cellular0"))
        assertTrue(advertiser.isExcludedInterface("dummy0"))
        assertTrue(advertiser.isExcludedInterface("p2p-wlan0-0"))
        assertTrue(advertiser.isExcludedInterface("p2p0"))

        assertFalse(advertiser.isExcludedInterface("wlan0"))
        assertFalse(advertiser.isExcludedInterface("eth0"))
        assertFalse(advertiser.isExcludedInterface("en0"))
        assertFalse(advertiser.isExcludedInterface("tun0"))
    }

    @Test
    fun `isPreferredInterface identifies wifi and ethernet interfaces`() {
        val advertiser = FCastReceiverAdvertiser(mockk(relaxed = true))
        assertTrue(advertiser.isPreferredInterface("wlan0"))
        assertTrue(advertiser.isPreferredInterface("wifi0"))
        assertTrue(advertiser.isPreferredInterface("ap0"))
        assertTrue(advertiser.isPreferredInterface("softap0"))
        assertTrue(advertiser.isPreferredInterface("eth0"))
        assertTrue(advertiser.isPreferredInterface("en0"))

        assertFalse(advertiser.isPreferredInterface("tun0"))
        assertFalse(advertiser.isPreferredInterface("rmnet0"))
    }

    @Test
    fun `findBindableAddress skips cellular and prefers wifi`() {
        val cellularIp = mockIpv4("10.0.0.5")
        val wifiIp = mockIpv4("192.168.1.50")

        val cellularIface = mockNetworkInterface(name = "rmnet_data0", addresses = listOf(cellularIp))
        val wifiIface = mockNetworkInterface(name = "wlan0", addresses = listOf(wifiIp))

        val advertiser = FCastReceiverAdvertiser(
            context = mockk(relaxed = true),
            interfaceProvider = { listOf(cellularIface, wifiIface) },
        )

        val selected = advertiser.findBindableAddress()
        assertEquals(wifiIp, selected)
    }

    @Test
    fun `findBindableAddress returns null when only cellular or dummy interfaces exist`() {
        val cellularIp = mockIpv4("10.0.0.5")
        val dummyIp = mockIpv4("192.168.200.1")

        val cellularIface = mockNetworkInterface(name = "rmnet_data0", addresses = listOf(cellularIp))
        val dummyIface = mockNetworkInterface(name = "dummy0", addresses = listOf(dummyIp))

        val advertiser = FCastReceiverAdvertiser(
            context = mockk(relaxed = true),
            interfaceProvider = { listOf(cellularIface, dummyIface) },
        )

        assertNull(advertiser.findBindableAddress())
    }

    @Test
    fun `register catches SocketException ENODEV without crashing`() = runBlocking {
        val wifiIp = mockIpv4("192.168.1.50")
        val wifiIface = mockNetworkInterface(name = "wlan0", addresses = listOf(wifiIp))

        val mockLock = mockk<WifiManager.MulticastLock>(relaxed = true)
        val mockWifiManager = mockk<WifiManager>()
        every { mockWifiManager.createMulticastLock(any()) } returns mockLock
        val mockContext = mockk<Context>()
        every { mockContext.applicationContext } returns mockContext
        every { mockContext.getSystemService(WifiManager::class.java) } returns mockWifiManager

        val advertiser = FCastReceiverAdvertiser(
            context = mockContext,
            jmdnsFactory = {
                throw SocketException("setsockopt failed: ENODEV (No such device)")
            },
            interfaceProvider = { listOf(wifiIface) },
        )

        // Should not throw
        advertiser.register(instanceName = "test-instance", port = 12345)
        verify { mockLock.release() }
    }

    @Test
    fun `register succeeds and unregister cleans up`() = runBlocking {
        val wifiIp = mockIpv4("192.168.1.50")
        val wifiIface = mockNetworkInterface(name = "wlan0", addresses = listOf(wifiIp))

        val mockLock = mockk<WifiManager.MulticastLock>(relaxed = true)
        val mockWifiManager = mockk<WifiManager>()
        every { mockWifiManager.createMulticastLock(any()) } returns mockLock
        val mockContext = mockk<Context>()
        every { mockContext.applicationContext } returns mockContext
        every { mockContext.getSystemService(WifiManager::class.java) } returns mockWifiManager

        val mockJmDns = mockk<JmDNS>(relaxed = true)

        val advertiser = FCastReceiverAdvertiser(
            context = mockContext,
            jmdnsFactory = { mockJmDns },
            interfaceProvider = { listOf(wifiIface) },
        )

        advertiser.register(instanceName = "test-instance", port = 12345)
        verify { mockJmDns.registerService(any<ServiceInfo>()) }

        advertiser.unregister()
        verify { mockJmDns.unregisterService(any()) }
        verify { mockJmDns.close() }
        verify { mockLock.release() }
    }
}
