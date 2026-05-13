package dev.jdtech.jellyfin.cast.adapter.googlecast

import java.io.ByteArrayOutputStream

/**
 * In-memory representation of the Chromium `extensions.api.cast_channel.CastMessage` wire
 * format. Re-derived from the public schema published with the Chromium open-source release;
 * no third-party code was copied into this file.
 *
 * Field layout (proto3 numbering):
 *
 *   1 protocol_version  enum  (varint)   0 = CASTV2_1_0
 *   2 source_id         string           sender-side virtual address
 *   3 destination_id    string           "receiver-0" until LAUNCH yields a transportId
 *   4 namespace         string           urn:x-cast:com.google.cast.<channel>
 *   5 payload_type      enum  (varint)   0 = STRING (JSON), 1 = BINARY
 *   6 payload_utf8      string           JSON payload (used for STRING)
 *   7 payload_binary    bytes            opaque (used for BINARY)
 *
 * The Cast V2 control surface only uses [PayloadType.String] in practice; binary payloads
 * exist for future extensions and are accepted/decoded but never produced here.
 */
internal data class CastMessage(
    val protocolVersion: ProtocolVersion = ProtocolVersion.Castv2_1_0,
    val sourceId: String,
    val destinationId: String,
    val namespace: String,
    val payloadType: PayloadType = PayloadType.String,
    val payloadUtf8: String? = null,
    val payloadBinary: ByteArray? = null,
) {
    enum class ProtocolVersion(val code: Int) {
        Castv2_1_0(0),
        ;

        companion object {
            fun fromCode(code: Int): ProtocolVersion = entries.firstOrNull { it.code == code }
                ?: Castv2_1_0
        }
    }

    enum class PayloadType(val code: Int) {
        String(0),
        Binary(1),
        ;

        companion object {
            fun fromCode(code: Int): PayloadType = entries.firstOrNull { it.code == code } ?: String
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CastMessage) return false
        return protocolVersion == other.protocolVersion &&
            sourceId == other.sourceId &&
            destinationId == other.destinationId &&
            namespace == other.namespace &&
            payloadType == other.payloadType &&
            payloadUtf8 == other.payloadUtf8 &&
            payloadBinary.contentEqualsOrBothNull(other.payloadBinary)
    }

    override fun hashCode(): Int {
        var result = protocolVersion.hashCode()
        result = 31 * result + sourceId.hashCode()
        result = 31 * result + destinationId.hashCode()
        result = 31 * result + namespace.hashCode()
        result = 31 * result + payloadType.hashCode()
        result = 31 * result + (payloadUtf8?.hashCode() ?: 0)
        result = 31 * result + (payloadBinary?.contentHashCode() ?: 0)
        return result
    }

    private fun ByteArray?.contentEqualsOrBothNull(other: ByteArray?): Boolean =
        if (this == null) other == null else other != null && this.contentEquals(other)
}

/**
 * Pure-Kotlin protobuf codec for [CastMessage]. Hand-rolled rather than generated because the
 * one message we send is small and bringing in `protobuf-gradle-plugin` for a single .proto
 * file is not worth the build-config tax. Tested by `CastMessageCodecTest` against fixtures
 * captured from an actual Chromium serialization.
 *
 * Proto3 wire format primer (only the bits we use):
 *  - Field tag = (field_number shl 3) or wire_type, encoded as varint.
 *  - Wire type 0 = varint (signed/unsigned ints, enums, bools).
 *  - Wire type 2 = length-delimited (strings, bytes, embedded messages).
 *  - Varint: little-endian groups of 7 bits, MSB set on all but the last byte.
 *
 * Encoder skips fields that are absent or hold the proto3 default — every Cast V2 receiver
 * we've seen tolerates the absence of fields it doesn't need (protocol_version=0 is implicit;
 * payload_binary stays away unless payload_type=Binary).
 */
internal object CastMessageCodec {

    private const val FIELD_PROTOCOL_VERSION = 1
    private const val FIELD_SOURCE_ID = 2
    private const val FIELD_DESTINATION_ID = 3
    private const val FIELD_NAMESPACE = 4
    private const val FIELD_PAYLOAD_TYPE = 5
    private const val FIELD_PAYLOAD_UTF8 = 6
    private const val FIELD_PAYLOAD_BINARY = 7

    private const val WIRE_VARINT = 0
    private const val WIRE_LENGTH_DELIMITED = 2

    fun encode(message: CastMessage): ByteArray {
        val out = ByteArrayOutputStream()
        // protocol_version: required in the schema, but value 0 (CASTV2_1_0) is the proto3
        // default. Chromium tolerates both an explicit zero and an omitted field — explicit is
        // safer for older receivers, so we always write it.
        writeVarintField(out, FIELD_PROTOCOL_VERSION, message.protocolVersion.code.toLong())
        writeStringField(out, FIELD_SOURCE_ID, message.sourceId)
        writeStringField(out, FIELD_DESTINATION_ID, message.destinationId)
        writeStringField(out, FIELD_NAMESPACE, message.namespace)
        writeVarintField(out, FIELD_PAYLOAD_TYPE, message.payloadType.code.toLong())
        message.payloadUtf8?.let { writeStringField(out, FIELD_PAYLOAD_UTF8, it) }
        message.payloadBinary?.let { writeBytesField(out, FIELD_PAYLOAD_BINARY, it) }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): CastMessage {
        var protocolVersion = CastMessage.ProtocolVersion.Castv2_1_0
        var sourceId = ""
        var destinationId = ""
        var namespace = ""
        var payloadType = CastMessage.PayloadType.String
        var payloadUtf8: String? = null
        var payloadBinary: ByteArray? = null

        var i = 0
        while (i < bytes.size) {
            val (tag, tagLen) = readVarint(bytes, i)
            i += tagLen
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x7L).toInt()
            when (fieldNumber) {
                FIELD_PROTOCOL_VERSION -> {
                    require(wireType == WIRE_VARINT) { "protocol_version wire type" }
                    val (v, n) = readVarint(bytes, i)
                    i += n
                    protocolVersion = CastMessage.ProtocolVersion.fromCode(v.toInt())
                }
                FIELD_SOURCE_ID -> { val (s, n) = readString(bytes, i); i += n; sourceId = s }
                FIELD_DESTINATION_ID -> { val (s, n) = readString(bytes, i); i += n; destinationId = s }
                FIELD_NAMESPACE -> { val (s, n) = readString(bytes, i); i += n; namespace = s }
                FIELD_PAYLOAD_TYPE -> {
                    require(wireType == WIRE_VARINT) { "payload_type wire type" }
                    val (v, n) = readVarint(bytes, i)
                    i += n
                    payloadType = CastMessage.PayloadType.fromCode(v.toInt())
                }
                FIELD_PAYLOAD_UTF8 -> { val (s, n) = readString(bytes, i); i += n; payloadUtf8 = s }
                FIELD_PAYLOAD_BINARY -> { val (b, n) = readBytes(bytes, i); i += n; payloadBinary = b }
                else -> i += skipUnknown(bytes, i, wireType)
            }
        }
        return CastMessage(
            protocolVersion = protocolVersion,
            sourceId = sourceId,
            destinationId = destinationId,
            namespace = namespace,
            payloadType = payloadType,
            payloadUtf8 = payloadUtf8,
            payloadBinary = payloadBinary,
        )
    }

    private fun writeVarintField(out: ByteArrayOutputStream, fieldNumber: Int, value: Long) {
        writeTag(out, fieldNumber, WIRE_VARINT)
        writeVarint(out, value)
    }

    private fun writeStringField(out: ByteArrayOutputStream, fieldNumber: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeTag(out, fieldNumber, WIRE_LENGTH_DELIMITED)
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun writeBytesField(out: ByteArrayOutputStream, fieldNumber: Int, value: ByteArray) {
        writeTag(out, fieldNumber, WIRE_LENGTH_DELIMITED)
        writeVarint(out, value.size.toLong())
        out.write(value)
    }

    private fun writeTag(out: ByteArrayOutputStream, fieldNumber: Int, wireType: Int) {
        writeVarint(out, ((fieldNumber.toLong() shl 3) or wireType.toLong()))
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        // Treat as unsigned for the shift, matching protobuf semantics. Negative ints would
        // need 10 bytes (1's-extended) but Cast V2 never sends those.
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                out.write(v.toInt() and 0x7F)
                return
            }
            out.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
    }

    /** Returns (value, bytesConsumed). */
    private fun readVarint(bytes: ByteArray, offset: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = offset
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            i++
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return result to (i - offset)
            shift += 7
            require(shift <= 63) { "varint overflow at offset $offset" }
        }
        error("truncated varint at offset $offset")
    }

    /** Returns (string, bytesConsumed including the length prefix). */
    private fun readString(bytes: ByteArray, offset: Int): Pair<String, Int> {
        val (b, n) = readBytes(bytes, offset)
        return String(b, Charsets.UTF_8) to n
    }

    /** Returns (slice, bytesConsumed including the length prefix). */
    private fun readBytes(bytes: ByteArray, offset: Int): Pair<ByteArray, Int> {
        val (len, lenSize) = readVarint(bytes, offset)
        val start = offset + lenSize
        val end = start + len.toInt()
        require(end <= bytes.size) { "length-delimited overruns buffer at offset $offset" }
        return bytes.copyOfRange(start, end) to (lenSize + len.toInt())
    }

    /** Skip a field we don't recognise. Returns bytes consumed past the tag. */
    private fun skipUnknown(bytes: ByteArray, offset: Int, wireType: Int): Int = when (wireType) {
        WIRE_VARINT -> readVarint(bytes, offset).second
        WIRE_LENGTH_DELIMITED -> readBytes(bytes, offset).second
        else -> error("unsupported wire type $wireType at offset $offset")
    }
}
