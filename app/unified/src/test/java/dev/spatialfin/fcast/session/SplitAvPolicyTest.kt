package dev.spatialfin.fcast.session

import dev.jdtech.jellyfin.cast.CastCapability
import dev.jdtech.jellyfin.cast.CastProtocol
import dev.jdtech.jellyfin.cast.CastReceiver
import dev.spatialfin.fcast.session.SplitAvPolicy.BeaconState
import dev.spatialfin.fcast.session.SplitAvPolicy.DriftAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitAvPolicyTest {

    private fun beacon(
        beaconStreamPositionMs: Long = 30_000L,
        beaconReceivedWallMs: Long = 1_000_000L,
        xrPositionMs: Long = 30_000L,
        nowWallMs: Long = 1_000_000L,
        audioLatencyMs: Int = 0,
        networkOneWayMs: Int = 0,
        tvIsPlaying: Boolean = true,
    ) = BeaconState(
        beaconStreamPositionMs = beaconStreamPositionMs,
        beaconReceivedWallMs = beaconReceivedWallMs,
        xrPositionMs = xrPositionMs,
        nowWallMs = nowWallMs,
        audioLatencyMs = audioLatencyMs,
        networkOneWayMs = networkOneWayMs,
        tvIsPlaying = tvIsPlaying,
    )

    // --- expectedXrPositionMs (unchanged math, kept under regression) -----------------------

    @Test
    fun `expected position equals beacon position when wall times match and no offsets`() {
        assertEquals(30_000L, SplitAvPolicy.expectedXrPositionMs(beacon()))
    }

    @Test
    fun `expected position advances by elapsed wall time`() {
        assertEquals(30_250L, SplitAvPolicy.expectedXrPositionMs(beacon(nowWallMs = 1_000_250L)))
    }

    @Test
    fun `audio latency shifts expected position backwards`() {
        assertEquals(30_000L - 80, SplitAvPolicy.expectedXrPositionMs(beacon(audioLatencyMs = 80)))
    }

    @Test
    fun `network one-way shifts expected position forward`() {
        assertEquals(30_030L, SplitAvPolicy.expectedXrPositionMs(beacon(networkOneWayMs = 30)))
    }

    @Test
    fun `v4 precise path uses clock offset and ignores the RTT-2 guess`() {
        // Receiver sampled pos=30_000 at its monotonic 5_000; θ=+200 ⇒ sender-time sample
        // was 5_000−200=4_800; sender "now" 5_300 ⇒ +500 elapsed; −80 audio latency.
        val s = beacon(
            beaconStreamPositionMs = 30_000L,
            nowWallMs = 5_300L,
            audioLatencyMs = 80,
            networkOneWayMs = 999, // must be ignored on the precise path
        ).copy(clockOffsetMs = 200, receiverMonotonicSampleMs = 5_000L)
        assertEquals(30_000L + 500L - 80L, SplitAvPolicy.expectedXrPositionMs(s))
    }

    @Test
    fun `falls back to legacy path when offset or sample missing`() {
        val onlyOffset = beacon(networkOneWayMs = 30).copy(clockOffsetMs = 200)
        assertEquals(30_030L, SplitAvPolicy.expectedXrPositionMs(onlyOffset))
        val onlySample = beacon(networkOneWayMs = 30).copy(receiverMonotonicSampleMs = 9L)
        assertEquals(30_030L, SplitAvPolicy.expectedXrPositionMs(onlySample))
    }

    // --- asymmetric hold band --------------------------------------------------------------

    @Test
    fun `video-leading drift inside the wider band holds`() {
        // +25 ms (XR video ahead of audio) is below HOLD_VIDEO_LEADS_MS=30 → Hold
        val a = SplitAvPolicy.decide(beacon(), 25L, 0.0)
        assertTrue(a is DriftAction.Hold)
        assertEquals(25L, (a as DriftAction.Hold).driftMs)
    }

    @Test
    fun `audio-leading drift uses the tighter band`() {
        // -20 ms (audio ahead of video) exceeds HOLD_AUDIO_LEADS_MS=15 → must correct,
        // even though +20 ms in the other direction would still hold.
        assertTrue(SplitAvPolicy.decide(beacon(), 20L, 0.0) is DriftAction.Hold)
        assertTrue(SplitAvPolicy.decide(beacon(), -20L, 0.0) is DriftAction.NudgeSpeed)
        assertTrue(SplitAvPolicy.decide(beacon(), -14L, 0.0) is DriftAction.Hold)
    }

    @Test
    fun `steady-rate gate forces a correction even inside the hold band`() {
        // Small drift but the skew is growing fast → don't sit in Hold, trim it.
        assertTrue(SplitAvPolicy.decide(beacon(), 10L, 8.0) is DriftAction.NudgeSpeed)
        assertTrue(SplitAvPolicy.decide(beacon(), 10L, 0.0) is DriftAction.Hold)
    }

    // --- hard seek -------------------------------------------------------------------------

    @Test
    fun `smoothed drift beyond threshold hard-seeks to expected position`() {
        val action = SplitAvPolicy.decide(beacon(), 2_000L, 0.0) as DriftAction.HardSeek
        assertEquals(30_000L, action.toPositionMs)
        assertEquals(2_000L, action.driftMs)
    }

    @Test
    fun `hard seek target is clamped non-negative`() {
        val state = beacon(beaconStreamPositionMs = 100L, audioLatencyMs = 5_000)
        val action = SplitAvPolicy.decide(state, -9_000L, 0.0) as DriftAction.HardSeek
        assertTrue(action.toPositionMs >= 0L)
    }

    @Test
    fun `boundary at hard-seek threshold hard-seeks, just under nudges`() {
        assertTrue(
            SplitAvPolicy.decide(beacon(), SplitAvPolicy.HARD_SEEK_THRESHOLD_MS - 1L, 0.0)
                is DriftAction.NudgeSpeed,
        )
        assertTrue(
            SplitAvPolicy.decide(beacon(), SplitAvPolicy.HARD_SEEK_THRESHOLD_MS.toLong(), 0.0)
                is DriftAction.HardSeek,
        )
    }

    @Test
    fun `TV not playing short-circuits`() {
        assertEquals(
            DriftAction.TvNotPlaying,
            SplitAvPolicy.decide(beacon(tvIsPlaying = false), 9_999L, 99.0),
        )
    }

    // --- capability gate (unchanged) -------------------------------------------------------

    private fun receiver(protocol: CastProtocol, caps: Set<CastCapability>) = CastReceiver(
        id = "$protocol:test", name = "test", host = "10.0.0.1", port = 46899,
        protocol = protocol, capabilities = caps,
    )

    @Test
    fun `isAvailable only for FCast receiver carrying SplitAv capability`() {
        assertTrue(
            SplitAvPolicy.isAvailable(
                receiver(CastProtocol.FCast, setOf(CastCapability.Video, CastCapability.SplitAv)),
            ),
        )
        assertFalse(
            SplitAvPolicy.isAvailable(receiver(CastProtocol.FCast, setOf(CastCapability.Video))),
        )
        // Policy only inspects the capability set; "GoogleCast/AirPlay never carry SplitAv" is
        // the adapter layer's contract (FCastAdapterCapabilityTest), so the realistic negative
        // is a non-FCast receiver without the cap.
        assertFalse(
            SplitAvPolicy.isAvailable(
                receiver(CastProtocol.GoogleCast, setOf(CastCapability.Video, CastCapability.Volume)),
            ),
        )
        assertFalse(
            SplitAvPolicy.isAvailable(
                receiver(CastProtocol.AirPlay, setOf(CastCapability.Video)),
            ),
        )
    }
}

class DriftEstimatorTest {

    @Test
    fun `first sample seeds, no rate yet`() {
        val e = DriftEstimator()
        assertTrue(e.record(120L, 0L))
        assertEquals(120L, e.driftMs())
        assertEquals(0.0, e.driftRateMsPerSec(), 1e-9)
    }

    @Test
    fun `EWMA smooths toward new samples`() {
        val e = DriftEstimator(alpha = 0.5)
        e.record(100L, 0L)
        e.record(200L, 100L) // 0.5*200 + 0.5*100 = 150
        assertEquals(150L, e.driftMs())
    }

    @Test
    fun `transient spike is rejected then recovered`() {
        val e = DriftEstimator()
        e.record(50L, 0L)
        e.record(55L, 100L)
        e.record(60L, 200L)
        assertFalse("a single huge jump is an outlier", e.record(5_000L, 300L))
        // Smoothed value not poisoned by the spike.
        assertTrue(e.driftMs()!! < 200L)
    }

    @Test
    fun `persistent jump is accepted as a real discontinuity`() {
        val e = DriftEstimator(maxConsecutiveOutliers = 2)
        e.record(0L, 0L)
        e.record(5L, 100L)
        e.record(10L, 200L)
        e.record(4_000L, 300L) // outlier 1 (rejected)
        e.record(4_010L, 400L) // outlier 2 (rejected)
        assertTrue(e.record(4_020L, 500L)) // 3rd in a row → accept new reality
        assertTrue(e.driftMs()!! > 3_000L)
    }

    @Test
    fun `positive skew yields a positive drift rate`() {
        val e = DriftEstimator(alpha = 1.0, rateAlpha = 1.0)
        e.record(0L, 0L)
        e.record(50L, 1_000L) // +50 ms over 1 s
        assertTrue("rate≈+50ms/s, got ${e.driftRateMsPerSec()}", e.driftRateMsPerSec() > 30.0)
    }

    @Test
    fun `reset clears state`() {
        val e = DriftEstimator()
        e.record(100L, 0L)
        e.reset()
        assertNull(e.driftMs())
    }
}

class NudgeControllerTest {

    @Test
    fun `slows down when video ahead, speeds up when behind`() {
        val c = NudgeController()
        assertTrue("ahead → <1×", c.update(300L, 0.0, 100L, frozen = false) < 1.0f)
        c.reset()
        assertTrue("behind → >1×", c.update(-300L, 0.0, 100L, frozen = false) > 1.0f)
    }

    @Test
    fun `output is clamped to the max deviation`() {
        val c = NudgeController()
        repeat(200) { c.update(1_000_000L, 0.0, 100L, frozen = false) }
        assertEquals(
            (1.0 - NudgeController.DEFAULT_MAX_DEVIATION).toFloat(),
            c.update(1_000_000L, 0.0, 100L, frozen = false), 1e-4f,
        )
    }

    @Test
    fun `integral drives a constant skew to zero steady-state error`() {
        // Closed-loop sim: constant +50 ms/s skew, controller must null it. A pure
        // proportional law would settle at drift = rate/(1000·Kp) ≈ 312 ms; the integral
        // term must do far better.
        val c = NudgeController()
        var drift = 0.0
        val skewPerSec = 50.0
        val dt = 0.1
        repeat(1_500) { // 150 s
            val f = c.update(
                Math.round(drift), 0.0, 100L,
                frozen = SplitAvPolicy.withinHoldBand(Math.round(drift)),
            )
            drift += skewPerSec * dt + (f - 1.0) * 1000.0 * dt
        }
        assertTrue("steady-state |drift|=${Math.abs(drift)} should be small", Math.abs(drift) < 25.0)
    }

    @Test
    fun `reset clears the integrator`() {
        val c = NudgeController()
        repeat(50) { c.update(400L, 0.0, 100L, frozen = false) }
        c.reset()
        // Fresh integrator: a single small sample → near-neutral output again.
        assertEquals(1.0f, c.update(0L, 0.0, 100L, frozen = false), 1e-3f)
    }
}

class ClockOffsetEstimatorTest {

    @Test
    fun `solves a clean symmetric exchange`() {
        // receiver clock = sender + 1000; one-way 20 ms each leg.
        // t1=0 (send); t2=1020 (recv = 20 transit + 1000 offset); t3=1030 (10 ms processing);
        // t4=50 (recv pong = t3-offset+20 transit = 1030-1000+20).
        val e = ClockOffsetEstimator()
        assertTrue(e.record(t1 = 0, t2 = 1_020, t3 = 1_030, t4 = 50))
        assertEquals(1_000L, e.offsetMs())
    }

    @Test
    fun `picks the minimum-delay sample (clock filter)`() {
        val e = ClockOffsetEstimator()
        // Asymmetric/laggy sample: large δ, biased θ.
        e.record(t1 = 0, t2 = 1_300, t3 = 1_310, t4 = 600) // δ=590, θ≈1005
        // Clean low-δ sample: should win.
        e.record(t1 = 10_000, t2 = 11_020, t3 = 11_030, t4 = 10_050) // δ=40, θ=1000
        assertEquals(1_000L, e.offsetMs())
    }

    @Test
    fun `rejects negative and absurd round-trip delays`() {
        val e = ClockOffsetEstimator(maxDelayMs = 500)
        assertFalse(e.record(t1 = 0, t2 = 0, t3 = 0, t4 = -10)) // δ<0
        assertFalse(e.record(t1 = 0, t2 = 100, t3 = 101, t4 = 5_000)) // δ huge
        assertNull(e.offsetMs())
    }

    @Test
    fun `reset clears the window`() {
        val e = ClockOffsetEstimator()
        e.record(0, 1_020, 1_030, 50)
        e.reset()
        assertNull(e.offsetMs())
        assertFalse(e.hasEstimate())
    }
}

class AudioLatencyProfileTest {

    @Test
    fun `bitstreamed formats add decode latency, PCM-class add none`() {
        assertTrue(AudioLatencyProfile.extraLatencyMs("audio/eac3-joc") > 0)
        assertTrue(AudioLatencyProfile.extraLatencyMs("audio/true-hd") > 0)
        assertTrue(AudioLatencyProfile.extraLatencyMs("AUDIO/AC3") > 0) // case-insensitive
        assertEquals(0, AudioLatencyProfile.extraLatencyMs("audio/aac"))
        assertEquals(0, AudioLatencyProfile.extraLatencyMs(null))
        assertEquals(0, AudioLatencyProfile.extraLatencyMs("audio/opus"))
    }

    @Test
    fun `atmos costs at least as much as plain dolby digital`() {
        assertTrue(
            AudioLatencyProfile.extraLatencyMs("audio/eac3-joc") >=
                AudioLatencyProfile.extraLatencyMs("audio/ac3"),
        )
    }
}

class BeaconFreshnessGateTest {

    @Test
    fun `null age disables the gate (no disciplined clock yet)`() {
        val g = BeaconFreshnessGate()
        assertTrue(g.accept(ageMs = null, atMonotonicMs = 9_999L))
        assertTrue(g.accept(ageMs = null, atMonotonicMs = 1L))
    }

    @Test
    fun `first sample accepted, steady ages accepted, a spike rejected`() {
        val g = BeaconFreshnessGate(staleThresholdMs = 200L)
        var t = 0L
        assertTrue(g.accept(40L, t)) // first
        repeat(5) { t += 100; assertTrue(g.accept(45L, t)) } // steady ~40-45
        t += 100
        assertFalse("250ms over the ~40ms floor → queued", g.accept(300L, t))
        // A new low becomes the reference and is fresh by definition.
        t += 100
        assertTrue(g.accept(35L, t))
    }

    @Test
    fun `a permanent baseline step self-heals within the window (not forever)`() {
        // Models a clock re-base / step: age jumps +2000 and stays there. With the old
        // all-time-min floor this dropped every beacon forever; the sliding window must
        // recover once the pre-step low samples age out.
        val g = BeaconFreshnessGate(staleThresholdMs = 250L, floorWindowMs = 5_000L)
        var t = 0L
        repeat(10) { t += 100; g.accept(50L, t) } // floor ≈ 50
        // Step: +2000. Rejected while the 50ms samples are still in the 5s window.
        t += 100
        assertFalse(g.accept(2_050L, t))
        t += 1_000
        assertFalse("still within window of the old low", g.accept(2_050L, t))
        // Past the window: only post-step samples remain → 2050 is the new floor → fresh.
        t += 6_000
        assertTrue("recovered after the window", g.accept(2_050L, t))
        t += 100
        assertTrue(g.accept(2_060L, t))
    }

    @Test
    fun `slow disciplined drift never trips the threshold`() {
        // θ keeps residual drift sub-ms/s; over a long session the windowed min tracks it,
        // so age−floor stays tiny. (The old all-time-min grew unbounded here.)
        val g = BeaconFreshnessGate(staleThresholdMs = 250L, floorWindowMs = 30_000L)
        var t = 0L
        var age = 40.0
        repeat(36_000) { // 1 h at 10 Hz
            t += 100
            age += 0.0005 // 0.5 ms per 100 ms ≈ 5 ms/s residual (already pessimistic)
            assertTrue("dropped at t=$t age=$age", g.accept(age.toLong(), t))
        }
    }

    @Test
    fun `reset clears the window`() {
        val g = BeaconFreshnessGate(staleThresholdMs = 50L)
        var t = 0L
        repeat(5) { t += 100; g.accept(40L, t) }
        g.reset()
        t += 100
        assertTrue("post-reset first sample is a fresh baseline", g.accept(5_000L, t))
    }
}

class SplitAvTraceTest {

    @Test
    fun `csv round-trips`() {
        val t = SplitAvTrace(
            atMs = 12_345L, rawDriftMs = -40L, smoothedDriftMs = -12L,
            driftRateMsPerSec = 3.25, rttMs = 28, codecMime = "audio/eac3-joc",
            action = "NudgeSpeed",
        )
        val parsed = SplitAvTrace.fromCsvLine(t.toCsvLine())!!
        assertEquals(t.atMs, parsed.atMs)
        assertEquals(t.rawDriftMs, parsed.rawDriftMs)
        assertEquals(t.smoothedDriftMs, parsed.smoothedDriftMs)
        assertEquals(t.driftRateMsPerSec, parsed.driftRateMsPerSec, 1e-3)
        assertEquals(t.rttMs, parsed.rttMs)
        assertEquals(t.codecMime, parsed.codecMime)
        assertEquals(t.action, parsed.action)
    }

    @Test
    fun `header and blank lines parse to null`() {
        assertNull(SplitAvTrace.fromCsvLine(SplitAvTrace.CSV_HEADER))
        assertNull(SplitAvTrace.fromCsvLine(""))
        assertNull(SplitAvTrace.fromCsvLine("garbage"))
    }

    @Test
    fun `null rtt and codec serialize empty and parse back to null`() {
        val t = SplitAvTrace(1L, 0L, 0L, 0.0, null, null, "Hold")
        val p = SplitAvTrace.fromCsvLine(t.toCsvLine())!!
        assertNull(p.rttMs)
        assertNull(p.codecMime)
    }
}

class HardSeekRateLimiterTest {

    @Test
    fun `does not degrade below the cap`() {
        val limiter = HardSeekRateLimiter(maxSeeks = 3, windowMs = 30_000L)
        limiter.record(1_000L)
        limiter.record(2_000L)
        assertFalse(limiter.wouldDegrade(3_000L))
    }

    @Test
    fun `degrades when next seek would exceed cap`() {
        val limiter = HardSeekRateLimiter(maxSeeks = 3, windowMs = 30_000L)
        limiter.record(1_000L)
        limiter.record(2_000L)
        limiter.record(3_000L)
        assertTrue(limiter.wouldDegrade(4_000L))
    }

    @Test
    fun `evicts samples older than window`() {
        val limiter = HardSeekRateLimiter(maxSeeks = 3, windowMs = 30_000L)
        limiter.record(0L)
        limiter.record(10_000L)
        limiter.record(20_000L)
        assertEquals(2, limiter.count(35_000L))
        assertFalse(limiter.wouldDegrade(35_000L))
    }

    @Test
    fun `reset clears history`() {
        val limiter = HardSeekRateLimiter(maxSeeks = 3, windowMs = 30_000L)
        repeat(5) { limiter.record(it.toLong() * 1_000L) }
        limiter.reset()
        assertEquals(0, limiter.count(10_000L))
    }
}

class NetworkDelayEstimatorTest {

    @Test
    fun `returns null when no samples`() {
        val est = NetworkDelayEstimator()
        assertNull(est.rttMs())
        assertNull(est.oneWayMs())
    }

    @Test
    fun `first sample seeds the average`() {
        val est = NetworkDelayEstimator()
        assertTrue(est.recordRtt(40))
        assertEquals(40, est.rttMs())
        assertEquals(20, est.oneWayMs())
    }

    @Test
    fun `subsequent samples are EWMA-smoothed`() {
        val est = NetworkDelayEstimator(alpha = 0.5)
        est.recordRtt(40)
        est.recordRtt(80) // 0.5*80 + 0.5*40 = 60
        assertEquals(60, est.rttMs())
    }

    @Test
    fun `outliers beyond factor are rejected after the first sample`() {
        val est = NetworkDelayEstimator(outlierFactor = 4.0)
        est.recordRtt(40)
        est.recordRtt(50)
        assertFalse(est.recordRtt(10_000))
        assertTrue(est.rttMs()!! < 200)
    }

    @Test
    fun `negative samples are rejected`() {
        val est = NetworkDelayEstimator()
        assertFalse(est.recordRtt(-5))
        assertNull(est.rttMs())
    }

    @Test
    fun `reset clears history`() {
        val est = NetworkDelayEstimator()
        est.recordRtt(40)
        est.reset()
        assertNull(est.rttMs())
    }
}
