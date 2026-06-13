package dev.jdtech.jellyfin.sendspin.receiver.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
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

    /**
     * Monotonic count of PCM bytes handed to the AudioTrack. Unlike
     * [pcmBytesWritten] it is never reset on flush/track-rebuild, so the
     * service's stall watchdog can detect "server says PLAYING but nothing is
     * being rendered" by watching it for forward progress.
     */
    @Volatile var totalBytesRendered: Long = 0L
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private var audioTrack: AudioTrack? = null
    private var audioTrackFormat: AudioOutputFormat? = null
    private var decoder: SendspinMediaCodecAudioDecoder? = null
    private var streamFormat: StreamFormat? = null
    private var pumpJob: Job? = null
    private var volume = 1.0f

    // Output-latency compensation. The AudioBuffer schedules each chunk's
    // *write* time as `toLocal(serverTimestamp) - staticDelayMicros`, but the
    // sound isn't actually emitted until the AudioTrack + HAL output latency
    // later — so without compensation this device lags every other player in a
    // sync group by that latency. We measure the real write→emit latency from
    // AudioTrack.getTimestamp() and feed it back as staticDelayMicros (positive
    // = render earlier), so the audio lands on the shared timeline.
    @Volatile private var pcmBytesWritten: Long = 0L
    private var lastLatencyUpdateMs: Long = 0L
    private val audioTimestamp = AudioTimestamp()

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
            // flush() rewinds the AudioTrack's frame position; keep the
            // written-frames counter in step and re-measure latency promptly.
            pcmBytesWritten = 0L
            lastLatencyUpdateMs = 0L
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

                maybeUpdateOutputLatency()

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
                synchronized(lock) {
                    releaseTrackLocked()
                }
                return
            }
            if (written == 0) {
                delay(WRITE_RETRY_DELAY_MS)
                continue
            }
            offset += written
            pcmBytesWritten += written
            totalBytesRendered += written
        }
    }

    /**
     * Periodically measure the AudioTrack write→emit latency and publish it as
     * the buffer's static delay so this device stays aligned with the rest of a
     * sync group. Cheap (a [AudioTrack.getTimestamp] call at most every
     * [LATENCY_UPDATE_INTERVAL_MS]); a bad/absent reading just leaves the delay
     * unchanged, so it can never glitch the audio path.
     */
    private fun maybeUpdateOutputLatency() {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastLatencyUpdateMs < LATENCY_UPDATE_INTERVAL_MS) return
        lastLatencyUpdateMs = nowMs
        synchronized(lock) {
            val track = audioTrack ?: return
            val format = audioTrackFormat ?: return
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) return
            if (!track.getTimestamp(audioTimestamp)) return
            val framesWritten = pcmBytesWritten / format.bytesPerFrame
            val elapsedNanos = (System.nanoTime() - audioTimestamp.nanoTime).coerceAtLeast(0L)
            val framesPresented = audioTimestamp.framePosition +
                elapsedNanos * format.sampleRate / 1_000_000_000L
            val latencyFrames = (framesWritten - framesPresented).coerceAtLeast(0L)
            val latencyUs = (latencyFrames * 1_000_000L / format.sampleRate)
                .coerceIn(0L, MAX_OUTPUT_LATENCY_US)
            // EWMA so a single noisy reading doesn't yank the timeline.
            val prev = buffer.staticDelayMicros
            buffer.staticDelayMicros = if (prev == 0L) latencyUs else (prev * 3 + latencyUs) / 4
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
                // framePosition restarts with a fresh track, so the matching
                // written-frames counter must restart too.
                pcmBytesWritten = 0L
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
        private const val LATENCY_UPDATE_INTERVAL_MS = 500L
        // Clamp the compensation below the buffered lead so advancing the render
        // time can't starve the buffer into underruns. Real speaker/wired output
        // latency sits well under this.
        private const val MAX_OUTPUT_LATENCY_US = 100_000L
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
