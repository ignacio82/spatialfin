package dev.jdtech.jellyfin.cast.adapter.googlecast

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CastV2FramingTest {

    @Test
    fun `frame round-trips through length-prefixed encoder`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0xFF.toByte(), 0x42)
        val baos = ByteArrayOutputStream()
        CastV2Channel.Framing.write(baos, payload)

        // First 4 bytes are the u32 big-endian length: 5 -> 00 00 00 05.
        val bytes = baos.toByteArray()
        assertEquals(0x00.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
        assertEquals(0x00.toByte(), bytes[2])
        assertEquals(0x05.toByte(), bytes[3])

        val decoded = CastV2Channel.Framing.read(ByteArrayInputStream(bytes))
        assertArrayEquals(payload, decoded)
    }

    @Test
    fun `multiple frames decode in order`() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(4)
        val c = byteArrayOf(5, 6, 7, 8, 9)
        val baos = ByteArrayOutputStream()
        CastV2Channel.Framing.write(baos, a)
        CastV2Channel.Framing.write(baos, b)
        CastV2Channel.Framing.write(baos, c)
        val stream = ByteArrayInputStream(baos.toByteArray())
        assertArrayEquals(a, CastV2Channel.Framing.read(stream))
        assertArrayEquals(b, CastV2Channel.Framing.read(stream))
        assertArrayEquals(c, CastV2Channel.Framing.read(stream))
        assertNull("Stream exhausted should return null cleanly", CastV2Channel.Framing.read(stream))
    }

    @Test
    fun `truncated length prefix returns null on clean EOF`() {
        // Empty stream: read should return null instead of throwing, so the read loop can
        // exit gracefully when the peer closes the socket.
        assertNull(CastV2Channel.Framing.read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `implausible frame size rejected`() {
        // Length prefix = 0x7FFFFFFF (2 GB-ish). Must reject before allocating that buffer.
        val bytes = byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertThrows(IllegalArgumentException::class.java) {
            CastV2Channel.Framing.read(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun `negative frame size rejected`() {
        // u32 read as Int: 0xFFFFFFFF = -1. Must reject.
        val bytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertThrows(IllegalArgumentException::class.java) {
            CastV2Channel.Framing.read(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun `length-prefixed CastMessage round-trips end-to-end`() {
        val msg = CastMessage(
            sourceId = "sender-1",
            destinationId = "receiver-0",
            namespace = CastNamespaces.CONNECTION,
            payloadUtf8 = """{"type":"CONNECT"}""",
        )
        val baos = ByteArrayOutputStream()
        CastV2Channel.Framing.write(baos, CastMessageCodec.encode(msg))
        val readFrame = CastV2Channel.Framing.read(ByteArrayInputStream(baos.toByteArray()))!!
        assertEquals(msg, CastMessageCodec.decode(readFrame))
    }
}
