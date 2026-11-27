package dev.jdtech.jellyfin.cast.adapter.sendspinavr

import dev.jdtech.jellyfin.cast.CastCapability
import dev.jdtech.jellyfin.cast.CastProtocol
import dev.jdtech.jellyfin.cast.CastReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendspinAvrDiscoveryBrowserTest {

    private val validTxt = mapOf(
        "v" to "1",
        "id" to "avr-550e8400e29b41d4a716446655440000",
        "auth" to "bearer",
        "name" to "Living Room Theater",
        "layout" to "7.1.4",
        "sw" to "0.1.0",
        "tls" to "0",
    )

    @Test
    fun `parseServiceInfo builds SendspinAvr receiver with expected capabilities`() {
        val receiver = SendspinAvrDiscoveryBrowser.parseServiceInfo(
            serviceName = "spatialfin-avr",
            host = "192.168.1.42",
            port = 1984,
            txt = validTxt,
        )

        assertNotNull(receiver)
        receiver!!
        assertEquals("SendspinAvr:avr-550e8400e29b41d4a716446655440000", receiver.id)
        assertEquals("Living Room Theater", receiver.name)
        assertEquals("192.168.1.42", receiver.host)
        assertEquals(1984, receiver.port)
        assertEquals(CastProtocol.SendspinAvr, receiver.protocol)
        assertEquals("7.1.4", receiver.modelName)
        assertEquals("0.1.0", receiver.appVersion)
        assertFalse(receiver.useTls)
        assertEquals(CastReceiver.Source.Mdns, receiver.source)
        assertTrue(receiver.supports(CastCapability.Audio))
        assertTrue(receiver.supports(CastCapability.Volume))
        assertTrue(receiver.supports(CastCapability.SplitAv))
        assertTrue(receiver.supports(CastCapability.TheaterAudioV1))
    }

    @Test
    fun `parseServiceInfo handles tls=1 default port and serviceName fallback`() {
        val receiver = SendspinAvrDiscoveryBrowser.parseServiceInfo(
            serviceName = "Studio AVR",
            host = "192.168.1.44",
            port = 0,
            txt = validTxt + mapOf("tls" to "1") - "name",
        )

        assertNotNull(receiver)
        receiver!!
        assertEquals(SendspinAvrDiscoveryBrowser.SENDSPIN_AVR_DEFAULT_PORT, receiver.port)
        assertEquals("Studio AVR", receiver.name)
        assertTrue(receiver.useTls)
    }

    @Test
    fun `parseServiceInfo rejects invalid version, server id, auth, or blank host`() {
        assertNull(SendspinAvrDiscoveryBrowser.parseServiceInfo("avr", null, 1984, validTxt))
        assertNull(SendspinAvrDiscoveryBrowser.parseServiceInfo("avr", " ", 1984, validTxt))
        assertNull(SendspinAvrDiscoveryBrowser.parseServiceInfo("avr", "192.168.1.42", 1984, validTxt + ("v" to "2")))
        assertNull(SendspinAvrDiscoveryBrowser.parseServiceInfo("avr", "192.168.1.42", 1984, validTxt - "v"))
        assertNull(SendspinAvrDiscoveryBrowser.parseServiceInfo("avr", "192.168.1.42", 1984, validTxt + ("id" to "bad-id")))
        assertNull(SendspinAvrDiscoveryBrowser.parseServiceInfo("avr", "192.168.1.42", 1984, validTxt + ("auth" to "none")))
    }
}
