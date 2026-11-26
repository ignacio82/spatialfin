package dev.jdtech.jellyfin.fcast.discovery

import dev.jdtech.jellyfin.fcast.discovery.LanNetworkChangePolicy.Action
import dev.jdtech.jellyfin.fcast.discovery.LanNetworkChangePolicy.EventType
import org.junit.Assert.assertEquals
import org.junit.Test

class LanNetworkChangeMonitorTest {

    @Test
    fun `available with different network handle triggers re-register even on same IP`() {
        val action = LanNetworkChangePolicy.decide(
            eventType = EventType.AVAILABLE,
            currentBoundIp = "192.168.1.171",
            candidateBindIp = "192.168.1.171",
            isAdvertiserActive = true,
            networkHandleChanged = true,
        )
        assertEquals(Action.RE_REGISTER, action)
    }

    @Test
    fun `initial available on same handle and same active IP is a no-op`() {
        val action = LanNetworkChangePolicy.decide(
            eventType = EventType.AVAILABLE,
            currentBoundIp = "192.168.1.171",
            candidateBindIp = "192.168.1.171",
            isAdvertiserActive = true,
            networkHandleChanged = false,
        )
        assertEquals(Action.NONE, action)
    }

    @Test
    fun `link change with new IPv4 triggers re-register`() {
        val action = LanNetworkChangePolicy.decide(
            eventType = EventType.LINK_CHANGED,
            currentBoundIp = "192.168.1.171",
            candidateBindIp = "192.168.1.205",
            isAdvertiserActive = true,
            networkHandleChanged = false,
        )
        assertEquals(Action.RE_REGISTER, action)
    }

    @Test
    fun `link change with unchanged IPv4 and active advertiser is a no-op`() {
        val action = LanNetworkChangePolicy.decide(
            eventType = EventType.LINK_CHANGED,
            currentBoundIp = "192.168.1.171",
            candidateBindIp = "192.168.1.171",
            isAdvertiserActive = true,
            networkHandleChanged = false,
        )
        assertEquals(Action.NONE, action)
    }

    @Test
    fun `link change with changed network handle triggers re-register even on same IP`() {
        // Covers Android firing onAvailable(newNet) followed within ~10ms by onLinkPropertiesChanged(newNet),
        // which cancels the debounced AVAILABLE event so LINK_CHANGED executes for the new handle.
        val action = LanNetworkChangePolicy.decide(
            eventType = EventType.LINK_CHANGED,
            currentBoundIp = "192.168.1.171",
            candidateBindIp = "192.168.1.171",
            isAdvertiserActive = true,
            networkHandleChanged = true,
        )
        assertEquals(Action.RE_REGISTER, action)
    }

    @Test
    fun `inactive advertiser with valid LAN IP triggers re-register on any event`() {
        val action = LanNetworkChangePolicy.decide(
            eventType = EventType.LINK_CHANGED,
            currentBoundIp = null,
            candidateBindIp = "192.168.1.171",
            isAdvertiserActive = false,
            networkHandleChanged = false,
        )
        assertEquals(Action.RE_REGISTER, action)
    }

    @Test
    fun `lost with no remaining bindable IP unregisters active advertiser`() {
        val action = LanNetworkChangePolicy.decide(
            eventType = EventType.LOST,
            currentBoundIp = "192.168.1.171",
            candidateBindIp = null,
            isAdvertiserActive = true,
            networkHandleChanged = false,
        )
        assertEquals(Action.UNREGISTER, action)
    }

    @Test
    fun `lost while fallback IP already matches current bound IP is a no-op`() {
        val action = LanNetworkChangePolicy.decide(
            eventType = EventType.LOST,
            currentBoundIp = "192.168.1.171",
            candidateBindIp = "192.168.1.171",
            isAdvertiserActive = true,
            networkHandleChanged = false,
        )
        assertEquals(Action.NONE, action)
    }
}
