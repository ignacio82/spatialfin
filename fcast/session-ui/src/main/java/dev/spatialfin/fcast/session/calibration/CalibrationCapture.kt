package dev.spatialfin.fcast.session.calibration

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * One-shot mic capture for the calibration wizard. Reads a fixed-duration mono 16-bit PCM
 * buffer and returns it along with the `CLOCK_BOOTTIME` anchor of the first sample (used by the
 * orchestrator's latency math).
 */
class CalibrationCapture(
    private val sampleRateHz: Int = ChirpGenerator.SAMPLE_RATE_HZ,
    private val durationSeconds: Double = ChirpGenerator.totalSeconds + EXTRA_TAIL_SECONDS,
    private val preferredInputDevice: AudioDeviceInfo? = null,
    private val audioManager: AudioManager? = null,
) {

    data class Result(
        val samples: ShortArray,
        /** Wall-clock time the AudioRecord was started — anchor for onset latency math. */
        val captureStartWallMs: Long,
        /** `CLOCK_BOOTTIME` (`SystemClock.elapsedRealtime()`) time of the first captured sample. */
        val captureStartMonotonicMs: Long,
        /** True when [captureStartMonotonicMs] came from AudioRecord.getTimestamp(TIMEBASE_BOOTTIME). */
        val usedAudioRecordTimestamp: Boolean,
        /** True when [MediaRecorder.AudioSource.UNPROCESSED] was supported and selected. */
        val usedUnprocessedSource: Boolean = false,
        /** Microsecond-precision `CLOCK_BOOTTIME` anchor of sample 0 (`AudioTimestamp.nanoTime / 1_000`). */
        val captureStartBoottimeUs: Long = captureStartMonotonicMs * 1_000L,
    )

    /**
     * Block until [durationSeconds] of audio have been captured. Caller must hold RECORD_AUDIO
     * permission; throws on AudioRecord init failure or negative USB read error.
     */
    @SuppressLint("MissingPermission")
    suspend fun capture(): Result = withContext(Dispatchers.IO) {
        val totalSamples = (sampleRateHz * durationSeconds).toInt()
        val minBufferBytes = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(MIN_BUFFER_BYTES)

        val useUnprocessed = audioManager != null && supportsUnprocessedAudioSource(audioManager)
        val audioSource = if (useUnprocessed) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.MIC
        }

        val record = AudioRecord(
            audioSource,
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferBytes,
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord init failed (state=${record.state})"
        }
        preferredInputDevice?.let { device ->
            record.setPreferredDevice(device)
        }
        val buffer = ShortArray(totalSamples)
        val startMs: Long
        val startBoottimeUs: Long
        var firstSampleBoottimeUs: Long? = null
        try {
            record.startRecording()
            startMs = System.currentTimeMillis()
            startBoottimeUs = runCatching { SystemClock.elapsedRealtimeNanos() / 1_000L }
                .getOrElse { SystemClock.elapsedRealtime() * 1_000L }
            var read = 0
            while (read < totalSamples) {
                val remaining = totalSamples - read
                val n = record.read(buffer, read, remaining, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    throw IllegalStateException("AudioRecord.read() failed with error code $n")
                }
                if (n == 0) {
                    delay(10)
                    continue
                }
                read += n
                if (firstSampleBoottimeUs == null) {
                    firstSampleBoottimeUs = readFirstSampleBoottimeUs(record, sampleRateHz)
                }
            }
        } finally {
            try { record.stop() } catch (_: Exception) {}
            try { record.release() } catch (_: Exception) {}
        }
        val anchorBoottimeUs = firstSampleBoottimeUs ?: startBoottimeUs
        Result(
            samples = buffer,
            captureStartWallMs = startMs,
            captureStartMonotonicMs = anchorBoottimeUs / 1_000L,
            usedAudioRecordTimestamp = firstSampleBoottimeUs != null,
            usedUnprocessedSource = useUnprocessed,
            captureStartBoottimeUs = anchorBoottimeUs,
        )
    }

    private fun readFirstSampleBoottimeUs(record: AudioRecord, sampleRateHz: Int): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        val ts = AudioTimestamp()
        val result = record.getTimestamp(ts, TIMESTAMP_TIMEBASE)
        if (result != AudioRecord.SUCCESS) return null
        return firstSampleBoottimeUs(
            timestampFramePosition = ts.framePosition,
            timestampNanoTime = ts.nanoTime,
            sampleRateHz = sampleRateHz,
        )
    }

    companion object {
        /** Extra capture window past the chirp pattern to absorb variable AVR latency. */
        const val EXTRA_TAIL_SECONDS: Double = 1.5

        /**
         * Canonical AudioTimestamp timebase matching [SystemClock.elapsedRealtime] (`CLOCK_BOOTTIME`).
         * Mixing `TIMEBASE_MONOTONIC` with `elapsedRealtime()` drifts by total suspend duration.
         */
        const val TIMESTAMP_TIMEBASE: Int = AudioTimestamp.TIMEBASE_BOOTTIME

        /** Floor on the AudioRecord internal buffer — some devices report tiny min sizes. */
        private const val MIN_BUFFER_BYTES: Int = 8 * 1024

        fun supportsUnprocessedAudioSource(audioManager: AudioManager?): Boolean =
            audioManager?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
                ?.equals("true", ignoreCase = true) == true

        fun firstSampleBoottimeUs(
            timestampFramePosition: Long,
            timestampNanoTime: Long,
            sampleRateHz: Int,
        ): Long {
            val frameOffsetUs = timestampFramePosition * 1_000_000L / sampleRateHz.coerceAtLeast(1)
            return (timestampNanoTime / 1_000L) - frameOffsetUs
        }

        fun firstSampleMonotonicMs(
            timestampFramePosition: Long,
            timestampNanoTime: Long,
            sampleRateHz: Int,
        ): Long = firstSampleBoottimeUs(timestampFramePosition, timestampNanoTime, sampleRateHz) / 1_000L
    }
}
