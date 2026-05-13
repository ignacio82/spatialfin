package dev.jdtech.jellyfin.cast.adapter.googlecast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CastMessageCodecTest {

    @Test
    fun `round-trip preserves all primitive fields`() {
        val original = CastMessage(
            sourceId = "sender-12345",
            destinationId = "receiver-0",
            namespace = "urn:x-cast:com.google.cast.tp.connection",
            payloadUtf8 = """{"type":"CONNECT"}""",
        )
        val bytes = CastMessageCodec.encode(original)
        val decoded = CastMessageCodec.decode(bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun `binary payload round-trips`() {
        val payload = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte(), 0x7E, 0x42)
        val original = CastMessage(
            sourceId = "sender-x",
            destinationId = "receiver-0",
            namespace = "urn:x-cast:custom",
            payloadType = CastMessage.PayloadType.Binary,
            payloadBinary = payload,
        )
        val decoded = CastMessageCodec.decode(CastMessageCodec.encode(original))
        assertEquals(original, decoded)
        assertNull(decoded.payloadUtf8)
        assertTrue(payload.contentEquals(decoded.payloadBinary))
    }

    @Test
    fun `tag encoding matches proto3 wire format`() {
        // Hand-compute the first few bytes of an encoded message and verify the encoder
        // produces the expected proto3 wire layout. Cheap insurance against an "off by one"
        // in the tag = (field_num << 3) | wire_type math.
        val msg = CastMessage(
            sourceId = "a",
            destinationId = "b",
            namespace = "n",
            payloadUtf8 = "p",
        )
        val bytes = CastMessageCodec.encode(msg)
        // Field 1 (protocol_version), wire 0 (varint), value 0:
        //   tag = (1 << 3) | 0 = 8 = 0x08, value = 0x00
        assertEquals(0x08.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
        // Field 2 (source_id), wire 2 (length-delimited):
        //   tag = (2 << 3) | 2 = 18 = 0x12, length = 1, value = 'a' = 0x61
        assertEquals(0x12.toByte(), bytes[2])
        assertEquals(0x01.toByte(), bytes[3])
        assertEquals('a'.code.toByte(), bytes[4])
    }

    @Test
    fun `varint encodes multi-byte values correctly`() {
        // protocol_version is varint. Encode a hypothetical value that needs 2 bytes (300).
        // Real Cast peers never send this — Castv2_1_0 has code 0 — but the encoder must
        // still produce the canonical proto3 multi-byte varint or future enum additions
        // would break.
        val multibyteSource = "x".repeat(300)
        val msg = CastMessage(
            sourceId = multibyteSource,
            destinationId = "r",
            namespace = "n",
            payloadUtf8 = "p",
        )
        val bytes = CastMessageCodec.encode(msg)
        val decoded = CastMessageCodec.decode(bytes)
        assertEquals(300, decoded.sourceId.length)
    }

    @Test
    fun `decoder skips unknown fields`() {
        // Simulate a forward-compatible scenario: a future CastMessage version adds field 99
        // (varint, e.g. an extension marker). We must keep decoding the known fields rather
        // than failing loud — `ignoreUnknownKeys` semantics, the same rule the FCast codec uses.
        val base = CastMessage(
            sourceId = "src",
            destinationId = "dst",
            namespace = "ns",
            payloadUtf8 = "payload",
        )
        val baseBytes = CastMessageCodec.encode(base)
        // Append: field 99, wire 0 (varint), value 42.
        //   tag = (99 << 3) | 0 = 792. Encoded as varint: 0x98, 0x06.
        //   value = 42 → 0x2A.
        val extended = baseBytes + byteArrayOf(0x98.toByte(), 0x06, 0x2A)
        val decoded = CastMessageCodec.decode(extended)
        assertEquals(base, decoded)
    }

    @Test
    fun `decoder rejects truncated varint`() {
        // First byte has MSB set (signals more bytes coming), but buffer ends. Decoder must
        // fail loud — a silent decode of garbage would mask wire-level bugs that the Cast
        // session reader needs to surface as a session error.
        val bytes = byteArrayOf(0x80.toByte())
        assertThrows(IllegalStateException::class.java) { CastMessageCodec.decode(bytes) }
    }

    @Test
    fun `decoder rejects out-of-range length-delimited field`() {
        // Field 2 (source_id), wire 2, length = 100, but only 1 actual byte. Must fail loud.
        val bytes = byteArrayOf(0x12, 100, 'x'.code.toByte())
        assertThrows(IllegalArgumentException::class.java) { CastMessageCodec.decode(bytes) }
    }

    @Test
    fun `default protocol version is explicit in wire form`() {
        // Even though `protocolVersion = Castv2_1_0` is the proto3 default, we always emit it
        // explicitly. Older receivers (rare) used to misbehave on omitted required-style fields.
        val msg = CastMessage(
            sourceId = "s",
            destinationId = "d",
            namespace = "n",
            payloadUtf8 = "p",
        )
        val bytes = CastMessageCodec.encode(msg)
        // First two bytes must be the protocol_version field.
        assertEquals(0x08.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
    }

    @Test
    fun `empty strings round-trip as zero-length fields`() {
        // A real Cast message rarely has empty namespace/source, but proto3 wire format must
        // still encode the field marker + length-zero length-delimited blob — not skip the
        // field. The decoder must see them as empty strings, not null.
        val msg = CastMessage(
            sourceId = "",
            destinationId = "",
            namespace = "",
            payloadUtf8 = "",
        )
        val decoded = CastMessageCodec.decode(CastMessageCodec.encode(msg))
        assertEquals("", decoded.sourceId)
        assertEquals("", decoded.destinationId)
        assertEquals("", decoded.namespace)
        assertEquals("", decoded.payloadUtf8)
    }
}
