package dev.spatialfin.fcast.session.calibration

import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * WAV (PCM, mono 16-bit and streaming 24-bit) writer.
 *
 * Keeps the legacy 16-bit in-memory helper for FCast chirp calibration while providing
 * [StreamingWav24Writer] for Spec 1 Tier 2 UMIK-1 sweep captures (zero full-file RAM buffer).
 */
object WavWriter {

    private const val WAV_HEADER_SIZE: Int = 44
    private const val PCM24_MAX: Int = 8_388_607
    private const val PCM24_MIN: Int = -8_388_608

    /** Total bytes the output will contain for [sampleCount] mono 16-bit samples. */
    fun byteSize(sampleCount: Int): Int = WAV_HEADER_SIZE + sampleCount * 2

    /** Total bytes the output will contain for [sampleCount] mono 24-bit samples. */
    fun byteSize24(sampleCount: Long): Long = WAV_HEADER_SIZE + sampleCount * 3L

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

    /** Clamp and quantize a normalized float sample `[-1.0, 1.0]` into signed 24-bit PCM. */
    fun floatSampleToPcm24(sample: Float): Int {
        val clamped = sample.coerceIn(-1f, 1f)
        return (clamped * PCM24_MAX.toFloat()).roundToInt().coerceIn(PCM24_MIN, PCM24_MAX)
    }

    /** Compute peak digital full-scale level in dBFS (`[-96.0, 0.0]`) for a float PCM chunk. */
    fun computePeakDbfs(samples: FloatArray, count: Int = samples.size): Float {
        val n = count.coerceIn(0, samples.size)
        var peak = 0f
        for (i in 0 until n) {
            val a = abs(samples[i])
            if (a > peak) peak = a
        }
        if (peak <= 1e-5f) return -96f
        return (20.0 * log10(peak.toDouble())).toFloat().coerceIn(-96f, 0f)
    }

    /**
     * Disk-streaming 24-bit mono little-endian WAV writer backed by [RandomAccessFile].
     * Writes a 44-byte placeholder header on open and patches `riffSize` (offset 4) and
     * `dataSize` (offset 40) on [close].
     */
    class StreamingWav24Writer(
        val file: File,
        val sampleRateHz: Int = 48_000,
    ) : Closeable {
        private val raf = RandomAccessFile(file, "rw")
        private var chunkBytes = ByteArray(4096 * 3)
        private var closed = false

        var samplesWritten: Long = 0L
            private set

        var dataBytesWritten: Long = 0L
            private set

        init {
            raf.setLength(0L)
            writeHeader(dataSize = 0)
        }

        fun writeFloatSamples(samples: FloatArray, count: Int = samples.size) {
            check(!closed) { "StreamingWav24Writer already closed" }
            val n = count.coerceIn(0, samples.size)
            if (n == 0) return
            val needed = n * 3
            if (chunkBytes.size < needed) {
                chunkBytes = ByteArray(needed)
            }
            var outIdx = 0
            for (i in 0 until n) {
                val pcm = floatSampleToPcm24(samples[i])
                chunkBytes[outIdx++] = (pcm and 0xFF).toByte()
                chunkBytes[outIdx++] = ((pcm ushr 8) and 0xFF).toByte()
                chunkBytes[outIdx++] = ((pcm ushr 16) and 0xFF).toByte()
            }
            raf.write(chunkBytes, 0, needed)
            samplesWritten += n.toLong()
            dataBytesWritten += needed.toLong()
        }

        fun writeRawPcm24Le(bytes: ByteArray, length: Int = bytes.size) {
            check(!closed) { "StreamingWav24Writer already closed" }
            val aligned = (length.coerceIn(0, bytes.size) / 3) * 3
            if (aligned == 0) return
            raf.write(bytes, 0, aligned)
            samplesWritten += (aligned / 3).toLong()
            dataBytesWritten += aligned.toLong()
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                val finalDataBytes = dataBytesWritten.coerceAtMost(Int.MAX_VALUE.toLong() - 36L).toInt()
                raf.seek(0L)
                writeHeader(dataSize = finalDataBytes)
                raf.fd.sync()
            } finally {
                raf.close()
            }
        }

        private fun writeHeader(dataSize: Int) {
            val byteRate = sampleRateHz * 3
            val riffSize = 36 + dataSize
            val header = ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(riffSize)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1)          // PCM
            header.putShort(1)          // mono
            header.putInt(sampleRateHz)
            header.putInt(byteRate)
            header.putShort(3)          // block align = 3 bytes/sample
            header.putShort(24)         // bits per sample
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataSize)
            raf.write(header.array())
        }
    }
}
