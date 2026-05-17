package dev.spatialfin.fcast.session

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Pure, Android-free building blocks the [SplitAvController] feeds raw observations into before
 * asking [SplitAvPolicy] to decide. Split out from `SplitAvPolicy.kt` so the policy stays a
 * single-shot oracle while the *stateful* smoothing/estimation lives in testable classes that
 * mirror the existing [NetworkDelayEstimator] shape.
 */

/**
 * Smooths the raw per-beacon drift signal and estimates its *rate of change*.
 *
 * Two problems this solves:
 *  - Wi-Fi RTT jitter spikes apparent drift by 100–200 ms momentarily (the policy doc itself
 *    calls this out). Acting on each raw sample makes the controller chase noise. An EWMA plus
 *    median-style outlier rejection (same pattern as [NetworkDelayEstimator]) suppresses that.
 *  - Two independent crystal oscillators (XR video clock vs. receiver audio DAC clock) drift
 *    apart at a roughly *constant rate*. Reacting only to instantaneous error means the
 *    controller is always chasing and never predicting. Estimating the slope lets the policy
 *    apply a steady feed-forward trim that nulls the skew instead of sawtoothing around it.
 *
 * Outlier handling matches [NetworkDelayEstimator]: a sample that jumps far beyond the smoothed
 * value is rejected, but [maxConsecutiveOutliers] in a row is treated as a genuine step (a real
 * seek / scene cut / track change) and accepted as the new reality.
 */
class DriftEstimator(
    private val alpha: Double = DEFAULT_ALPHA,
    private val rateAlpha: Double = DEFAULT_RATE_ALPHA,
    private val outlierFactor: Double = DEFAULT_OUTLIER_FACTOR,
    private val maxConsecutiveOutliers: Int = DEFAULT_MAX_CONSECUTIVE_OUTLIERS,
) {
    private var smoothed: Double? = null
    private var rateMsPerSec: Double = 0.0
    private var lastSampleAtMs: Long? = null
    private var consecutiveOutliers: Int = 0
    private var count: Int = 0

    /**
     * Feed a raw drift sample (ms) observed at monotonic time [atMs].
     * Returns true if the sample was accepted (false = rejected as a transient outlier).
     */
    fun record(rawDriftMs: Long, atMs: Long): Boolean {
        val prev = smoothed
        if (prev == null) {
            smoothed = rawDriftMs.toDouble()
            lastSampleAtMs = atMs
            count = 1
            return true
        }
        // Tolerance scales with the current magnitude but has a floor so a baseline near zero
        // doesn't flag every ordinary sample as an outlier.
        val tolerance = max(abs(prev) * (outlierFactor - 1.0), OUTLIER_FLOOR_MS)
        if (abs(rawDriftMs - prev) > tolerance && count > 1) {
            consecutiveOutliers++
            if (consecutiveOutliers <= maxConsecutiveOutliers) return false
            // Persistent jump → real discontinuity. Re-seed rather than crawl toward it.
            smoothed = rawDriftMs.toDouble()
            rateMsPerSec = 0.0
            lastSampleAtMs = atMs
            consecutiveOutliers = 0
            count++
            return true
        }
        consecutiveOutliers = 0
        val dtSec = (atMs - (lastSampleAtMs ?: atMs)).coerceAtLeast(1L).toDouble() / 1000.0
        val newSmoothed = alpha * rawDriftMs + (1 - alpha) * prev
        val instRate = (newSmoothed - prev) / dtSec
        rateMsPerSec = rateAlpha * instRate + (1 - rateAlpha) * rateMsPerSec
        smoothed = newSmoothed
        lastSampleAtMs = atMs
        count++
        return true
    }

    /** Current smoothed drift in ms, or null until the first sample. */
    fun driftMs(): Long? = smoothed?.roundToLong()

    /** Estimated drift growth in ms of error per second of wall time (clock-skew slope). */
    fun driftRateMsPerSec(): Double = rateMsPerSec

    fun sampleCount(): Int = count

    fun reset() {
        smoothed = null
        rateMsPerSec = 0.0
        lastSampleAtMs = null
        consecutiveOutliers = 0
        count = 0
    }

    companion object {
        const val DEFAULT_ALPHA: Double = 0.30
        const val DEFAULT_RATE_ALPHA: Double = 0.20
        const val DEFAULT_OUTLIER_FACTOR: Double = 4.0
        const val DEFAULT_MAX_CONSECUTIVE_OUTLIERS: Int = 3

        /** Absolute slack (ms) added to the outlier tolerance so a near-zero baseline is stable. */
        const val OUTLIER_FLOOR_MS: Double = 120.0
    }
}

/**
 * NTP clock-offset estimator (FCast v4). Fed the four Ping/Pong timestamps; estimates θ such
 * that `receiver_monotonic ≈ sender_monotonic + θ`, so the sender can map a beacon's
 * `monotonicSampleMs` precisely onto its own clock instead of guessing the path delay as
 * `RTT/2`.
 *
 * ```
 * θ = ((t2 − t1) + (t3 − t4)) / 2          // clock offset
 * δ = (t4 − t1) − (t3 − t2)                // round-trip delay
 * ```
 *
 * NTP "clock filter": the θ from the round trip with the **lowest δ** is the least
 * asymmetry-biased, so we keep the minimum-δ sample over a sliding window rather than
 * averaging (averaging blends in the very Wi-Fi asymmetry we're trying to remove). Samples
 * with δ < 0 or absurdly large δ are rejected outright.
 */
class ClockOffsetEstimator(
    private val windowSize: Int = DEFAULT_WINDOW,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
) {
    private data class Sample(val deltaMs: Long, val thetaMs: Long)

    private val recent = ArrayDeque<Sample>()

    /** Feed one NTP four-timestamp set. Returns true if accepted. */
    fun record(t1: Long, t2: Long, t3: Long, t4: Long): Boolean {
        val delta = (t4 - t1) - (t3 - t2)
        if (delta < 0L || delta > maxDelayMs) return false
        val theta = ((t2 - t1) + (t3 - t4)) / 2
        recent.addLast(Sample(delta, theta))
        while (recent.size > windowSize) recent.removeFirst()
        return true
    }

    /** Best θ (ms): the offset from the lowest-delay round trip in the window; null if empty. */
    fun offsetMs(): Int? = recent.minByOrNull { it.deltaMs }?.thetaMs?.toInt()

    fun hasEstimate(): Boolean = recent.isNotEmpty()

    fun reset() = recent.clear()

    companion object {
        const val DEFAULT_WINDOW: Int = 8
        const val DEFAULT_MAX_DELAY_MS: Long = 2_000L
    }
}

/**
 * PI(+feed-forward) speed-trim controller. Produces the ExoPlayer playback-speed multiplier
 * the orchestrator applies *continuously* while the receiver plays — not a transient blip.
 *
 * Why PI and not the old fixed ±1 % bang-bang: two free-running oscillators drift apart at a
 * roughly *constant rate*, which is a constant-rate disturbance on the position error. A pure
 * proportional law settles with a non-zero steady-state error (`drift_ss = rate / (1000·Kp)`)
 * — i.e. it would hold lipsync permanently ~hundreds of ms off. The integral term accumulates
 * until it supplies exactly the standing speed offset that cancels the skew, driving the
 * steady-state error to zero. The feed-forward term on measured drift-rate sharpens the
 * transient. Output is clamped (video micro-rate change is imperceptible since audio is
 * remote, but a visible fast-forward isn't) with matching integrator anti-windup.
 *
 * Deadband ([SplitAvPolicy.DriftAction.Hold]): inside the perceptual band the proportional
 * term is zeroed and the integrator is *frozen* (not reset) — the standing trim that cancels
 * the skew is preserved, but sub-perceptual jitter can't wind the integrator up.
 */
class NudgeController(
    private val kp: Double = DEFAULT_KP,
    private val ki: Double = DEFAULT_KI,
    private val kf: Double = DEFAULT_KF,
    private val maxDeviation: Double = DEFAULT_MAX_DEVIATION,
) {
    /** Accumulated ∫ drift dt in ms·s. */
    private var integral: Double = 0.0
    private val integralClamp: Double = maxDeviation / ki // |Ki·integral| ≤ maxDeviation

    /**
     * @param smoothedDriftMs from [DriftEstimator]; >0 = video ahead ⇒ slow down (<1×)
     * @param driftRateMsPerSec from [DriftEstimator]
     * @param dtMs wall ms since the previous beacon (clamped to a sane range)
     * @param frozen true on [SplitAvPolicy.DriftAction.Hold] — hold the trim, don't integrate
     */
    fun update(
        smoothedDriftMs: Long,
        driftRateMsPerSec: Double,
        dtMs: Long,
        frozen: Boolean,
    ): Float {
        val dtSec = dtMs.coerceIn(1L, 1_000L).toDouble() / 1000.0
        if (!frozen) {
            integral = (integral + smoothedDriftMs * dtSec)
                .coerceIn(-integralClamp, integralClamp)
        }
        val proportional = if (frozen) 0.0 else -kp * smoothedDriftMs
        val integ = -ki * integral
        val feedForward = -kf * driftRateMsPerSec
        val deviation = (proportional + integ + feedForward)
            .coerceIn(-maxDeviation, maxDeviation)
        return (1.0 + deviation).toFloat()
    }

    fun reset() {
        integral = 0.0
    }

    companion object {
        /** Proportional gain: smoothed drift (ms) → speed deviation. ~Hard-seek-boundary drift
         *  maps near full authority for fast transient convergence. */
        const val DEFAULT_KP: Double = 1.6e-4

        /** Integral gain (per ms·s). Sized so a realistic standing skew accumulates to its
         *  cancelling offset within a few seconds without saturating the anti-windup clamp. */
        const val DEFAULT_KI: Double = 8.0e-5

        /** Feed-forward gain: a +R ms/s skew is physically nulled by running −R/1000 off-speed. */
        const val DEFAULT_KF: Double = 1.0e-3

        /** Max speed-multiplier deviation (±). Caps both the output and the integrator. */
        const val DEFAULT_MAX_DEVIATION: Double = 0.08
    }
}

/**
 * Per-codec latency the calibration chirp can't see.
 *
 * Calibration plays a short chirp through the *normal* (usually PCM/AAC) decode path and
 * measures the AVR/soundbar tail. But when the actual stream is a bitstreamed Dolby/DTS
 * format, the AVR runs an *additional* object/spatial decode that adds tens of ms the chirp
 * never exercised. The receiver already tells us, in every beacon, exactly which codec
 * ExoPlayer is decoding ([dev.jdtech.jellyfin.fcast.protocol.AudioFormatInfo.mimeType]), so we
 * add a codec-specific delta on top of the calibrated base — and re-select it for free when
 * the codec changes mid-stream (ad break, episode change, track switch).
 *
 * Values are conservative first estimates; the trace/replay harness ([SplitAvTrace]) is the
 * tool for tuning them against real hardware captures.
 */
object AudioLatencyProfile {
    fun extraLatencyMs(mimeType: String?): Int = when (mimeType?.lowercase()) {
        "audio/eac3-joc" -> 60 // Dolby Atmos (E-AC-3 + Joint Object Coding) object decode
        "audio/true-hd" -> 55 // Dolby TrueHD / TrueHD-Atmos
        "audio/eac3" -> 40 // Dolby Digital Plus
        "audio/ac3" -> 35 // Dolby Digital
        "audio/dts-hd", "audio/dts", "audio/dtsx", "audio/vnd.dts", "audio/vnd.dts.hd" -> 45
        else -> 0 // PCM / AAC / Opus / FLAC ≈ what the calibration chirp already measured
    }
}

/**
 * Rejects beacons that spent an anomalous amount of time in the network/receiver queue before
 * reaching us — their position sample is already stale by the time the policy would act on it.
 *
 * Staleness is judged on the beacon's age in the sender's *monotonic* clock: the orchestrator
 * maps the receiver's monotonic sample stamp into sender time via the continuously-estimated
 * v4 clock offset θ (see [ClockOffsetEstimator]) and passes `senderNow − sampleTimeₛ`. Both
 * endpoints are `elapsedRealtime` (no NTP steps) and θ tracks inter-device crystal drift out,
 * so the age doesn't accumulate skew.
 *
 * An earlier version compared the receiver's *wall* clock (`generationTime`) against the
 * sender's monotonic clock and kept an *all-time* minimum as the reference. That mixed two
 * undisciplined heterogeneous clocks: crystal drift made the gap grow unbounded (every fresh
 * beacon dropped within ~1–2 h of a long movie) and a single receiver NTP backward step
 * dropped *every* beacon for the rest of the session permanently. This version (a) uses the
 * θ-disciplined monotonic age and (b) takes the reference as a *sliding-window* minimum so
 * any residual baseline shift self-heals within [floorWindowMs] instead of forever.
 */
class BeaconFreshnessGate(
    private val staleThresholdMs: Long = DEFAULT_STALE_THRESHOLD_MS,
    private val floorWindowMs: Long = DEFAULT_FLOOR_WINDOW_MS,
) {
    private data class Sample(val atMonotonicMs: Long, val ageMs: Long)

    private val recent = ArrayDeque<Sample>()

    /**
     * @param ageMs beacon age in the *sender's* monotonic clock — `senderNow − (receiver
     *   monotonicSampleMs − θ)`, computed by the orchestrator. Null when no disciplined clock
     *   relationship exists yet (θ unconverged / pre-v4 / non-SpatialFin receiver): we then
     *   can't tell stale from skewed, so accept — the gate is a bonus on top of
     *   [DriftEstimator]'s outlier rejection, never a single point of failure.
     * @param atMonotonicMs sender monotonic clock now; the sliding-window axis.
     * @return true if the beacon is fresh enough to act on.
     */
    fun accept(ageMs: Long?, atMonotonicMs: Long): Boolean {
        if (ageMs == null) return true
        val cutoff = atMonotonicMs - floorWindowMs
        while (recent.isNotEmpty() && recent.first().atMonotonicMs < cutoff) {
            recent.removeFirst()
        }
        // Reference is the *windowed* minimum, not an all-time minimum: even though θ
        // discipline keeps `ageMs` skew-free, a θ re-base or transient still shifts the
        // baseline — a sliding window lets it wash out within [floorWindowMs] instead of
        // permanently biasing the floor and silently dropping every beacon for the rest of a
        // long movie (the all-time-min bug this replaced).
        val floor = recent.minOfOrNull { it.ageMs }
        recent.addLast(Sample(atMonotonicMs, ageMs))
        if (floor == null) return true // first in window — nothing to compare against
        if (ageMs <= floor) return true // a new best is fresh by definition
        return ageMs - floor <= staleThresholdMs
    }

    fun reset() {
        recent.clear()
    }

    companion object {
        const val DEFAULT_STALE_THRESHOLD_MS: Long = 250L

        /**
         * Window the "best-case age" reference is taken over. Long enough to span transient
         * congestion (so a brief stall doesn't redefine "fresh"), short enough that any
         * residual baseline shift self-heals in at most this long of dropped beacons —
         * versus *permanently* with the previous all-time minimum.
         */
        const val DEFAULT_FLOOR_WINDOW_MS: Long = 30_000L
    }
}
