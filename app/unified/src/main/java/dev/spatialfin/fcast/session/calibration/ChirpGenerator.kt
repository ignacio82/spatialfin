package dev.spatialfin.fcast.session.calibration

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure generator for the audio-latency calibration tone. Produces a 16-bit PCM mono buffer at
 * [SAMPLE_RATE_HZ] containing:
 *
 *  1. [LEAD_SILENCE_MS] of silence (lets the receiver's ExoPlayer reach steady state before
 *     anything we care about plays — buffer fill, codec init, ramp-up are all hidden here).
 *  2. [CHIRP_COUNT] linear-sweep chirps at [CHIRP_DURATION_MS] each, separated by
 *     [CHIRP_GAP_MS] of silence. The sweep spans [CHIRP_FREQ_LOW_HZ]..[CHIRP_FREQ_HIGH_HZ] —
 *     chosen to clear typical AVR/soundbar bass roll-off and tweeter distortion.
 *  3. [TAIL_SILENCE_MS] of silence so we can capture the last chirp's reverb tail without
 *     truncating it.
 *
 * The chirps are amplitude-modulated by a Hann window so they don't click at the start or end
 * (clicks broaden the cross-correlation peak and hurt timing accuracy).
 *
 * Why generate at runtime instead of bundling: the chirp is fully determined by the constants
 * here, so a dedicated audio asset would just be a 200KB git-checked-in copy of `generate(...)`.
 * We compute it once on first calibration and cache the bytes in `filesDir` for subsequent runs.
 *
 * The companion object exposes the file positions of each chirp's onset so the correlator can
 * report per-chirp arrival times.
 */
object ChirpGenerator {

    const val SAMPLE_RATE_HZ: Int = 48_000
    const val CHANNELS: Int = 1
    const val BITS_PER_SAMPLE: Int = 16

    const val LEAD_SILENCE_MS: Int = 1_000
    const val CHIRP_DURATION_MS: Int = 100
    const val CHIRP_GAP_MS: Int = 500
    const val TAIL_SILENCE_MS: Int = 500
    const val CHIRP_COUNT: Int = 5

    const val CHIRP_FREQ_LOW_HZ: Double = 1_000.0
    const val CHIRP_FREQ_HIGH_HZ: Double = 4_000.0

    /** Peak amplitude (0..1). Loud enough to capture cleanly, soft enough not to clip the AVR. */
    const val AMPLITUDE: Double = 0.5

    /** Total length of the produced PCM buffer in samples (per channel). */
    val totalSamples: Int =
        msToSamples(LEAD_SILENCE_MS) +
            CHIRP_COUNT * (msToSamples(CHIRP_DURATION_MS) + msToSamples(CHIRP_GAP_MS)) +
            msToSamples(TAIL_SILENCE_MS)

    /** Total length of the produced PCM buffer in seconds. */
    val totalSeconds: Double = totalSamples.toDouble() / SAMPLE_RATE_HZ

    /**
     * Onset position of each chirp (in seconds from the start of the file). Used by the
     * orchestrator to correlate captured arrival times with TV's reported playback position.
     */
    val chirpOnsetsSeconds: List<Double> = (0 until CHIRP_COUNT).map { i ->
        val onsetSamples = msToSamples(LEAD_SILENCE_MS) +
            i * (msToSamples(CHIRP_DURATION_MS) + msToSamples(CHIRP_GAP_MS))
        onsetSamples.toDouble() / SAMPLE_RATE_HZ
    }

    /** Generate the full PCM buffer as little-endian signed 16-bit samples. */
    fun generatePcm(): ShortArray {
        val out = ShortArray(totalSamples)
        var cursor = msToSamples(LEAD_SILENCE_MS)
        val chirpSamples = msToSamples(CHIRP_DURATION_MS)
        val gapSamples = msToSamples(CHIRP_GAP_MS)
        repeat(CHIRP_COUNT) {
            writeChirpInto(out, cursor, chirpSamples)
            cursor += chirpSamples + gapSamples
        }
        return out
    }

    /** Standalone chirp template (single chirp, Hann-windowed). Used as the correlation target. */
    fun chirpTemplate(): ShortArray {
        val n = msToSamples(CHIRP_DURATION_MS)
        val out = ShortArray(n)
        writeChirpInto(out, 0, n)
        return out
    }

    private fun writeChirpInto(buffer: ShortArray, offset: Int, n: Int) {
        // Linear frequency sweep from f0 to f1 over n samples.
        // Phase = 2π * (f0*t + (f1 - f0)/(2T) * t^2), where T is the duration in seconds.
        val durationS = n.toDouble() / SAMPLE_RATE_HZ
        val k = (CHIRP_FREQ_HIGH_HZ - CHIRP_FREQ_LOW_HZ) / durationS
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE_HZ
            val phase = 2.0 * PI * (CHIRP_FREQ_LOW_HZ * t + 0.5 * k * t * t)
            // Hann window: 0.5 * (1 - cos(2π i / (n-1))). Avoids start/end discontinuity clicks.
            val hann = 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1).coerceAtLeast(1)))
            val sample = (sin(phase) * hann * AMPLITUDE * Short.MAX_VALUE).toInt()
            buffer[offset + i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }

    private fun msToSamples(ms: Int): Int = (SAMPLE_RATE_HZ * ms) / 1_000
}
