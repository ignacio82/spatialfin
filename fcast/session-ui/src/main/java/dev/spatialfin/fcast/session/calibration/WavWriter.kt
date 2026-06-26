package dev.spatialfin.fcast.session.calibration

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal WAV (PCM, mono, 16-bit) header writer. Wraps a [ShortArray] of PCM samples in a
 * 44-byte RIFF header so an HTTP client / Media3 ExoPlayer recognizes it as `audio/wav`.
 *
 * Pure: takes raw samples + format params, writes a self-contained .wav byte stream. No I/O
 * beyond the supplied [OutputStream]; no Android dependencies.
 */
object WavWriter {

    private const val WAV_HEADER_SIZE: Int = 44

    /** Total bytes the output will contain for [sampleCount] mono 16-bit samples. */
    fun byteSize(sampleCount: Int): Int = WAV_HEADER_SIZE + sampleCount * 2

    /** Stream the PCM samples plus a RIFF header into [out]. Caller closes the stream. */
    fun writeMono16(
        samples: ShortArray,
        sampleRateHz: Int,
        out: OutputStream,
    ) {
        val byteRate = sampleRateHz * 2
        val dataSize = samples.size * 2
        val riffSize = 36 + dataSize

        val header = ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(riffSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)                      // fmt subchunk size
        header.putShort(1)                     // audio format = PCM
        header.putShort(1)                     // channels
        header.putInt(sampleRateHz)
        header.putInt(byteRate)
        header.putShort(2)                     // block align
        header.putShort(16)                    // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)
        out.write(header.array())

        // PCM body — little-endian. Buffer up to keep socket/file writes coarse-grained.
        val body = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) body.putShort(s)
        out.write(body.array())
        out.flush()
    }

    /** Convenience: render WAV bytes to a [ByteArray]. */
    fun toByteArray(samples: ShortArray, sampleRateHz: Int): ByteArray {
        val baos = java.io.ByteArrayOutputStream(byteSize(samples.size))
        writeMono16(samples, sampleRateHz, baos)
        return baos.toByteArray()
    }
}
