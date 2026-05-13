package dev.jdtech.jellyfin.cast.adapter.airplay

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BinaryPlistTest {

    @Test
    fun `bool true decodes`() {
        val plist = buildPlist {
            // Single root object: true.
            byte(0x09)
            offsetTable(listOf(8))
            trailer(offsetIntSize = 1, refIntSize = 1, numObjects = 1, topObject = 0)
        }
        assertEquals(true, BinaryPlist.parse(plist))
    }

    @Test
    fun `bool false decodes`() {
        val plist = buildPlist {
            byte(0x08)
            offsetTable(listOf(8))
            trailer(offsetIntSize = 1, refIntSize = 1, numObjects = 1, topObject = 0)
        }
        assertEquals(false, BinaryPlist.parse(plist))
    }

    @Test
    fun `null decodes`() {
        val plist = buildPlist {
            byte(0x00)
            offsetTable(listOf(8))
            trailer(offsetIntSize = 1, refIntSize = 1, numObjects = 1, topObject = 0)
        }
        assertNull(BinaryPlist.parse(plist))
    }

    @Test
    fun `single-byte integer decodes`() {
        val plist = buildPlist {
            byte(0x10) // int, 2^0 = 1 byte
            byte(42)
            offsetTable(listOf(8))
            trailer(offsetIntSize = 1, refIntSize = 1, numObjects = 1, topObject = 0)
        }
        assertEquals(42L, BinaryPlist.parse(plist))
    }

    @Test
    fun `eight-byte integer decodes`() {
        val plist = buildPlist {
            byte(0x13) // int, 2^3 = 8 bytes
            // 0x00_00_00_01_02_03_04_05 big-endian = 4328719365
            bytes(byteArrayOf(0, 0, 0, 1, 2, 3, 4, 5))
            offsetTable(listOf(8))
            trailer(offsetIntSize = 1, refIntSize = 1, numObjects = 1, topObject = 0)
        }
        assertEquals(4_328_719_365L, BinaryPlist.parse(plist))
    }

    @Test
    fun `double decodes correctly`() {
        // Encode the AirPlay `position = 123.5` case the playback-info poller will hit most.
        val bits = java.lang.Double.doubleToRawLongBits(123.5)
        val plist = buildPlist {
            byte(0x23) // real, 8 bytes
            for (i in 7 downTo 0) {
                byte((bits ushr (i * 8) and 0xFF).toInt())
            }
            offsetTable(listOf(8))
            trailer(offsetIntSize = 1, refIntSize = 1, numObjects = 1, topObject = 0)
        }
        assertEquals(123.5, BinaryPlist.parse(plist) as Double, 0.0)
    }

    @Test
    fun `ASCII string decodes`() {
        val plist = buildPlist {
            byte(0x55) // ASCII string, length 5
            bytes("hello".toByteArray(Charsets.US_ASCII))
            offsetTable(listOf(8))
            trailer(offsetIntSize = 1, refIntSize = 1, numObjects = 1, topObject = 0)
        }
        assertEquals("hello", BinaryPlist.parse(plist))
    }

    @Test
    fun `dictionary with mixed values decodes`() {
        // Build a 2-entry dict: {"readyToPlay": true, "position": 42}. Object table:
        //   [0] dict          (root, refs 1+2 as keys, 3+4 as values)
        //   [1] "readyToPlay" (string)
        //   [2] "position"    (string)
        //   [3] true          (bool)
        //   [4] 42            (int)
        val out = ByteArrayOutputStream()
        // Header
        out.write("bplist00".toByteArray(Charsets.US_ASCII))
        // [0] dict header: 0xD2 (dict, 2 entries) followed by 2 key refs and 2 value refs.
        val dictOffset = out.size()
        out.write(0xD2)
        out.write(1); out.write(2)   // key refs
        out.write(3); out.write(4)   // value refs
        val k1Offset = out.size()
        out.write(0x5B) // ASCII string, len 11
        out.write("readyToPlay".toByteArray(Charsets.US_ASCII))
        val k2Offset = out.size()
        out.write(0x58) // ASCII string, len 8
        out.write("position".toByteArray(Charsets.US_ASCII))
        val v1Offset = out.size()
        out.write(0x09) // true
        val v2Offset = out.size()
        out.write(0x10) // int, 1 byte
        out.write(42)
        val offsetTableStart = out.size()
        val offsets = listOf(dictOffset, k1Offset, k2Offset, v1Offset, v2Offset)
        for (o in offsets) out.write(o)
        // Trailer
        out.write(ByteArray(6)) // unused
        out.write(1)            // offsetIntSize
        out.write(1)            // refIntSize
        out.write(ByteArray(7)); out.write(offsets.size) // numObjects (8 bytes, low byte)
        out.write(ByteArray(8)) // topObject = 0
        // offsetTableOffset (8 bytes, big-endian)
        val ote = offsetTableStart
        for (i in 7 downTo 0) {
            out.write((ote.toLong() ushr (i * 8) and 0xFF).toInt())
        }
        val decoded = BinaryPlist.parse(out.toByteArray()) as Map<*, *>
        assertEquals(2, decoded.size)
        assertEquals(true, decoded["readyToPlay"])
        assertEquals(42L, decoded["position"])
    }

    @Test
    fun `missing magic header is rejected`() {
        val bytes = ByteArray(40)
        assertThrows(IllegalArgumentException::class.java) { BinaryPlist.parse(bytes) }
    }

    @Test
    fun `too-short buffer is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            BinaryPlist.parse(ByteArray(16))
        }
    }

    // --- builder helper for hand-constructed fixtures ---

    private class PlistBuilder {
        private val out = ByteArrayOutputStream()
        init { out.write("bplist00".toByteArray(Charsets.US_ASCII)) }
        fun byte(b: Int) { out.write(b and 0xFF) }
        fun bytes(b: ByteArray) { out.write(b) }
        fun offsetTable(offsets: List<Int>) {
            for (o in offsets) out.write(o and 0xFF)
        }
        fun trailer(offsetIntSize: Int, refIntSize: Int, numObjects: Int, topObject: Int) {
            val offsetTableOffset = out.size() - (numObjects * offsetIntSize)
            out.write(ByteArray(6))
            out.write(offsetIntSize)
            out.write(refIntSize)
            for (i in 7 downTo 0) out.write((numObjects.toLong() ushr (i * 8) and 0xFF).toInt())
            for (i in 7 downTo 0) out.write((topObject.toLong() ushr (i * 8) and 0xFF).toInt())
            for (i in 7 downTo 0) out.write((offsetTableOffset.toLong() ushr (i * 8) and 0xFF).toInt())
        }
        fun build(): ByteArray = out.toByteArray()
    }

    private fun buildPlist(block: PlistBuilder.() -> Unit): ByteArray =
        PlistBuilder().apply(block).build()
}
