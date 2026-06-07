package dev.jdtech.jellyfin.sendspin.receiver.audio

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import com.sendspin.protocol.StreamFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

internal class SendspinDecodeException(message: String, cause: Throwable) :
    RuntimeException(message, cause)

internal class SendspinMediaCodecAudioDecoder private constructor(
    private val codec: MediaCodec,
    private val codecConfigBuffers: List<ByteArray>,
) {

    private val bufferInfo = MediaCodec.BufferInfo()
    private var currentOutputFormat: AudioOutputFormat? = null
    private var resubmitCodecConfig = false
    private var released = false

    fun decode(packet: ByteArray, presentationTimeUs: Long): List<DecodedAudio> {
        if (released) return emptyList()
        try {
            val decoded = mutableListOf<DecodedAudio>()
            drain(decoded, timeoutUs = 0)
            if (resubmitCodecConfig) {
                for (config in codecConfigBuffers) {
                    if (!queueInput(config, presentationTimeUs = 0L, flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG)) {
                        return decoded
                    }
                }
                resubmitCodecConfig = false
            }
            if (queueInput(packet, presentationTimeUs = presentationTimeUs, flags = 0)) {
                drain(decoded, timeoutUs = OUTPUT_TIMEOUT_US)
            }
            return decoded
        } catch (e: IllegalStateException) {
            released = true
            runCatching { codec.release() }
            throw SendspinDecodeException("SendSpin decoder entered an invalid MediaCodec state", e)
        }
    }

    fun flush() {
        if (released) return
        codec.flush()
        resubmitCodecConfig = codecConfigBuffers.isNotEmpty()
    }

    fun release() {
        released = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    private fun queueInput(data: ByteArray, presentationTimeUs: Long, flags: Int): Boolean {
        if (released) return false
        val inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (inputIndex < 0) return false
        val inputBuffer = codec.getInputBuffer(inputIndex) ?: return false
        if (data.size > inputBuffer.capacity()) {
            error("encoded SendSpin packet is too large for decoder input buffer: ${data.size} > ${inputBuffer.capacity()}")
        }
        inputBuffer.clear()
        inputBuffer.put(data)
        codec.queueInputBuffer(inputIndex, 0, data.size, presentationTimeUs, flags)
        return true
    }

    private fun drain(decoded: MutableList<DecodedAudio>, timeoutUs: Long) {
        var timeout = timeoutUs
        while (!released) {
            when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeout)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    currentOutputFormat = AudioOutputFormat.from(codec.outputFormat)
                    timeout = 0
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> timeout = 0
                else -> {
                    if (outputIndex >= 0) {
                        readOutput(outputIndex, decoded)
                        timeout = 0
                    }
                }
            }
        }
    }

    private fun readOutput(outputIndex: Int, decoded: MutableList<DecodedAudio>) {
        try {
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
            if (bufferInfo.size <= 0) return
            val outputBuffer = codec.getOutputBuffer(outputIndex) ?: return
            outputBuffer.position(bufferInfo.offset)
            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
            val data = ByteArray(bufferInfo.size)
            outputBuffer.get(data)
            val outputFormat = currentOutputFormat ?: AudioOutputFormat.from(codec.getOutputFormat(outputIndex))
            currentOutputFormat = outputFormat
            decoded += DecodedAudio(data, outputFormat)
        } finally {
            codec.releaseOutputBuffer(outputIndex, false)
        }
    }

    companion object {
        private const val INPUT_TIMEOUT_US = 2_000L
        private const val OUTPUT_TIMEOUT_US = 2_000L

        fun create(format: StreamFormat): SendspinMediaCodecAudioDecoder {
            val mimeType = mimeTypeFor(format.codec)
            val mediaFormat = MediaFormat.createAudioFormat(mimeType, format.sampleRate, format.channels)
            mediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)

            val codecConfig = codecConfigBuffers(format)
            codecConfig.forEachIndexed { index, bytes ->
                mediaFormat.setByteBuffer("csd-$index", ByteBuffer.wrap(bytes))
            }

            val codec = MediaCodec.createDecoderByType(mimeType)
            try {
                codec.configure(mediaFormat, null, null, 0)
                codec.start()
                return SendspinMediaCodecAudioDecoder(codec, codecConfig)
            } catch (e: Exception) {
                runCatching { codec.release() }
                throw e
            }
        }

        fun canDecode(codecName: String, sampleRate: Int, channels: Int): Boolean {
            val mimeType = runCatching { mimeTypeFor(codecName) }.getOrNull() ?: return codecName == CODEC_PCM
            val format = MediaFormat.createAudioFormat(mimeType, sampleRate, channels)
            return runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) != null
            }.getOrDefault(false)
        }

        private fun mimeTypeFor(codec: String): String =
            when (codec) {
                CODEC_FLAC -> MIMETYPE_AUDIO_FLAC
                CODEC_OPUS -> MIMETYPE_AUDIO_OPUS
                else -> error("unsupported encoded SendSpin codec: $codec")
            }

        private fun codecConfigBuffers(format: StreamFormat): List<ByteArray> =
            when (format.codec) {
                CODEC_FLAC -> listOf(flacCodecSpecificData(format))
                CODEC_OPUS -> listOf(
                    opusHead(channels = format.channels, sampleRate = format.sampleRate),
                    nativeLong(0L),
                    nativeLong(OPUS_SEEK_PREROLL_NS),
                )
                else -> emptyList()
            }

        private fun flacCodecSpecificData(format: StreamFormat): ByteArray {
            val header = format.codecHeader?.takeIf { it.isNotBlank() }?.let(Base64.getDecoder()::decode)
                ?: error("FLAC stream/start missing codec_header")
            if (header.size >= 4 &&
                header[0] == 'f'.code.toByte() &&
                header[1] == 'L'.code.toByte() &&
                header[2] == 'a'.code.toByte() &&
                header[3] == 'C'.code.toByte()
            ) {
                return header
            }
            return if (header.size == FLAC_STREAMINFO_BYTES) {
                byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte(), 0x80.toByte(), 0, 0, FLAC_STREAMINFO_BYTES.toByte()) +
                    header
            } else {
                header
            }
        }

        private fun opusHead(channels: Int, sampleRate: Int): ByteArray {
            require(channels in 1..2) { "unsupported Opus channel count: $channels" }
            return ByteBuffer.allocate(OPUS_HEAD_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("OpusHead".toByteArray(Charsets.US_ASCII))
                .put(1)
                .put(channels.toByte())
                .putShort(0)
                .putInt(sampleRate)
                .putShort(0)
                .put(0)
                .array()
        }

        private fun nativeLong(value: Long): ByteArray =
            ByteBuffer.allocate(Long.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .putLong(value)
                .array()

        private const val CODEC_PCM = "pcm"
        private const val CODEC_FLAC = "flac"
        private const val CODEC_OPUS = "opus"
        private const val MIMETYPE_AUDIO_FLAC = "audio/flac"
        private const val MIMETYPE_AUDIO_OPUS = "audio/opus"
        private const val MAX_INPUT_SIZE = 256 * 1024
        private const val FLAC_STREAMINFO_BYTES = 34
        private const val OPUS_HEAD_BYTES = 19
        private const val OPUS_SEEK_PREROLL_NS = 80_000_000L
    }
}

internal data class DecodedAudio(
    val data: ByteArray,
    val format: AudioOutputFormat,
)

internal data class AudioOutputFormat(
    val sampleRate: Int,
    val channels: Int,
    val encoding: Int,
) {
    val bytesPerFrame: Int
        get() = bytesPerSample(encoding) * channels

    companion object {
        fun from(format: MediaFormat): AudioOutputFormat {
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val encoding =
                if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else {
                    android.media.AudioFormat.ENCODING_PCM_16BIT
                }
            return AudioOutputFormat(sampleRate = sampleRate, channels = channels, encoding = encoding)
        }

        fun bytesPerSample(encoding: Int): Int =
            when (encoding) {
                android.media.AudioFormat.ENCODING_PCM_8BIT -> 1
                android.media.AudioFormat.ENCODING_PCM_16BIT -> 2
                android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                android.media.AudioFormat.ENCODING_PCM_32BIT,
                android.media.AudioFormat.ENCODING_PCM_FLOAT -> 4
                else -> error("unsupported decoded PCM encoding: $encoding")
            }
    }
}
