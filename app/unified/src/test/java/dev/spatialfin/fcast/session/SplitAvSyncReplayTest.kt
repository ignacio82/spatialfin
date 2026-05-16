package dev.spatialfin.fcast.session

import dev.spatialfin.fcast.session.calibration.ChirpDetector
import dev.spatialfin.fcast.session.calibration.ChirpGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Matched-filter accuracy + the offline drift-policy replay harness. Both exercise the pure
 * sync pipeline end-to-end so thresholds/gains can be tuned here instead of on hardware.
 */
class MatchedChirpDetectorTest {

    private fun addNoise(src: ShortArray, rng: Random, amp: Int): ShortArray =
        ShortArray(src.size) { i ->
            (src[i] + rng.nextInt(-amp, amp + 1)).coerceIn(
                Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt(),
            ).toShort()
        }

    @Test
    fun `matched filter recovers every chirp onset within 5ms under noise and echo`() {
        val sr = ChirpGenerator.SAMPLE_RATE_HZ
        val clean = ChirpGenerator.generatePcm()
        // Add a delayed, attenuated copy (room echo) + broadband noise.
        val echoDelay = sr * 60 / 1_000 // 60 ms reflection
        val withEcho = clean.copyOf()
        for (i in echoDelay until withEcho.size) {
            withEcho[i] = (withEcho[i] + clean[i - echoDelay] / 3)
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        val capture = addNoise(withEcho, Random(7), amp = 1_200)

        val res = ChirpDetector.detectMatched(
            samples = capture, sampleRateHz = sr, template = ChirpGenerator.chirpTemplate(),
        )
        assertEquals(
            "should find all chirps", ChirpGenerator.CHIRP_COUNT, res.onsets.size,
        )
        ChirpGenerator.chirpOnsetsSeconds.forEachIndexed { i, expected ->
            val err = abs(res.onsets[i].timeSeconds - expected) * 1_000.0
            assertTrue("chirp $i onset off by ${"%.1f".format(err)}ms", err <= 5.0)
        }
    }

    @Test
    fun `pure noise yields no spurious onsets`() {
        val sr = ChirpGenerator.SAMPLE_RATE_HZ
        val noise = ShortArray(sr * 2) { Random(1).nextInt(-1_000, 1_000).toShort() }
        val res = ChirpDetector.detectMatched(
            samples = noise, sampleRateHz = sr, template = ChirpGenerator.chirpTemplate(),
        )
        assertTrue("got ${res.onsets.size} false onsets", res.onsets.isEmpty())
    }
}

/**
 * Closed-loop replay of the *real* [DriftEstimator] + [SplitAvPolicy] + [NudgeController]
 * pipeline against a simulated plant. Each step: measure (true drift + Wi-Fi jitter) → smooth
 * → decide → trim → and crucially **feed the trim back into the plant** so the speed change
 * actually moves the drift, exactly as on a device. The open-loop version (prescribed raw
 * samples) couldn't validate convergence because the controller's output had no effect on the
 * input. This is the harness the production `traceSink` feeds: capture logcat, parse with
 * [SplitAvTrace.fromCsvLine], replace `disturbance` with the recorded skew, A/B a gain change.
 */
class SplitAvReplayTest {

    private data class Step(val action: SplitAvPolicy.DriftAction, val trueDrift: Double)

    /**
     * @param steps number of 100 ms beacons
     * @param jitter ± Wi-Fi jitter (ms) added to the *measurement* only
     * @param disturbance true-drift change per second (ms/s) injected by the plant — constant
     *  clock skew, optionally a step jump via [jumpAt]/[jumpMs]
     */
    private fun simulate(
        steps: Int,
        jitter: Int,
        disturbance: Double,
        jumpAt: Int = -1,
        jumpMs: Double = 0.0,
        seed: Int = 1,
    ): List<Step> {
        val est = DriftEstimator()
        val ctl = NudgeController()
        val rng = Random(seed)
        val state = SplitAvPolicy.BeaconState(
            beaconStreamPositionMs = 0, beaconReceivedWallMs = 0, xrPositionMs = 0,
            nowWallMs = 0, audioLatencyMs = 0, networkOneWayMs = 0, tvIsPlaying = true,
        )
        var trueDrift = 0.0
        val dtSec = 0.1
        val out = ArrayList<Step>(steps)
        for (i in 0 until steps) {
            if (i == jumpAt) trueDrift += jumpMs
            val measured = Math.round(trueDrift) + rng.nextInt(-jitter, jitter + 1).toLong()
            var f = 1.0f
            if (est.record(measured, i * 100L)) {
                val s = est.driftMs() ?: measured
                val r = est.driftRateMsPerSec()
                val a = SplitAvPolicy.decide(state, s, r)
                when (a) {
                    is SplitAvPolicy.DriftAction.HardSeek -> {
                        trueDrift = 0.0; est.reset(); ctl.reset()
                    }
                    is SplitAvPolicy.DriftAction.Hold ->
                        f = ctl.update(s, r, 100L, frozen = true)
                    is SplitAvPolicy.DriftAction.NudgeSpeed ->
                        f = ctl.update(s, r, 100L, frozen = false)
                    SplitAvPolicy.DriftAction.TvNotPlaying -> ctl.reset()
                }
                out += Step(a, trueDrift)
            }
            // Plant: skew pushes drift; the applied video speed (f) pulls it back.
            trueDrift += disturbance * dtSec + (f - 1.0) * 1000.0 * dtSec
        }
        return out
    }

    @Test
    fun `wifi jitter around zero never hard-seeks`() {
        val steps = simulate(steps = 600, jitter = 150, disturbance = 0.0, seed = 42)
        assertTrue(
            "jitter must not trip a hard-seek",
            steps.none { it.action is SplitAvPolicy.DriftAction.HardSeek },
        )
    }

    @Test
    fun `steady clock skew converges to tight lipsync with no hard-seeks`() {
        // +60 ms/s constant skew (far worse than any real crystal) + light jitter.
        val steps = simulate(steps = 1_500, jitter = 20, disturbance = 60.0, seed = 9)
        assertTrue(
            "PI trim must absorb skew without hard-seeking",
            steps.none { it.action is SplitAvPolicy.DriftAction.HardSeek },
        )
        val tail = steps.takeLast(300)
        val worst = tail.maxByOrNull { abs(it.trueDrift) }!!.trueDrift
        assertTrue("steady-state |drift|=$worst should be tight", abs(worst) < 60.0)
    }

    @Test
    fun `a real desync jump triggers a hard-seek then re-converges`() {
        // 20 s steady, a +3 s jump at t=20 s, then steady again for 20 s.
        val steps = simulate(
            steps = 460, jitter = 15, disturbance = 0.0,
            jumpAt = 200, jumpMs = 3_000.0, seed = 3,
        )
        val seeks = steps.count { it.action is SplitAvPolicy.DriftAction.HardSeek }
        assertTrue("expected a hard-seek on the real jump, got $seeks", seeks in 1..3)
        assertTrue(
            "must re-converge after the seek",
            steps.takeLast(50).none { it.action is SplitAvPolicy.DriftAction.HardSeek },
        )
        assertTrue(abs(steps.last().trueDrift) < 60.0)
    }
}
