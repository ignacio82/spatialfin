package dev.jdtech.jellyfin.cast

import dev.jdtech.jellyfin.cast.adapter.FCastAdapter
import dev.jdtech.jellyfin.cast.adapter.googlecast.GoogleCastAdapter
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CastAdapterFactoryTest {

    private fun receiver(protocol: CastProtocol) = CastReceiver(
        id = "$protocol:test",
        name = "Test",
        host = "10.0.0.1",
        port = if (protocol == CastProtocol.GoogleCast) 8009 else 46899,
        protocol = protocol,
        capabilities = if (protocol == CastProtocol.FCast) FCAST_DEFAULT_CAPABILITIES else emptySet(),
    )

    @Test
    fun `factory returns FCastAdapter for FCast receiver`() {
        val adapter = CastAdapterFactory.create(receiver(CastProtocol.FCast))
        assertTrue(adapter is FCastAdapter)
    }

    @Test
    fun `factory returns GoogleCastAdapter for Google Cast receiver`() {
        val adapter = CastAdapterFactory.create(receiver(CastProtocol.GoogleCast))
        assertTrue(adapter is GoogleCastAdapter)
    }

    @Test
    fun `factory rejects AirPlay receiver until PR 4`() {
        assertThrows(NotImplementedError::class.java) {
            CastAdapterFactory.create(receiver(CastProtocol.AirPlay))
        }
    }

    @Test
    fun `FCastAdapter constructor rejects non-FCast receiver`() {
        // Defense in depth — if a future caller bypasses the factory, fail loud rather than
        // silently dialing 46899 against a Chromecast.
        val cast = receiver(CastProtocol.GoogleCast)
        assertThrows(IllegalArgumentException::class.java) { FCastAdapter(cast) }
    }

    @Test
    fun `GoogleCastAdapter constructor rejects non-GoogleCast receiver`() {
        val fcast = receiver(CastProtocol.FCast)
        assertThrows(IllegalArgumentException::class.java) { GoogleCastAdapter(fcast) }
    }
}
