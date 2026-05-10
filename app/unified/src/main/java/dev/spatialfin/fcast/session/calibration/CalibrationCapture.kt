package dev.spatialfin.fcast.session.calibration

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * One-shot mic capture for the calibration wizard. Reads a fixed-duration mono 16-bit PCM
 * buffer from [MediaRecorder.AudioSource.MIC] and returns it along with the wall-clock time
 * the first sample was captured (used by the orchestrator's latency math).
 *
 * Why a dedicated class vs. reusing `SpatialVoiceService`: the voice service is tuned for VAD
 * + recognizer hand-off, has its own session state, and would conflict with our capture. For
 * a 5-second one-shot, raw [AudioRecord] is simpler and cheaper.
 */
class CalibrationCapture(
    private val sampleRateHz: Int = ChirpGenerator.SAMPLE_RATE_HZ,
    private val durationSeconds: Double = ChirpGenerator.totalSeconds + EXTRA_TAIL_SECONDS,
) {

    data class Result(
        val samples: ShortArray,
        /** Wall-clock time the AudioRecord was started — anchor for onset latency math. */
        val captureStartWallMs: Long,
    )

    /**
     * Block until [durationSeconds] of audio have been captured. Caller must hold RECORD_AUDIO
     * permission; this throws if construction fails.
     */
    @SuppressLint("MissingPermission")
    suspend fun capture(): Result = withContext(Dispatchers.IO) {
        val totalSamples = (sampleRateHz * durationSeconds).toInt()
        val minBufferBytes = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(MIN_BUFFER_BYTES)

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferBytes,
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord init failed (state=${record.state})"
        }
        val buffer = ShortArray(totalSamples)
        val startMs: Long
        try {
            record.startRecording()
            startMs = System.currentTimeMillis()
            // Tight read loop — we want every available sample without lossy fallbacks.
            var read = 0
            while (read < totalSamples) {
                val remaining = totalSamples - read
                val n = record.read(buffer, read, remaining, AudioRecord.READ_BLOCKING)
                if (n <= 0) {
                    delay(10)
                    continue
                }
                read += n
            }
        } finally {
            try { record.stop() } catch (_: Exception) {}
            try { record.release() } catch (_: Exception) {}
        }
        Result(buffer, startMs)
    }

    companion object {
        /** Extra capture window past the chirp pattern to absorb variable AVR latency. */
        const val EXTRA_TAIL_SECONDS: Double = 1.5

        /** Floor on the AudioRecord internal buffer — some devices report tiny min sizes. */
        private const val MIN_BUFFER_BYTES: Int = 8 * 1024
    }
}
