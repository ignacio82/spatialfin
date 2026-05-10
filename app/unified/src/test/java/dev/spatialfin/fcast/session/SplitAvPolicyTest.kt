package dev.spatialfin.fcast.session

import dev.spatialfin.fcast.session.SplitAvPolicy.BeaconState
import dev.spatialfin.fcast.session.SplitAvPolicy.DriftAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test
    fun `expected position equals beacon position when wall times match and no offsets`() {
        val expected = SplitAvPolicy.expectedXrPositionMs(beacon())
        assertEquals(30_000L, expected)
    }

    @Test
    fun `expected position advances by elapsed wall time`() {
        // 250ms elapsed since beacon receipt → expected has moved forward 250ms
        val state = beacon(nowWallMs = 1_000_250L)
        assertEquals(30_250L, SplitAvPolicy.expectedXrPositionMs(state))
    }

    @Test
    fun `audio latency shifts expected position backwards`() {
        // The user is hearing what the TV submitted 80ms ago, so XR's video should match
        // 80ms earlier than the raw beacon would suggest.
        val state = beacon(audioLatencyMs = 80)
        assertEquals(30_000L - 80, SplitAvPolicy.expectedXrPositionMs(state))
    }

    @Test
    fun `network one-way shifts expected position forward`() {
        // 30ms one-way means the TV has played 30ms further than the beacon shows.
        val state = beacon(networkOneWayMs = 30)
        assertEquals(30_030L, SplitAvPolicy.expectedXrPositionMs(state))
    }

    @Test
    fun `drift below hold threshold returns Hold`() {
        // 15ms ahead — under the 20ms hold band
        val state = beacon(xrPositionMs = 30_015L)
        assertEquals(DriftAction.Hold, SplitAvPolicy.decide(state))
    }

    @Test
    fun `drift in nudge band slows down when XR is ahead`() {
        val state = beacon(xrPositionMs = 30_100L)  // 100ms ahead
        val action = SplitAvPolicy.decide(state) as DriftAction.NudgeSpeed
        assertEquals(SplitAvPolicy.SLOWDOWN_FACTOR, action.factor)
        assertEquals(100L, action.driftMs)
    }

    @Test
    fun `drift in nudge band speeds up when XR is behind`() {
        val state = beacon(xrPositionMs = 29_900L)  // 100ms behind
        val action = SplitAvPolicy.decide(state) as DriftAction.NudgeSpeed
        assertEquals(SplitAvPolicy.SPEEDUP_FACTOR, action.factor)
        assertEquals(-100L, action.driftMs)
    }

    @Test
    fun `drift beyond hard seek threshold returns HardSeek`() {
        val state = beacon(xrPositionMs = 32_000L)  // 2s ahead
        val action = SplitAvPolicy.decide(state) as DriftAction.HardSeek
        assertEquals(30_000L, action.toPositionMs)
        assertEquals(2_000L, action.driftMs)
    }

    @Test
    fun `HardSeek target is clamped to non-negative`() {
        // Beacon says 200ms but XR has somehow run far ahead and audio latency is huge
        val state = beacon(
            beaconStreamPositionMs = 100L,
            xrPositionMs = 5_000L,
            audioLatencyMs = 500,
        )
        val action = SplitAvPolicy.decide(state) as DriftAction.HardSeek
        assertTrue(action.toPositionMs >= 0L)
    }

    @Test
    fun `TV not playing short-circuits to TvNotPlaying`() {
        val state = beacon(tvIsPlaying = false, xrPositionMs = 0L, beaconStreamPositionMs = 60_000L)
        assertEquals(DriftAction.TvNotPlaying, SplitAvPolicy.decide(state))
    }

    @Test
    fun `boundary at hold threshold falls into nudge band`() {
        // Exactly 20ms drift should nudge, not hold (band is < 20)
        val state = beacon(xrPositionMs = 30_020L)
        assertTrue(SplitAvPolicy.decide(state) is DriftAction.NudgeSpeed)
    }

    @Test
    fun `boundary at hard seek threshold falls into hard seek`() {
        val state = beacon(xrPositionMs = 30_200L)
        assertTrue(SplitAvPolicy.decide(state) is DriftAction.HardSeek)
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
        // At t=35s the t=0 sample has fallen off
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
        est.recordRtt(80)  // 0.5 * 80 + 0.5 * 40 = 60
        assertEquals(60, est.rttMs())
    }

    @Test
    fun `outliers beyond factor are rejected after the first sample`() {
        val est = NetworkDelayEstimator(outlierFactor = 4.0)
        est.recordRtt(40)
        est.recordRtt(50)  // accepted, helps establish history
        assertFalse(est.recordRtt(10_000))  // 10s spike — rejected
        // RTT remains close to the smoothed value, not poisoned
        val rtt = est.rttMs()!!
        assertTrue("rtt=$rtt should be under 200ms", rtt < 200)
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
