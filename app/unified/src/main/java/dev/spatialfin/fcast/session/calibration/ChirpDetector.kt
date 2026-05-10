package dev.spatialfin.fcast.session.calibration

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Pure onset detector for the calibration chirps. Given a captured PCM mono 16-bit buffer,
 * it finds the time of each chirp's leading edge using a rolling-RMS / threshold-against-noise
 * approach.
 *
 * Why not full cross-correlation: we only need ~5–10 ms accuracy on each onset, and the chirp
 * is loud relative to a quiet-room noise floor. Rolling RMS is far cheaper than FFT-based
 * convolution and easier to validate. If we ever need sub-millisecond accuracy we can swap in
 * a matched filter without changing the orchestrator's interface.
 *
 * Algorithm:
 *  1. Compute RMS over fixed-size windows that hop by half their length (50% overlap).
 *  2. The median window RMS is the noise-floor estimate.
 *  3. A window is "loud" when its RMS is above [thresholdMultiplier]× the noise floor.
 *  4. Group runs of consecutive loud windows → events.
 *  5. The event's start sample is the first loud window's left edge.
 *
 * If fewer than [expectedCount] events are detected the result is incomplete; the orchestrator
 * decides whether to retry or surface a calibration failure.
 */
object ChirpDetector {

    const val WINDOW_MS: Int = 10
    const val HOP_MS: Int = 5

    /** Default threshold over the median RMS for a window to be considered "loud". */
    const val DEFAULT_THRESHOLD_MULTIPLIER: Double = 8.0

    /** Lower bound on the threshold so a perfectly silent capture doesn't degenerate. */
    const val MIN_ABSOLUTE_THRESHOLD: Double = 200.0  // raw 16-bit RMS units

    data class Onset(
        /** Time of chirp leading edge in seconds, relative to the start of the buffer. */
        val timeSeconds: Double,
        /** Peak RMS within the event divided by the noise floor. Higher = stronger detection. */
        val snr: Double,
    )

    data class Result(
        val onsets: List<Onset>,
        val noiseFloor: Double,
        val threshold: Double,
    ) {
        val complete: Boolean get() = onsets.isNotEmpty()
    }

    fun detect(
        samples: ShortArray,
        sampleRateHz: Int,
        thresholdMultiplier: Double = DEFAULT_THRESHOLD_MULTIPLIER,
    ): Result {
        val windowSize = sampleRateHz * WINDOW_MS / 1_000
        val hopSize = sampleRateHz * HOP_MS / 1_000
        if (samples.size < windowSize) {
            return Result(emptyList(), 0.0, MIN_ABSOLUTE_THRESHOLD)
        }

        val rms = DoubleArray((samples.size - windowSize) / hopSize + 1)
        for (i in rms.indices) {
            val start = i * hopSize
            val end = start + windowSize
            var sumSq = 0.0
            for (j in start until end) {
                val v = samples[j].toDouble()
                sumSq += v * v
            }
            rms[i] = sqrt(sumSq / windowSize)
        }

        val noiseFloor = median(rms)
        val threshold = max(noiseFloor * thresholdMultiplier, MIN_ABSOLUTE_THRESHOLD)

        val onsets = mutableListOf<Onset>()
        var inEvent = false
        var eventStartIdx = -1
        var eventPeakRms = 0.0
        for (i in rms.indices) {
            val loud = rms[i] >= threshold
            if (loud && !inEvent) {
                inEvent = true
                eventStartIdx = i
                eventPeakRms = rms[i]
            } else if (loud && inEvent) {
                if (rms[i] > eventPeakRms) eventPeakRms = rms[i]
            } else if (!loud && inEvent) {
                val sampleIdx = eventStartIdx * hopSize
                onsets += Onset(
                    timeSeconds = sampleIdx.toDouble() / sampleRateHz,
                    snr = if (noiseFloor > 0) eventPeakRms / noiseFloor else Double.POSITIVE_INFINITY,
                )
                inEvent = false
                eventStartIdx = -1
                eventPeakRms = 0.0
            }
        }
        if (inEvent && eventStartIdx >= 0) {
            val sampleIdx = eventStartIdx * hopSize
            onsets += Onset(
                timeSeconds = sampleIdx.toDouble() / sampleRateHz,
                snr = if (noiseFloor > 0) eventPeakRms / noiseFloor else Double.POSITIVE_INFINITY,
            )
        }

        return Result(onsets, noiseFloor, threshold)
    }

    /**
     * Compute the median latency from observed onsets matched against expected file positions.
     * The orchestrator already knows when (in XR-wall time) it sent the Play command, when the
     * TV's first PlaybackUpdate beacon arrived, and the [chirpOnsetsSeconds] from
     * [ChirpGenerator]. From those plus the detected mic onsets (in seconds from capture start),
     * the per-chirp audio latency is:
     *
     * ```
     * latencyMs[i] = (capturedTimeMs[i] - captureStartXrWallMs) +
     *                (firstBeaconReceiveXrWallMs - firstBeaconStreamPosMs * 1000)
     *              - (chirpOnsetsSeconds[i] * 1000)
     * ```
     *
     * The orchestrator computes that per chirp and reduces with this median.
     */
    fun medianLatencyMs(perChirpLatenciesMs: List<Long>): Long {
        require(perChirpLatenciesMs.isNotEmpty())
        val sorted = perChirpLatenciesMs.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2
        }
    }

    private fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val copy = values.copyOf().also { it.sort() }
        val mid = copy.size / 2
        return if (copy.size % 2 == 1) copy[mid] else (copy[mid - 1] + copy[mid]) / 2.0
    }
}
