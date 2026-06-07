package dev.jdtech.jellyfin.sendspin.receiver.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import com.sendspin.protocol.AudioBuffer
import com.sendspin.protocol.AudioPlayer
import com.sendspin.protocol.AudioFormat as SendspinAudioFormat
import com.sendspin.protocol.StreamFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Android PCM renderer for SendSpin's protocol-managed audio buffer.
 *
 * SendSpinClient handles WebSocket framing, server arbitration, clock sync, and timestamped
 * buffering. This class only renders chunks once [AudioBuffer] says they are due locally.
 */
class AndroidSendspinAudioPlayer(
    private val buffer: AudioBuffer,
) : AudioPlayer {

    @Volatile override var isPlaying: Boolean = false
        private set

    @Volatile override var droppedDecodeFrames: Long = 0L
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private var audioTrack: AudioTrack? = null
    private var audioTrackFormat: AudioOutputFormat? = null
    private var decoder: SendspinMediaCodecAudioDecoder? = null
    private var streamFormat: StreamFormat? = null
    private var pumpJob: Job? = null
    private var volume = 1.0f

    override fun configure(format: StreamFormat) {
        synchronized(lock) {
            if (streamFormat == format && (format.codec == CODEC_PCM && audioTrack != null || format.codec != CODEC_PCM && decoder != null)) {
                return
            }
            releaseAudioLocked()
            streamFormat = format
            if (format.codec == CODEC_PCM) {
                audioTrack = buildAudioTrack(outputFormatForPcm(format)).apply { setVolume(volume) }
            } else {
                decoder = SendspinMediaCodecAudioDecoder.create(format)
            }
        }
        Timber.tag(TAG).i(
            "configured codec=%s sampleRate=%d channels=%d bitDepth=%d",
            format.codec,
            format.sampleRate,
            format.channels,
            format.bitDepth,
        )
    }

    override fun start() {
        synchronized(lock) {
            val format = streamFormat ?: return
            val track = if (format.codec == CODEC_PCM) {
                audioTrack ?: buildAudioTrack(outputFormatForPcm(format)).also { audioTrack = it }
            } else {
                audioTrack
            }
            isPlaying = true
            track?.play()
            if (pumpJob?.isActive != true) {
                pumpJob = scope.launch { pumpAudio() }
            }
        }
    }

    override fun flush() {
        buffer.flush()
        synchronized(lock) {
            runCatching { decoder?.flush() }
                .onFailure { Timber.tag(TAG).w(it, "Sendspin decoder flush failed") }
            val track = audioTrack ?: return
            runCatching { track.pause() }
            runCatching { track.flush() }
            if (isPlaying) {
                runCatching { track.play() }
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            isPlaying = false
            releaseAudioLocked()
            streamFormat = null
        }
        buffer.flush()
        Timber.tag(TAG).i("stopped")
    }

    override fun transition(format: StreamFormat) {
        val resume = isPlaying
        configure(format)
        if (resume) start()
    }

    fun setVolume(volLevel: Int) {
        val normalized = (volLevel / 100.0).coerceIn(0.0, 1.0)
        volume = (normalized * normalized * normalized).toFloat()
        synchronized(lock) {
            audioTrack?.setVolume(volume)
        }
    }

    fun close() {
        stop()
        pumpJob?.cancel()
        pumpJob = null
        scope.cancel()
    }

    private suspend fun pumpAudio() {
        try {
            while (currentCoroutineContext().isActive) {
                if (!isPlaying) {
                    delay(IDLE_DELAY_MS)
                    continue
                }

                val waitMicros = buffer.nextChunkDelayMicros()
                when {
                    waitMicros == null -> {
                        buffer.signalUnderrun()
                        delay(UNDERRUN_DELAY_MS)
                    }
                    waitMicros > READY_WINDOW_US -> {
                        delay((waitMicros / 1_000L).coerceIn(1L, MAX_WAIT_DELAY_MS))
                    }
                    else -> {
                        val chunk = buffer.poll()
                        if (chunk != null) {
                            renderChunk(chunk.data, chunk.serverTimestampMicros)
                        } else {
                            delay(READY_RETRY_DELAY_MS)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Sendspin audio pump failed")
            synchronized(lock) {
                isPlaying = false
                releaseAudioLocked()
                streamFormat = null
            }
            buffer.flush()
        }
    }

    private suspend fun renderChunk(data: ByteArray, presentationTimeUs: Long) {
        val format = streamFormat ?: return
        if (format.codec == CODEC_PCM) {
            writePcm(DecodedAudio(data, outputFormatForPcm(format)))
            return
        }
        val decoded =
            try {
                synchronized(lock) {
                    decoder?.decode(data, presentationTimeUs).orEmpty()
                }
            } catch (e: SendspinDecodeException) {
                handleDecodeFailure(format, e)
                return
            }
        for (frame in decoded) {
            writePcm(frame)
        }
    }

    private fun handleDecodeFailure(format: StreamFormat, error: SendspinDecodeException) {
        Timber.tag(TAG).e(
            error,
            "decoder failed codec=%s sampleRate=%d channels=%d bitDepth=%d",
            format.codec,
            format.sampleRate,
            format.channels,
            format.bitDepth,
        )
        synchronized(lock) {
            isPlaying = false
            releaseAudioLocked()
            streamFormat = null
        }
        buffer.flush()
    }

    private suspend fun writePcm(frame: DecodedAudio) {
        var offset = 0
        val data = frame.data
        while (offset < data.size && currentCoroutineContext().isActive && isPlaying) {
            val track = synchronized(lock) {
                ensureAudioTrackLocked(frame.format)
            } ?: return
            val written = track.write(data, offset, data.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written < 0) {
                droppedDecodeFrames += (data.size - offset) / frame.format.bytesPerFrame
                Timber.tag(TAG).w("AudioTrack write failed: %d", written)
                return
            }
            if (written == 0) {
                delay(WRITE_RETRY_DELAY_MS)
                continue
            }
            offset += written
        }
    }

    private fun ensureAudioTrackLocked(format: AudioOutputFormat): AudioTrack? {
        audioTrack?.takeIf { audioTrackFormat == format }?.let { return it }
        releaseTrackLocked()
        val track = buildAudioTrack(format).apply {
            setVolume(volume)
            if (isPlaying) play()
        }
        audioTrack = track
        return track
    }

    private fun buildAudioTrack(format: AudioOutputFormat): AudioTrack {
        val channelMask = channelMaskFor(format.channels)
        val minBufferSize = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, format.encoding)
        require(minBufferSize > 0) {
            "invalid AudioTrack format sampleRate=${format.sampleRate} channels=${format.channels} encoding=${format.encoding}"
        }

        val targetBufferBytes =
            format.sampleRate * format.bytesPerFrame * TARGET_BUFFER_MS / 1_000
        val bufferSize = maxOf(minBufferSize * 2, targetBufferBytes)
        val bufferFrames = bufferSize / format.bytesPerFrame
        val startThresholdFrames =
            (format.sampleRate * START_THRESHOLD_MS / 1_000)
                .coerceAtLeast(1)
                .coerceAtMost(bufferFrames)

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(format.encoding)
                    .setSampleRate(format.sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setStartThresholdInFrames(startThresholdFrames)
                }
                require(state == AudioTrack.STATE_INITIALIZED) { "AudioTrack failed to initialize" }
                audioTrackFormat = format
            }
    }

    private fun releaseAudioLocked() {
        releaseTrackLocked()
        decoder?.release()
        decoder = null
    }

    private fun releaseTrackLocked() {
        audioTrack?.apply {
            runCatching { pause() }
            runCatching { flush() }
            runCatching { release() }
        }
        audioTrack = null
        audioTrackFormat = null
    }

    private fun outputFormatForPcm(format: StreamFormat): AudioOutputFormat =
        AudioOutputFormat(
            sampleRate = format.sampleRate,
            channels = format.channels,
            encoding = encodingForBitDepth(format.bitDepth),
        )

    companion object {
        private const val TAG = "SendspinAudioPlayer"
        private const val CODEC_PCM = "pcm"
        private const val CODEC_FLAC = "flac"
        private const val CODEC_OPUS = "opus"
        private const val IDLE_DELAY_MS = 10L
        private const val UNDERRUN_DELAY_MS = 5L
        private const val READY_RETRY_DELAY_MS = 1L
        private const val WRITE_RETRY_DELAY_MS = 1L
        private const val MAX_WAIT_DELAY_MS = 10L
        private const val READY_WINDOW_US = 2_000L
        private const val TARGET_BUFFER_MS = 120
        private const val START_THRESHOLD_MS = 20
        private val CHANNEL_COUNTS = intArrayOf(2, 1)
        private val LOSSLESS_SAMPLE_RATES = intArrayOf(48_000, 44_100)
        private val FLAC_BIT_DEPTHS = intArrayOf(24, 16)
        private val OPUS_SAMPLE_RATES = intArrayOf(48_000, 24_000, 16_000, 12_000, 8_000)

        fun supportedFormats(): List<SendspinAudioFormat> =
            buildList {
                for (channels in CHANNEL_COUNTS) {
                    for (sampleRate in LOSSLESS_SAMPLE_RATES) {
                        if (!canPlayPcm(sampleRate, channels, AudioFormat.ENCODING_PCM_16BIT) ||
                            !SendspinMediaCodecAudioDecoder.canDecode(CODEC_FLAC, sampleRate, channels)
                        ) {
                            continue
                        }
                        for (bitDepth in FLAC_BIT_DEPTHS) {
                            add(SendspinAudioFormat(CODEC_FLAC, channels, sampleRate, bitDepth))
                        }
                    }
                }
                for (channels in CHANNEL_COUNTS) {
                    for (sampleRate in OPUS_SAMPLE_RATES) {
                        if (canPlayPcm(sampleRate, channels, AudioFormat.ENCODING_PCM_16BIT) &&
                            SendspinMediaCodecAudioDecoder.canDecode(CODEC_OPUS, sampleRate, channels)
                        ) {
                            add(SendspinAudioFormat(CODEC_OPUS, channels, sampleRate, bitDepth = 16))
                        }
                    }
                }
                for (channels in CHANNEL_COUNTS) {
                    for (sampleRate in LOSSLESS_SAMPLE_RATES) {
                        if (canPlayPcm(sampleRate, channels, AudioFormat.ENCODING_PCM_16BIT)) {
                            add(SendspinAudioFormat(CODEC_PCM, channels, sampleRate, bitDepth = 16))
                        }
                    }
                }
            }

        private fun canPlayPcm(sampleRate: Int, channels: Int, encoding: Int): Boolean =
            AudioTrack.getMinBufferSize(sampleRate, channelMaskFor(channels), encoding) > 0

        private fun encodingForBitDepth(bitDepth: Int): Int =
            when (bitDepth) {
                16 -> AudioFormat.ENCODING_PCM_16BIT
                24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
                32 -> AudioFormat.ENCODING_PCM_32BIT
                else -> error("unsupported PCM bit depth: $bitDepth")
            }

        private fun channelMaskFor(channels: Int): Int =
            when (channels) {
                1 -> AudioFormat.CHANNEL_OUT_MONO
                2 -> AudioFormat.CHANNEL_OUT_STEREO
                else -> error("unsupported channel count: $channels")
            }
    }
}
