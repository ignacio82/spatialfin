package dev.spatialfin.fcast.session

import dev.jdtech.jellyfin.cast.CastCapability
import dev.jdtech.jellyfin.cast.CastReceiver
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
 * Decision (on the *smoothed* drift from [DriftEstimator], not the raw beacon value):
 *  - |drift| ≥ [HARD_SEEK_THRESHOLD_MS]: HardSeek to the expected position
 *  - inside the asymmetric perceptual band ([HOLD_VIDEO_LEADS_MS] / [HOLD_AUDIO_LEADS_MS])
 *    and steady (|rate| < [STEADY_RATE_MS_PER_SEC]): Hold — the orchestrator keeps the
 *    standing [NudgeController] trim but freezes its integrator
 *  - otherwise: NudgeSpeed — the orchestrator drives the integrating [NudgeController]
 *
 * This object only picks the *mode*; the speed multiplier is the stateful [NudgeController]'s
 * job (an integral term is required to null a constant-rate clock-skew disturbance — pure
 * proportional control leaves a large steady-state offset). The orchestrator caps the *rate*
 * of HardSeeks (see [HardSeekRateLimiter]).
 */
object SplitAvPolicy {

    /**
     * Whether SpatialFin's split-A/V mode (XR video + receiver audio with lipsync calibration)
     * is available on [receiver]. Only FCast receivers ever return true — Google Cast and
     * AirPlay protocols don't expose the primitives the split-mode design depends on:
     *
     *  - No commanded sub-frame start instant for independent A/V streams.
     *  - No side-channel for the calibration chirp / `SplitAvMetadata` payload.
     *  - No precise clock telemetry (Cast `MEDIA_STATUS` is ~1 Hz, AirPlay `playback-info` the
     *    same, and Cast groups add 50–200 ms of uncontrolled buffering on top).
     *
     * Adapters are responsible for advertising the [CastCapability.SplitAv] flag; only
     * [dev.jdtech.jellyfin.cast.adapter.FCastAdapter] is allowed to set it. Every SplitAv
     * entry point (session manager, calibration orchestrator, bridge service, UI toggles) checks
     * this — defense in depth so a non-FCast receiver can never reach the calibration / drift
     * pipeline.
     */
    fun isAvailable(receiver: CastReceiver): Boolean =
        CastCapability.SplitAv in receiver.capabilities

    /**
     * Perceptual hold band — *asymmetric* by design (ITU-R BT.1359). The human visual+aural
     * system tolerates video *leading* audio far more than audio leading video:
     *
     *  - `drift > 0` → XR video is ahead of the audio ("video leads audio"): tolerated to
     *    [HOLD_VIDEO_LEADS_MS]. Detectable threshold is ~+125 ms; we hold conservatively below.
     *  - `drift < 0` → audio is ahead of XR video ("audio leads video"): much more jarring,
     *    detectable around ~-45 ms; we hold only to [HOLD_AUDIO_LEADS_MS].
     *
     * A symmetric ±20 ms band wasted correction effort on imperceptible video-leads drift and
     * under-protected the audio-leads side. Both bounds stay well inside the perceptual limits
     * so steady-state lipsync is still tight.
     */
    const val HOLD_VIDEO_LEADS_MS: Int = 30
    const val HOLD_AUDIO_LEADS_MS: Int = 15

    /**
     * Above this *smoothed* absolute drift we hard-seek instead of trimming speed. Wider than
     * perceptible A/V offset because a hard-seek costs a buffer cycle (a black frame inside an
     * immersive headset) and is far more disruptive than a residual the speed trim closes
     * within a few seconds. The smoothing in [DriftEstimator] means a transient Wi-Fi spike no
     * longer reaches this threshold, so it can sit tighter than the old raw-signal value.
     */
    const val HARD_SEEK_THRESHOLD_MS: Int = 500

    /** Below this |drift-rate| (ms/s) the skew is "steady". Combined with the hold band it
     *  lets the policy return [DriftAction.Hold], which freezes the [NudgeController]
     *  integrator so it *maintains* the standing trim instead of winding up on sub-perceptual
     *  noise. */
    const val STEADY_RATE_MS_PER_SEC: Double = 4.0

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
        /**
         * Estimated XR→TV network one-way delay (RTT/2); 0 when no Ping/Pong samples yet.
         * Only used on the *legacy* path — when [clockOffsetMs] + [receiverMonotonicSampleMs]
         * are both present the precise NTP mapping replaces this approximation entirely.
         */
        val networkOneWayMs: Int,
        /**
         * The TV's reported playback state. Drift correction only fires while the TV is
         * playing — paused / idle states are passed through untouched so the orchestrator can
         * propagate them via the cascade rules.
         */
        val tvIsPlaying: Boolean,
        /**
         * FCast v4: estimated receiver↔sender clock offset θ (`receiver ≈ sender + θ`) from
         * [ClockOffsetEstimator]. Null until θ converges / pre-v4 peer → legacy path.
         */
        val clockOffsetMs: Long? = null,
        /**
         * FCast v4: the receiver's monotonic clock when it sampled [beaconStreamPositionMs]
         * (`PlaybackUpdateMessage.monotonicSampleMs`). Null pre-v4 → legacy path.
         */
        val receiverMonotonicSampleMs: Long? = null,
    )

    sealed interface DriftAction {
        /**
         * Within the perceptual band and steady. The orchestrator keeps applying the
         * [NudgeController]'s *current* speed trim (so a standing skew correction is held —
         * resetting to 1.0× here is what caused the old sawtooth) but tells the controller to
         * freeze its integrator so sub-perceptual jitter doesn't wind it up. [driftMs] is the
         * smoothed drift, for telemetry.
         */
        data class Hold(val driftMs: Long) : DriftAction

        /**
         * Actively correcting: the orchestrator drives the [NudgeController] (integrating) and
         * applies its speed trim. [driftMs] is the smoothed drift.
         */
        data class NudgeSpeed(val driftMs: Long) : DriftAction

        /** Drift too large to mask — seek the XR player to [toPositionMs]. */
        data class HardSeek(val toPositionMs: Long, val driftMs: Long) : DriftAction

        /** TV isn't playing — do nothing here, propagate state at the orchestrator. */
        data object TvNotPlaying : DriftAction
    }

    /**
     * Where XR's video *should* be right now to match the audio the user is hearing.
     *
     * Precise (v4) path — when both the clock offset θ and the receiver's monotonic sample
     * time are known: the beacon position was sampled at sender-time
     * `receiverMonotonicSampleMs − θ`, so add the wall time elapsed since then. No `RTT/2`
     * guess and no "received ≈ generated" approximation — both error sources are eliminated.
     *
     * Legacy path — otherwise: advance the beacon by the time since *receipt* and correct by
     * the symmetric one-way estimate.
     */
    fun expectedXrPositionMs(state: BeaconState): Long {
        val offset = state.clockOffsetMs
        val recvSample = state.receiverMonotonicSampleMs
        if (offset != null && recvSample != null) {
            val senderSampleTimeMs = recvSample - offset
            return state.beaconStreamPositionMs +
                (state.nowWallMs - senderSampleTimeMs) -
                state.audioLatencyMs
        }
        val elapsedSinceBeaconMs = state.nowWallMs - state.beaconReceivedWallMs
        return state.beaconStreamPositionMs +
            elapsedSinceBeaconMs -
            state.audioLatencyMs +
            state.networkOneWayMs
    }

    /** True when [driftMs] is inside the asymmetric perceptual hold band. */
    fun withinHoldBand(driftMs: Long): Boolean =
        if (driftMs >= 0) driftMs < HOLD_VIDEO_LEADS_MS else -driftMs < HOLD_AUDIO_LEADS_MS

    /**
     * Decide the *mode* given the smoothed drift and its rate (both from [DriftEstimator]),
     * plus [state] for the play flag and the hard-seek target. The actual speed multiplier is
     * computed statefully by [NudgeController] — a constant clock skew is a constant-rate
     * disturbance, and only an integral term drives its steady-state error to zero (pure
     * proportional control leaves a large residual offset). Acting on the smoothed signal,
     * not the raw per-beacon value, is what lets the thresholds sit tight without thrashing.
     */
    fun decide(
        state: BeaconState,
        smoothedDriftMs: Long,
        driftRateMsPerSec: Double,
    ): DriftAction {
        if (!state.tvIsPlaying) return DriftAction.TvNotPlaying

        return when {
            smoothedDriftMs.absoluteValue >= HARD_SEEK_THRESHOLD_MS ->
                DriftAction.HardSeek(
                    toPositionMs = expectedXrPositionMs(state).coerceAtLeast(0L),
                    driftMs = smoothedDriftMs,
                )
            withinHoldBand(smoothedDriftMs) &&
                driftRateMsPerSec.absoluteValue < STEADY_RATE_MS_PER_SEC ->
                DriftAction.Hold(driftMs = smoothedDriftMs)
            else ->
                DriftAction.NudgeSpeed(driftMs = smoothedDriftMs)
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
