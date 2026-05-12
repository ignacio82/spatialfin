package dev.spatialfin.fcast.session

import kotlin.math.absoluteValue

/**
 * Pure drift-correction policy for split-A/V playback. The XR sender owns the video and uses
 * `PlaybackUpdate` beacons from the TV (which is the audio renderer + clock leader) to figure
 * out where its own video should be. This object is a fully testable, Android-free oracle —
 * the orchestrator (`SplitAvController`) feeds it state and translates the returned
 * [DriftAction] into ExoPlayer / FCast operations.
 *
 * Math reference (all times in ms unless suffixed):
 * ```
 * audioBeingHeardNowMs =
 *     (beaconTimeSeconds * 1000)            // TV's reported playback position when it sent
 *   + (nowWallMs - beaconReceivedWallMs)    // local wall-time elapsed since we got it
 *   - audioLatencyMs                        // constant AVR/soundbar tail
 *   + networkOneWayMs                       // estimated XR→TV network one-way (RTT/2)
 * ```
 * The +networkOneWayMs term comes from the derivation: `T_local` already absorbs the inbound
 * trip, so we add one outbound-equivalent hop to land on TV's true playback position at `now`.
 *
 * `xrDrift = xrPositionMs - audioBeingHeardNowMs`
 *  - drift > 0  → XR is showing future video (audio behind)
 *  - drift < 0  → XR is showing past video (audio ahead)
 *
 * Three-tier response:
 *  - |drift| < [HOLD_THRESHOLD_MS]: do nothing (within lipsync tolerance)
 *  - |drift| < [HARD_SEEK_THRESHOLD_MS]: NudgeSpeed in the appropriate direction
 *  - else: HardSeek to the expected position
 *
 * The orchestrator is responsible for capping the *rate* of HardSeeks (see [HardSeekRateLimiter]).
 */
object SplitAvPolicy {

    /** Below this absolute drift the user can't tell, so no correction. */
    const val HOLD_THRESHOLD_MS: Int = 20

    /**
     * Below this absolute drift we nudge speed; above it we hard-seek instead. 500 ms is
     * deliberately wider than perceptible A/V offset (~80–120 ms) because hard-seeks cost a
     * brief buffer cycle on the XR master and are far more disruptive than a residual offset
     * the speed-nudge will close within a couple of seconds. Wi-Fi RTT jitter alone can
     * spike apparent drift by 100–200 ms momentarily, and we don't want every transient
     * spike to trigger a seek.
     */
    const val HARD_SEEK_THRESHOLD_MS: Int = 500

    /** Speed multiplier used when nudging (XR is behind / ahead). */
    const val SPEEDUP_FACTOR: Float = 1.01f
    const val SLOWDOWN_FACTOR: Float = 0.99f

    /**
     * How long a NudgeSpeed should run before reverting to 1.0×. The orchestrator schedules
     * the revert; the policy doesn't track time itself.
     */
    const val NUDGE_DURATION_MS: Long = 500L

    data class BeaconState(
        /** PlaybackUpdate.time, converted from seconds to ms. */
        val beaconStreamPositionMs: Long,
        /** Local wall-clock time at which the beacon was received. */
        val beaconReceivedWallMs: Long,
        /** XR's current playback position in the same stream. */
        val xrPositionMs: Long,
        /** Wall-clock "now" at the moment the policy is evaluating. */
        val nowWallMs: Long,
        /** Cached AVR/soundbar tail latency from calibration; 0 when uncalibrated. */
        val audioLatencyMs: Int,
        /** Estimated XR→TV network one-way delay (RTT/2); 0 when no Ping/Pong samples yet. */
        val networkOneWayMs: Int,
        /**
         * The TV's reported playback state. Drift correction only fires while the TV is
         * playing — paused / idle states are passed through untouched so the orchestrator can
         * propagate them via the cascade rules.
         */
        val tvIsPlaying: Boolean,
    )

    sealed interface DriftAction {
        /** Within tolerance — do nothing. */
        data object Hold : DriftAction

        /**
         * Apply a small playback-rate change for [SplitAvPolicy.NUDGE_DURATION_MS] ms.
         * [factor] is the multiplier to feed to ExoPlayer.setPlaybackSpeed().
         */
        data class NudgeSpeed(val factor: Float, val driftMs: Long) : DriftAction

        /** Drift too large to mask — seek the XR player to [toPositionMs]. */
        data class HardSeek(val toPositionMs: Long, val driftMs: Long) : DriftAction

        /** TV isn't playing — do nothing here, propagate state at the orchestrator. */
        data object TvNotPlaying : DriftAction
    }

    fun expectedXrPositionMs(state: BeaconState): Long {
        val elapsedSinceBeaconMs = state.nowWallMs - state.beaconReceivedWallMs
        return state.beaconStreamPositionMs +
            elapsedSinceBeaconMs -
            state.audioLatencyMs +
            state.networkOneWayMs
    }

    fun decide(state: BeaconState): DriftAction {
        if (!state.tvIsPlaying) return DriftAction.TvNotPlaying

        val expected = expectedXrPositionMs(state)
        val drift = state.xrPositionMs - expected

        return when {
            drift.absoluteValue < HOLD_THRESHOLD_MS -> DriftAction.Hold
            drift.absoluteValue < HARD_SEEK_THRESHOLD_MS -> {
                val factor = if (drift > 0) SLOWDOWN_FACTOR else SPEEDUP_FACTOR
                DriftAction.NudgeSpeed(factor = factor, driftMs = drift)
            }
            else -> DriftAction.HardSeek(toPositionMs = expected.coerceAtLeast(0L), driftMs = drift)
        }
    }
}

/**
 * Caps how often a HardSeek can fire. If the policy returns HardSeek more than [maxSeeks] times
 * within [windowMs], the orchestrator should bail to single-renderer mode — the network is
 * misbehaving badly enough that fighting drift will just sound worse than abandoning split mode.
 *
 * Pure: feed it timestamps and ask if the next seek would breach the cap.
 */
class HardSeekRateLimiter(
    private val maxSeeks: Int = DEFAULT_MAX_SEEKS,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    private val recent = ArrayDeque<Long>()

    /** Record a hard seek at [nowWallMs]. */
    fun record(nowWallMs: Long) {
        evict(nowWallMs)
        recent.addLast(nowWallMs)
    }

    /** Returns true if recording another seek at [nowWallMs] would exceed the cap. */
    fun wouldDegrade(nowWallMs: Long): Boolean {
        evict(nowWallMs)
        return recent.size + 1 > maxSeeks
    }

    /** Current count of seeks within the window ending at [nowWallMs]. */
    fun count(nowWallMs: Long): Int {
        evict(nowWallMs)
        return recent.size
    }

    fun reset() {
        recent.clear()
    }

    private fun evict(nowWallMs: Long) {
        val cutoff = nowWallMs - windowMs
        while (recent.isNotEmpty() && recent.first() < cutoff) {
            recent.removeFirst()
        }
    }

    companion object {
        const val DEFAULT_MAX_SEEKS: Int = 3
        const val DEFAULT_WINDOW_MS: Long = 30_000L
    }
}

/**
 * EWMA-smoothed RTT estimator fed by Ping/Pong round-trips. The orchestrator records each
 * sample; [oneWayMs] returns the current half-RTT estimate (or null when no samples yet).
 *
 * Outlier rejection: a sample more than [outlierFactor]× the current EWMA is dropped to keep
 * a single Wi-Fi hiccup from poisoning the running average. The first sample always seeds
 * the average since there's no history to compare against.
 */
class NetworkDelayEstimator(
    private val alpha: Double = DEFAULT_ALPHA,
    private val outlierFactor: Double = DEFAULT_OUTLIER_FACTOR,
) {
    private var smoothedRttMs: Double? = null
    private var sampleCount: Int = 0
    private var consecutiveOutliers: Int = 0

    /** Returns true if the sample was accepted (not rejected as an outlier). */
    fun recordRtt(rttMs: Long): Boolean {
        if (rttMs < 0) return false
        val current = smoothedRttMs
        if (current == null) {
            smoothedRttMs = rttMs.toDouble()
            sampleCount = 1
            return true
        }
        if (rttMs > current * outlierFactor && sampleCount > 1) {
            consecutiveOutliers++
            if (consecutiveOutliers > DEFAULT_MAX_CONSECUTIVE_OUTLIERS) {
                // The network latency jump appears permanent; accept the new reality
                smoothedRttMs = rttMs.toDouble()
                consecutiveOutliers = 0
                return true
            }
            return false
        }
        consecutiveOutliers = 0
        smoothedRttMs = alpha * rttMs + (1 - alpha) * current
        sampleCount++
        return true
    }

    fun rttMs(): Int? = smoothedRttMs?.toInt()

    fun oneWayMs(): Int? = smoothedRttMs?.let { (it / 2.0).toInt() }

    fun reset() {
        smoothedRttMs = null
        sampleCount = 0
        consecutiveOutliers = 0
    }

    companion object {
        const val DEFAULT_ALPHA: Double = 0.25
        const val DEFAULT_OUTLIER_FACTOR: Double = 4.0
        const val DEFAULT_MAX_CONSECUTIVE_OUTLIERS: Int = 3
    }
}
