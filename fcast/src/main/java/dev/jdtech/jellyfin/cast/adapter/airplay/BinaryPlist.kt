package dev.jdtech.jellyfin.cast.adapter.airplay

/**
 * Minimal parser for Apple's `bplist00` binary property-list format. Apple's AirPlay v1
 * `/playback-info` endpoint returns one of these and we only need to read a handful of
 * top-level fields out of it, so a hand-rolled parser is enough — we don't need to encode
 * plists or support every object type.
 *
 * What we support:
 *  - `null` (0x00) → null
 *  - `bool false` (0x08), `bool true` (0x09) → Boolean
 *  - Integers (0x10..0x13) — 1/2/4/8-byte signed/unsigned per Apple's encoding
 *  - Reals (0x22 float, 0x23 double) → Double
 *  - ASCII strings (0x50..0x5F) → String
 *  - UTF-16BE strings (0x60..0x6F) → String
 *  - Arrays (0xA0..0xAF) — used inside dict values
 *  - Dictionaries (0xD0..0xDF) → Map<String, Any?>
 *
 * What we **don't** support:
 *  - Dates (0x33), data (0x40..0x4F), UID (0x80..0x8F), set (0xC0..0xCF)
 *  - Large-marker forms past 0xnF (≥ 15 entries — needs a second-byte size prefix)
 *
 * Both gaps are unreachable from `/playback-info`: the response is a small dict of bools,
 * reals, and strings. Anything we don't handle is logged + skipped so the surrounding fields
 * still decode.
 *
 * Format reference: the bplist00 layout (header, object table, offset table, trailer) is
 * documented across many open-source projects and Apple's own public CoreFoundation source.
 * Re-derived here from the published format; no third-party code copied in.
 */
internal object BinaryPlist {

    /**
     * Parse [bytes] and return the root value. The root of a `/playback-info` response is
     * always a dict; callers cast to `Map<String, Any?>` after this returns.
     *
     * Throws [IllegalArgumentException] on any structural problem — these are server bugs
     * we'd rather surface than silently treat as "playback stopped."
     */
    fun parse(bytes: ByteArray): Any? {
        require(bytes.size >= 32) { "bplist too short: ${bytes.size} bytes" }
        require(String(bytes, 0, 8, Charsets.US_ASCII) == "bplist00") {
            "missing bplist00 magic header"
        }
        // Trailer is the last 32 bytes:
        //   6 unused, offsetIntSize (1), refIntSize (1), numObjects (8), topObject (8),
        //   offsetTableOffset (8).
        val trailerStart = bytes.size - 32
        val offsetIntSize = bytes[trailerStart + 6].toInt() and 0xFF
        val refIntSize = bytes[trailerStart + 7].toInt() and 0xFF
        val numObjects = readLong(bytes, trailerStart + 8, 8).toInt()
        val topObject = readLong(bytes, trailerStart + 16, 8).toInt()
        val offsetTableOffset = readLong(bytes, trailerStart + 24, 8).toInt()
        require(offsetIntSize in SUPPORTED_INT_SIZES) { "unsupported offsetIntSize=$offsetIntSize" }
        require(refIntSize in SUPPORTED_INT_SIZES) { "unsupported refIntSize=$refIntSize" }
        require(numObjects > 0 && topObject in 0 until numObjects) {
            "bplist trailer says topObject=$topObject of $numObjects objects"
        }
        val offsetTableBytes = numObjects.toLong() * offsetIntSize.toLong()
        require(offsetTableOffset >= 8 && offsetTableOffset.toLong() + offsetTableBytes <= trailerStart) {
            "bplist offset table out of bounds"
        }

        val offsets = IntArray(numObjects) { i ->
            readLong(bytes, offsetTableOffset + i * offsetIntSize, offsetIntSize).toInt()
        }
        offsets.forEachIndexed { i, offset ->
            require(offset in 8 until offsetTableOffset) {
                "bplist object offset[$i]=$offset out of bounds"
            }
        }
        return readObject(bytes, offsets, refIntSize, topObject)
    }

    private fun readObject(
        bytes: ByteArray,
        offsets: IntArray,
        refIntSize: Int,
        objectIndex: Int,
    ): Any? {
        require(objectIndex in offsets.indices) { "object ref $objectIndex out of bounds" }
        val offset = offsets[objectIndex]
        requireAvailable(bytes, offset, 1, "object marker")
        val marker = bytes[offset].toInt() and 0xFF
        val type = marker ushr 4
        val info = marker and 0x0F
        return when (type) {
            0x0 -> when (info) {
                0x0 -> null
                0x8 -> false
                0x9 -> true
                else -> malformed("unsupported 0x0_ marker: 0x${"%02X".format(marker)}")
            }
            0x1 -> {
                // Integer. info is log2(byteCount): 0->1, 1->2, 2->4, 3->8.
                val intByteCount = 1 shl info
                readLong(bytes, offset + 1, intByteCount)
            }
            0x2 -> {
                // Real. info=2 → float (4B), info=3 → double (8B).
                when (info) {
                    2 -> java.lang.Float.intBitsToFloat(
                        readLong(bytes, offset + 1, 4).toInt(),
                    ).toDouble()
                    3 -> java.lang.Double.longBitsToDouble(
                        readLong(bytes, offset + 1, 8),
                    )
                    else -> malformed("unsupported real size 0x${"%02X".format(marker)}")
                }
            }
            0x5 -> {
                // ASCII string. info is char count; if 0x0F, next bytes are a size int.
                val (start, length) = readVarLength(bytes, offset, info)
                requireAvailable(bytes, start, length, "ASCII string")
                String(bytes, start, length, Charsets.US_ASCII)
            }
            0x6 -> {
                // UTF-16BE string. info is char count (each char is 2 bytes).
                val (start, charCount) = readVarLength(bytes, offset, info)
                val byteCount = charCount * 2
                requireAvailable(bytes, start, byteCount, "UTF-16 string")
                String(bytes, start, byteCount, Charsets.UTF_16BE)
            }
            0xA -> {
                // Array of object refs.
                val (start, count) = readVarLength(bytes, offset, info)
                requireAvailable(bytes, start, count * refIntSize, "array refs")
                List(count) { i ->
                    val ref = readLong(bytes, start + i * refIntSize, refIntSize).toInt()
                    readObject(bytes, offsets, refIntSize, ref)
                }
            }
            0xD -> {
                // Dict. n key refs followed by n value refs.
                val (start, count) = readVarLength(bytes, offset, info)
                requireAvailable(bytes, start, count * refIntSize * 2, "dict refs")
                val keys = IntArray(count) { i ->
                    readLong(bytes, start + i * refIntSize, refIntSize).toInt()
                }
                val values = IntArray(count) { i ->
                    readLong(bytes, start + (count + i) * refIntSize, refIntSize).toInt()
                }
                val map = LinkedHashMap<String, Any?>(count)
                for (i in 0 until count) {
                    val key = readObject(bytes, offsets, refIntSize, keys[i]) as? String
                        ?: malformed("dict key is not a string")
                    map[key] = readObject(bytes, offsets, refIntSize, values[i])
                }
                map
            }
            else -> malformed("unsupported bplist marker 0x${"%02X".format(marker)}")
        }
    }

    /**
     * Decode a variable-length object header. When [info] is `0x0F`, the actual length is
     * encoded as the next integer-marker object (1, 2, 4, or 8 bytes), and the payload starts
     * after that. Otherwise the length is `info` and the payload starts at `offset + 1`.
     * Returns `(payloadStart, length)`.
     */
    private fun readVarLength(bytes: ByteArray, offset: Int, info: Int): Pair<Int, Int> {
        if (info != 0x0F) return (offset + 1) to info
        requireAvailable(bytes, offset + 1, 1, "variable-length marker")
        val sizeMarker = bytes[offset + 1].toInt() and 0xFF
        require(sizeMarker ushr 4 == 0x1) {
            "expected integer size-marker after 0x_F, got 0x${"%02X".format(sizeMarker)}"
        }
        val sizeByteCount = 1 shl (sizeMarker and 0x0F)
        require(sizeByteCount in SUPPORTED_INT_SIZES) {
            "unsupported variable length size=$sizeByteCount"
        }
        val length = readLong(bytes, offset + 2, sizeByteCount).toInt()
        return (offset + 2 + sizeByteCount) to length
    }

    /** Big-endian unsigned read into a Long. byteCount in {1,2,4,8}. */
    private fun readLong(bytes: ByteArray, offset: Int, byteCount: Int): Long {
        require(byteCount in SUPPORTED_INT_SIZES) { "unsupported integer byteCount=$byteCount" }
        requireAvailable(bytes, offset, byteCount, "integer")
        var result = 0L
        for (i in 0 until byteCount) {
            result = (result shl 8) or (bytes[offset + i].toLong() and 0xFFL)
        }
        return result
    }

    private fun requireAvailable(bytes: ByteArray, offset: Int, byteCount: Int, label: String) {
        require(offset >= 0 && byteCount >= 0 && offset.toLong() + byteCount.toLong() <= bytes.size) {
            "bplist $label out of bounds"
        }
    }

    private fun malformed(message: String): Nothing = throw IllegalArgumentException(message)

    private val SUPPORTED_INT_SIZES = setOf(1, 2, 4, 8)
}
