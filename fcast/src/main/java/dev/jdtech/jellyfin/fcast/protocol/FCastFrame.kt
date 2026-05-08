package dev.jdtech.jellyfin.fcast.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Wire framing per the FCast spec:
 *
 *   uint32 size (LE) — total bytes of (1 opcode byte + JSON body)
 *   uint8  opcode
 *   bytes  utf-8 JSON body  (size - 1 bytes; may be empty)
 *
 * `size = 1` indicates a body-less message. The total packet (4 + size) must be ≤ 32 KB.
 */
object FCastFrame {

    /** Encode a typed message into a byte array ready to write to a socket. */
    fun encode(message: FCastMessage): ByteArray {
        val body: ByteArray = encodeBody(message)
        val size = 1 + body.size
        require(size + Int.SIZE_BYTES <= FCAST_MAX_PACKET_BYTES) {
            "FCast packet exceeds 32KB limit (size=${size + Int.SIZE_BYTES})"
        }
        val out = ByteArray(Int.SIZE_BYTES + size)
        // size: little-endian
        out[0] = (size and 0xFF).toByte()
        out[1] = ((size ushr 8) and 0xFF).toByte()
        out[2] = ((size ushr 16) and 0xFF).toByte()
        out[3] = ((size ushr 24) and 0xFF).toByte()
        out[4] = message.opcode.code.toByte()
        if (body.isNotEmpty()) {
            System.arraycopy(body, 0, out, 5, body.size)
        }
        return out
    }

    /** Convenience: write a message to a [DataOutputStream] and flush. */
    @Throws(IOException::class)
    fun write(out: DataOutputStream, message: FCastMessage) {
        out.write(encode(message))
        out.flush()
    }

    /**
     * Read one frame from [input]. Returns `null` on clean EOF (peer closed). Throws on malformed
     * frames (oversize, unknown opcode, malformed JSON).
     */
    @Throws(IOException::class)
    fun read(input: DataInputStream): FCastMessage? {
        val sizeBytes = ByteArray(Int.SIZE_BYTES)
        val read = input.readFullyOrEof(sizeBytes)
        if (!read) return null
        val size = (sizeBytes[0].toInt() and 0xFF) or
            ((sizeBytes[1].toInt() and 0xFF) shl 8) or
            ((sizeBytes[2].toInt() and 0xFF) shl 16) or
            ((sizeBytes[3].toInt() and 0xFF) shl 24)
        if (size < 1) {
            throw IOException("FCast frame size too small: $size")
        }
        if (size + Int.SIZE_BYTES > FCAST_MAX_PACKET_BYTES) {
            throw IOException("FCast frame exceeds 32KB limit: ${size + Int.SIZE_BYTES}")
        }
        val opcodeByte = input.readUnsignedByte()
        val opcode = FCastOpcode.fromCode(opcodeByte)
            ?: throw IOException("FCast unknown opcode: $opcodeByte")
        val bodyLen = size - 1
        val body = if (bodyLen == 0) ByteArray(0) else ByteArray(bodyLen).also(input::readFully)
        return decode(opcode, body)
    }

    /** Pure decoder used by [read] and tests. Body must be the JSON portion only (no header). */
    fun decode(opcode: FCastOpcode, body: ByteArray): FCastMessage {
        val text = if (body.isEmpty()) "" else String(body, Charsets.UTF_8)
        return when (opcode) {
            FCastOpcode.None -> FCastMessage.None
            FCastOpcode.Pause -> FCastMessage.Pause
            FCastOpcode.Resume -> FCastMessage.Resume
            FCastOpcode.Stop -> FCastMessage.Stop
            FCastOpcode.Ping -> FCastMessage.Ping
            FCastOpcode.Pong -> FCastMessage.Pong
            FCastOpcode.Play -> FCastMessage.Play(FCastJson.decode(text))
            FCastOpcode.Seek -> FCastMessage.Seek(FCastJson.decode(text))
            FCastOpcode.PlaybackUpdate -> FCastMessage.PlaybackUpdate(FCastJson.decode(text))
            FCastOpcode.VolumeUpdate -> FCastMessage.VolumeUpdate(FCastJson.decode(text))
            FCastOpcode.SetVolume -> FCastMessage.SetVolume(FCastJson.decode(text))
            FCastOpcode.PlaybackError -> FCastMessage.PlaybackError(FCastJson.decode(text))
            FCastOpcode.SetSpeed -> FCastMessage.SetSpeed(FCastJson.decode(text))
            FCastOpcode.Version -> FCastMessage.Version(FCastJson.decode(text))
            FCastOpcode.Initial -> FCastMessage.Initial(FCastJson.decode(text))
            FCastOpcode.PlayUpdate -> FCastMessage.PlayUpdate(FCastJson.decode(text))
            FCastOpcode.SetPlaylistItem -> FCastMessage.SetPlaylistItem(FCastJson.decode(text))
            FCastOpcode.SubscribeEvent -> FCastMessage.SubscribeEvent(FCastJson.decode(text))
            FCastOpcode.UnsubscribeEvent -> FCastMessage.UnsubscribeEvent(FCastJson.decode(text))
            FCastOpcode.Event -> FCastMessage.Event(FCastJson.decode(text))
        }
    }

    private inline fun <reified T> Json.decode(text: String): T = decodeFromString(text)

    /** Encode the JSON body for a message, or empty bytes for body-less opcodes. */
    fun encodeBody(message: FCastMessage): ByteArray = when (message) {
        FCastMessage.None,
        FCastMessage.Pause,
        FCastMessage.Resume,
        FCastMessage.Stop,
        FCastMessage.Ping,
        FCastMessage.Pong,
        -> ByteArray(0)
        is FCastMessage.Play -> FCastJson.encodeToString(message.payload).toByteArray(Charsets.UTF_8)
        is FCastMessage.Seek -> FCastJson.encodeToString(message.payload).toByteArray(Charsets.UTF_8)
        is FCastMessage.PlaybackUpdate -> FCastJson.encodeToString(message.payload).toByteArray(Charsets.UTF_8)
        is FCastMessage.VolumeUpdate -> FCastJson.encodeToString(message.payload).toByteArray(Charsets.UTF_8)
        is FCastMessage.SetVolume -> FCastJson.encodeToString(message.payload).toByteArray(Charsets.UTF_8)
        is FCastMessage.PlaybackError -> FCastJson.encodeToString(message.payload).toByteArray(Charsets.UTF_8)
        is FCastMessage.SetSpeed -> FCastJson.encodeToString(message.payload).toByteArray(Charsets.UTF_8)
        is FCastMessage.Version -> FCastJson.encodeToString(message.payload).toByteArray(Charsets.UTF_8)
        is FCastMessage.Initial -> FCastJson.encodeToString(message.payload).toByteArray(Charsets.UTF_8)
        is FCastMessage.PlayUpdate -> FCastJson.encodeToString(message.payload).toByteArray(Charsets.UTF_8)
        is FCastMessage.SetPlaylistItem -> FCastJson.encodeToString(message.payload).toByteArray(Charsets.UTF_8)
        is FCastMessage.SubscribeEvent -> FCastJson.encodeToString(message.payload).toByteArray(Charsets.UTF_8)
        is FCastMessage.UnsubscribeEvent -> FCastJson.encodeToString(message.payload).toByteArray(Charsets.UTF_8)
        is FCastMessage.Event -> FCastJson.encodeToString(message.payload).toByteArray(Charsets.UTF_8)
    }

    private fun DataInputStream.readFullyOrEof(buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = try {
                read(buf, read, buf.size - read)
            } catch (_: EOFException) {
                return false
            }
            if (n < 0) return false
            read += n
        }
        return true
    }
}
