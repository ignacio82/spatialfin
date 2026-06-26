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

    /**
     * Matched-filter onset detection: normalized cross-correlation of the capture against the
     * known chirp [template]. More accurate than rolling-RMS for onset timing and far more
     * robust to room echo and broadband noise — the correlation only spikes where the *swept*
     * waveform actually aligns, so a reflection (delayed, filtered copy) produces a smaller,
     * later peak that the spacing/grouping rejects, and stationary HVAC/fan noise correlates
     * near zero with a 1–4 kHz sweep.
     *
     * The lag axis is strided by [lagStrideMs] (default 1 ms) so cost stays ~`N/stride · M`
     * instead of `N · M`; 1 ms easily clears the ~5 ms accuracy the orchestrator needs.
     *
     * Returns the same [Result] shape as [detect] so it's a drop-in: the orchestrator prefers
     * this and only falls back to [detect] if it under-detects, so a correlator regression can
     * never do worse than the previous RMS behaviour.
     */
    fun detectMatched(
        samples: ShortArray,
        sampleRateHz: Int,
        template: ShortArray,
        thresholdMultiplier: Double = DEFAULT_THRESHOLD_MULTIPLIER,
        lagStrideMs: Int = 1,
        minSpacingMs: Int = DEFAULT_MIN_SPACING_MS,
    ): Result {
        val m = template.size
        if (m == 0 || samples.size < m) return Result(emptyList(), 0.0, 0.0)
        val stride = (sampleRateHz * lagStrideMs / 1_000).coerceAtLeast(1)

        var tplEnergy = 0.0
        for (v in template) tplEnergy += v.toDouble() * v.toDouble()
        val tplNorm = sqrt(tplEnergy)
        if (tplNorm == 0.0) return Result(emptyList(), 0.0, 0.0)

        val lastLag = samples.size - m
        val corr = DoubleArray(lastLag / stride + 1)
        for (idx in corr.indices) {
            val lag = idx * stride
            var dot = 0.0
            var sigEnergy = 0.0
            for (j in 0 until m) {
                val s = samples[lag + j].toDouble()
                dot += s * template[j].toDouble()
                sigEnergy += s * s
            }
            val denom = sqrt(sigEnergy) * tplNorm
            corr[idx] = if (denom > 0.0) (dot / denom) else 0.0
        }

        // Noise floor = median |corr|; an event is a run of lags whose correlation clears the
        // threshold, and the onset is the *peak* lag within that run (sub-sampled to `stride`).
        val absCorr = DoubleArray(corr.size) { kotlin.math.abs(corr[it]) }
        val noiseFloor = median(absCorr)
        val threshold = (noiseFloor * thresholdMultiplier).coerceIn(MIN_CORRELATION, 0.95)

        // Candidate peaks: every above-threshold local maximum of the correlation curve. Then
        // greedily keep the strongest and reject anything within minSpacing of an already-kept
        // peak. This is what kills room echo: a reflection's peak is later *and* weaker than
        // the direct path, so it's always discarded in favour of the true onset, while genuine
        // chirps are spaced far enough apart (chirp gap) to survive.
        data class Peak(val idx: Int, val v: Double)
        val candidates = ArrayList<Peak>()
        for (i in corr.indices) {
            val c = absCorr[i]
            if (c < threshold) continue
            val leftOk = i == 0 || absCorr[i - 1] <= c
            val rightOk = i == corr.lastIndex || absCorr[i + 1] < c
            if (leftOk && rightOk) candidates += Peak(i, c)
        }
        val minSpacingIdx = ((sampleRateHz * minSpacingMs / 1_000) / stride).coerceAtLeast(1)
        val kept = ArrayList<Peak>()
        for (p in candidates.sortedByDescending { it.v }) {
            if (kept.none { kotlin.math.abs(it.idx - p.idx) < minSpacingIdx }) kept += p
        }
        val onsets = kept.sortedBy { it.idx }.map { p ->
            Onset(
                timeSeconds = (p.idx * stride).toDouble() / sampleRateHz,
                snr = if (noiseFloor > 0) p.v / noiseFloor else Double.POSITIVE_INFINITY,
            )
        }
        return Result(onsets, noiseFloor, threshold)
    }

    /** Lower bound on the correlation threshold so a noiseless capture can't degenerate to 0. */
    const val MIN_CORRELATION: Double = 0.30

    /** Reject any matched peak within this of a stronger one — suppresses room echo while
     *  staying well under the inter-chirp gap so genuine chirps are never merged. */
    const val DEFAULT_MIN_SPACING_MS: Int = 250

    private fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val copy = values.copyOf().also { it.sort() }
        val mid = copy.size / 2
        return if (copy.size % 2 == 1) copy[mid] else (copy[mid - 1] + copy[mid]) / 2.0
    }
}
