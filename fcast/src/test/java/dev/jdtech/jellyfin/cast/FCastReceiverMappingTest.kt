package dev.jdtech.jellyfin.cast

import dev.jdtech.jellyfin.fcast.sender.FCastReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FCastReceiverMappingTest {

    @Test
    fun `FCast receiver maps to FCast protocol with SplitAv capability`() {
        val fcast = FCastReceiver(host = "10.0.0.5", port = 46899, name = "Living Room")
        val cast = fcast.toCastReceiver()
        assertEquals(CastProtocol.FCast, cast.protocol)
        assertEquals("fcast:10.0.0.5:46899", cast.id)
        assertEquals("Living Room", cast.name)
        // FCast receivers always carry SplitAv — the wire protocol is the only one that supports
        // the calibration / drift / metadata side-channel the feature needs.
        assertTrue(CastCapability.SplitAv in cast.capabilities)
        assertTrue(CastCapability.Video in cast.capabilities)
        assertTrue(CastCapability.Volume in cast.capabilities)
    }

    @Test
    fun `Cast receiver with FCast protocol round-trips`() {
        val original = FCastReceiver(host = "192.168.1.42", port = 46899, name = "Kitchen TV")
        val roundTripped = original.toCastReceiver().toFCastReceiver()
        assertEquals(original.host, roundTripped.host)
        assertEquals(original.port, roundTripped.port)
        assertEquals(original.name, roundTripped.name)
    }

    @Test
    fun `Cast receiver with non-FCast protocol cannot be converted to FCastReceiver`() {
        val cast = CastReceiver(
            id = "googlecast:abc",
            name = "Kitchen Mini",
            host = "10.0.0.6",
            port = 8009,
            protocol = CastProtocol.GoogleCast,
        )
        // toFCastReceiver routes through protocol-specific wire code; misrouting a Cast/AirPlay
        // receiver into the FCast client would silently fail at TCP-connect time. Fail loud.
        assertThrows(IllegalArgumentException::class.java) { cast.toFCastReceiver() }
    }
}
