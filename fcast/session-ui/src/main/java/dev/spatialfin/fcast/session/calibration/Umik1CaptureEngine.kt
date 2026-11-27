package dev.spatialfin.fcast.session.calibration

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.CalibrationSweepStartResponse
import dev.jdtech.jellyfin.core.audio.VoiceCaptureSuspensionRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Pure-HTTP Tier 2 USB-C OTG microphone (`UMIK-1`) capture engine.
 *
 * Key invariants from Spec 1:
 *  - Acquires [VoiceCaptureSuspensionRegistry] before opening `AudioRecord`.
 *  - Verifies `>= 64 MiB` free cache space before recording and cleans up temporary WAV files on
 *    error or abort (`caller` or `recordSweep` deletes after upload).
 *  - Pre-opens and starts `AudioRecord` (`ENCODING_PCM_FLOAT` -> `ENCODING_PCM_24BIT_PACKED` ->
 *    `ENCODING_PCM_32BIT`) BEFORE `POST /api/v1/calibration/sweeps` so USB Audio HAL startup
 *    latency never clips `T_0`.
 *  - Computes capture duration strictly from the server's relative sweep span
 *    `(tend_ref_avr_us - t0_ref_avr_us) + safetyLeadUs + 1_500_000 µs` on Android's
 *    `CLOCK_BOOTTIME`.
 *  - Streams directly into a 24-bit/48 kHz WAV file on disk (`WavWriter.StreamingWav24Writer`)
 *    and aborts cleanly via [onAbortSweep] if USB disconnects (`AudioDeviceCallback`),
 *    `AudioRecord.read()` returns `< 0`, or the coroutine is cancelled.
 */
class Umik1CaptureEngine(
    private val context: Context,
    private val clockBoottimeUs: () -> Long = { SystemClock.elapsedRealtimeNanos() / 1_000L },
) {

    data class UsbMicDescriptor(
        val id: Int,
        val productName: String,
        val isUmik: Boolean,
        val deviceInfo: AudioDeviceInfo? = null,
    )

    data class CaptureOutcome(
        val wavFile: File,
        val sampleRateHz: Int,
        val samplesWritten: Long,
        val peakDbfs: Float,
        val usedUnprocessedSource: Boolean,
        val encodingUsed: Int,
    )

    interface AudioCaptureBackend {
        val sampleRateHz: Int
        val encoding: Int
        val usedUnprocessedSource: Boolean
        fun startRecording()
        fun readFloat(buffer: FloatArray, offset: Int, size: Int): Int
        fun stopAndRelease()
    }

    private val _livePeakDbfs = MutableStateFlow(-96f)
    val livePeakDbfs: StateFlow<Float> = _livePeakDbfs.asStateFlow()

    fun listUsbMicrophones(): List<UsbMicDescriptor> {
        val am = runCatching { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }.getOrNull() ?: return emptyList()
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return inputs
            .filter {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            .map { dev ->
                val name = dev.productName?.toString()?.trim().orEmpty().ifBlank { "USB Microphone" }
                UsbMicDescriptor(
                    id = dev.id,
                    productName = name,
                    isUmik = name.contains("UMIK", ignoreCase = true),
                    deviceInfo = dev,
                )
            }
            .sortedByDescending { it.isUmik }
    }

    /**
     * Streams live peak dBFS readings for the SPL headroom check screen until cancelled.
     */
    @SuppressLint("MissingPermission")
    suspend fun monitorLiveHeadroom(
        preferredDevice: AudioDeviceInfo?,
        sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    ) = withContext(Dispatchers.IO) {
        val lease = VoiceCaptureSuspensionRegistry.acquire("umik1_spl_meter")
        val backend = openAndroidBackend(sampleRateHz, preferredDevice)
        try {
            backend.startRecording()
            val chunk = FloatArray(1024)
            while (currentCoroutineContext().isActive) {
                val n = backend.readFloat(chunk, 0, chunk.size)
                if (n < 0) {
                    throw IllegalStateException("USB microphone disconnected during SPL check (err=$n)")
                }
                if (n > 0) {
                    _livePeakDbfs.value = WavWriter.computePeakDbfs(chunk, n)
                }
            }
        } finally {
            runCatching { backend.stopAndRelease() }
            _livePeakDbfs.value = -96f
            lease.release()
        }
    }

    /**
     * Pre-opens the USB mic, triggers [startServerSweep] once recording is live, streams 24-bit
     * WAV to disk across the computed relative sweep span, invokes [onCapturedWavReady] to upload,
     * and guarantees temporary file deletion in `finally`.
     */
    suspend fun <T> recordAndProcessSweep(
        preferredDevice: AudioDeviceInfo?,
        sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
        cacheDir: File = context.cacheDir,
        backendFactory: () -> AudioCaptureBackend = { openAndroidBackend(sampleRateHz, preferredDevice) },
        startServerSweep: suspend () -> CalibrationSweepStartResponse,
        onAbortSweep: suspend (sweepId: String) -> Unit,
        onCapturedWavReady: suspend (CalibrationSweepStartResponse, CaptureOutcome) -> T,
    ): T = withContext(Dispatchers.IO) {
        require(cacheDir.usableSpace >= REQUIRED_FREE_CACHE_BYTES) {
            "Insufficient cache space for calibration WAV (need at least 64 MiB free)"
        }
        val lease = VoiceCaptureSuspensionRegistry.acquire("umik1_calibration_sweep")
        val wakeLock = acquirePartialWakeLock()
        val usbDisconnected = AtomicBoolean(false)
        val audioManager = if (preferredDevice != null) {
            runCatching { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }.getOrNull()
        } else {
            null
        }
        val deviceCallback = if (preferredDevice != null && audioManager != null) {
            object : AudioDeviceCallback() {
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    if (removedDevices.any { it.id == preferredDevice.id }) {
                        usbDisconnected.set(true)
                    }
                }
            }.also { cb ->
                audioManager.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
            }
        } else {
            null
        }

        val tempFile = File(cacheDir, "theater_sweep_${System.currentTimeMillis()}.wav")
        val backend = backendFactory()
        var startedSweep: CalibrationSweepStartResponse? = null
        var writer: WavWriter.StreamingWav24Writer? = null
        try {
            // 1. Pre-open and start AudioRecord BEFORE requesting server sweep start so USB Audio
            //    HAL warmup cannot clip the leading T_0 reference chirp.
            backend.startRecording()
            val sweep = startServerSweep()
            startedSweep = sweep

            val durationUs = computeCaptureDurationUs(
                t0RefAvrUs = sweep.t0RefAvrUs,
                tendRefAvrUs = sweep.tendRefAvrUs,
            )
            val deadlineUs = clockBoottimeUs() + durationUs
            writer = WavWriter.StreamingWav24Writer(tempFile, backend.sampleRateHz)
            val chunk = FloatArray(READ_CHUNK_FRAMES)
            var overallPeakDbfs = -96f

            while (clockBoottimeUs() < deadlineUs) {
                if (!currentCoroutineContext().isActive) {
                    throw CancellationException("Calibration sweep cancelled by user")
                }
                if (usbDisconnected.get()) {
                    throw IllegalStateException("USB microphone disconnected during sweep")
                }
                val n = backend.readFloat(chunk, 0, chunk.size)
                if (n < 0) {
                    throw IllegalStateException("AudioRecord.read() failed during sweep (code=$n)")
                }
                if (n > 0) {
                    val peak = WavWriter.computePeakDbfs(chunk, n)
                    _livePeakDbfs.value = peak
                    if (peak > overallPeakDbfs) overallPeakDbfs = peak
                    writer.writeFloatSamples(chunk, n)
                }
            }
            writer.close()
            val samplesWritten = writer.samplesWritten
            writer = null

            val outcome = CaptureOutcome(
                wavFile = tempFile,
                sampleRateHz = backend.sampleRateHz,
                samplesWritten = samplesWritten,
                peakDbfs = overallPeakDbfs,
                usedUnprocessedSource = backend.usedUnprocessedSource,
                encodingUsed = backend.encoding,
            )
            onCapturedWavReady(sweep, outcome)
        } catch (e: Throwable) {
            startedSweep?.let { sweep ->
                runCatching { onAbortSweep(sweep.sweepId) }
                    .onFailure { Timber.tag(TAG).w(it, "Failed to abort server sweep %s", sweep.sweepId) }
            }
            throw e
        } finally {
            runCatching { writer?.close() }
            runCatching { backend.stopAndRelease() }
            if (deviceCallback != null && audioManager != null) {
                runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
            }
            runCatching { tempFile.delete() }
            _livePeakDbfs.value = -96f
            runCatching {
                if (wakeLock?.isHeld == true) wakeLock.release()
            }
            lease.release()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openAndroidBackend(
        sampleRateHz: Int,
        preferredDevice: AudioDeviceInfo?,
    ): AudioCaptureBackend {
        val am = runCatching { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }.getOrNull()
        val useUnprocessed = CalibrationCapture.supportsUnprocessedAudioSource(am)
        val source = if (useUnprocessed) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.MIC
        }
        val candidateEncodings = buildList {
            add(AudioFormat.ENCODING_PCM_FLOAT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AudioFormat.ENCODING_PCM_24BIT_PACKED)
                add(AudioFormat.ENCODING_PCM_32BIT)
            }
        }
        var lastError: Throwable? = null
        for (encoding in candidateEncodings) {
            try {
                val minBytes = AudioRecord.getMinBufferSize(
                    sampleRateHz,
                    AudioFormat.CHANNEL_IN_MONO,
                    encoding,
                )
                if (minBytes <= 0) continue
                val format = AudioFormat.Builder()
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(encoding)
                    .build()
                val record = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes((minBytes * 4).coerceAtLeast(32 * 1024))
                    .build()
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    continue
                }
                preferredDevice?.let { record.setPreferredDevice(it) }
                return AndroidAudioRecordBackend(
                    record = record,
                    sampleRateHz = sampleRateHz,
                    encoding = encoding,
                    usedUnprocessedSource = useUnprocessed,
                )
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException(
            "Unable to initialize AudioRecord at ${sampleRateHz} Hz for USB mic",
            lastError,
        )
    }

    private fun acquirePartialWakeLock(): PowerManager.WakeLock? = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpatialFin:TheaterCalibrationSweep")
            .apply { acquire(MAX_WAKE_LOCK_MS) }
    }.getOrNull()

    private class AndroidAudioRecordBackend(
        private val record: AudioRecord,
        override val sampleRateHz: Int,
        override val encoding: Int,
        override val usedUnprocessedSource: Boolean,
    ) : AudioCaptureBackend {
        private var byteScratch = ByteArray(READ_CHUNK_FRAMES * 4)

        override fun startRecording() {
            record.startRecording()
        }

        override fun readFloat(buffer: FloatArray, offset: Int, size: Int): Int {
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                return record.read(buffer, offset, size, AudioRecord.READ_BLOCKING)
            }
            val bytesPerSample = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED
            ) 3 else 4
            val neededBytes = size * bytesPerSample
            if (byteScratch.size < neededBytes) {
                byteScratch = ByteArray(neededBytes)
            }
            val readBytes = record.read(byteScratch, 0, neededBytes, AudioRecord.READ_BLOCKING)
            if (readBytes <= 0) return readBytes
            val sampleCount = readBytes / bytesPerSample
            if (bytesPerSample == 3) {
                for (i in 0 until sampleCount) {
                    val b0 = byteScratch[i * 3].toInt() and 0xFF
                    val b1 = byteScratch[i * 3 + 1].toInt() and 0xFF
                    val b2 = byteScratch[i * 3 + 2].toInt() // sign-extended
                    val signed24 = (b2 shl 16) or (b1 shl 8) or b0
                    buffer[offset + i] = (signed24 / 8_388_608f).coerceIn(-1f, 1f)
                }
            } else {
                for (i in 0 until sampleCount) {
                    val b0 = byteScratch[i * 4].toInt() and 0xFF
                    val b1 = byteScratch[i * 4 + 1].toInt() and 0xFF
                    val b2 = byteScratch[i * 4 + 2].toInt() and 0xFF
                    val b3 = byteScratch[i * 4 + 3].toInt()
                    val signed32 = (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
                    buffer[offset + i] = (signed32 / 2_147_483_648.0).toFloat().coerceIn(-1f, 1f)
                }
            }
            return sampleCount
        }

        override fun stopAndRelease() {
            try { record.stop() } catch (_: Exception) {}
            try { record.release() } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "Umik1CaptureEngine"
        const val DEFAULT_SAMPLE_RATE_HZ: Int = 48_000
        const val REQUIRED_FREE_CACHE_BYTES: Long = 64L * 1024L * 1024L
        const val DEFAULT_SAFETY_LEAD_US: Long = 500_000L
        const val DEFAULT_TAIL_MARGIN_US: Long = 1_500_000L
        private const val READ_CHUNK_FRAMES: Int = 2048
        private const val MAX_WAKE_LOCK_MS: Long = 5L * 60L * 1000L

        /**
         * Pure Spec 1 sweep capture duration formula from relative server AVR timestamps:
         * `(tend_ref_avr_us - t0_ref_avr_us) + safetyLeadUs + tailMarginUs`.
         */
        fun computeCaptureDurationUs(
            t0RefAvrUs: Long,
            tendRefAvrUs: Long,
            safetyLeadUs: Long = DEFAULT_SAFETY_LEAD_US,
            tailMarginUs: Long = DEFAULT_TAIL_MARGIN_US,
        ): Long {
            val spanUs = (tendRefAvrUs - t0RefAvrUs).coerceAtLeast(1_000_000L)
            return spanUs + safetyLeadUs.coerceAtLeast(0L) + tailMarginUs.coerceAtLeast(0L)
        }
    }
}
