package dev.spatialfin.companion.wear.rotary

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the real [RotaryScrubState], not a copy of its constants: these assertions
 * fail if the production step size, clamping, debounce or consumption contract changes.
 *
 * Haptics are injected as a lambda, so the accumulator carries no Android types and
 * runs on a plain JVM test dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RotaryScrubStateTest {

    private fun state(
        initial: Long = 0L,
        duration: Long = 3600L,
        onSeek: (Long) -> Unit = {},
    ) = RotaryScrubState(initial, duration, onSeek)

    @Test
    fun `one detent advances a single five second step`() = runTest {
        val state = state()
        val consumed = state.onRotaryDelta(RotaryScrubState.PIXELS_PER_STEP, {}, this)

        assertTrue("a full detent must be consumed", consumed)
        assertEquals(RotaryScrubState.SECONDS_PER_STEP, state.currentScrubPositionSeconds)
        advanceUntilIdle()
    }

    @Test
    fun `sub-detent movement is not consumed so it can fall through to scrolling`() = runTest {
        val state = state()
        val consumed = state.onRotaryDelta(RotaryScrubState.PIXELS_PER_STEP / 3f, {}, this)

        assertFalse("a partial detent must fall through to the scroll container", consumed)
        assertEquals(0L, state.currentScrubPositionSeconds)
    }

    @Test
    fun `partial deltas accumulate across events into one step`() = runTest {
        val state = state()
        val third = RotaryScrubState.PIXELS_PER_STEP / 3f + 0.1f
        state.onRotaryDelta(third, {}, this)
        state.onRotaryDelta(third, {}, this)
        val consumed = state.onRotaryDelta(third, {}, this)

        assertTrue(consumed)
        assertEquals(RotaryScrubState.SECONDS_PER_STEP, state.currentScrubPositionSeconds)
        advanceUntilIdle()
    }

    @Test
    fun `scrub position is clamped to the item duration`() = runTest {
        val state = state(initial = 3595L, duration = 3600L)
        state.onRotaryDelta(RotaryScrubState.PIXELS_PER_STEP * 10, {}, this)

        assertEquals(3600L, state.currentScrubPositionSeconds)
        advanceUntilIdle()
    }

    @Test
    fun `scrub position never goes negative`() = runTest {
        val state = state(initial = 10L)
        state.onRotaryDelta(-RotaryScrubState.PIXELS_PER_STEP * 5, {}, this)

        assertEquals(0L, state.currentScrubPositionSeconds)
        advanceUntilIdle()
    }

    @Test
    fun `a fast spin dispatches once, at the final position`() = runTest {
        val dispatched = mutableListOf<Long>()
        val state = state(onSeek = { dispatched += it })

        // Ten detents inside the debounce window: the socket must see one seek, not ten.
        repeat(10) { state.onRotaryDelta(RotaryScrubState.PIXELS_PER_STEP, {}, this) }
        assertTrue("nothing may dispatch before the debounce elapses", dispatched.isEmpty())

        advanceTimeBy(RotaryScrubState.DISPATCH_DEBOUNCE_MS + 1)
        advanceUntilIdle()

        assertEquals(listOf(10 * RotaryScrubState.SECONDS_PER_STEP), dispatched)
        assertFalse("isScrubbing must clear once the seek is dispatched", state.isScrubbing)
    }

    @Test
    fun `isScrubbing is set while a spin is in flight`() = runTest {
        val state = state()
        state.onRotaryDelta(RotaryScrubState.PIXELS_PER_STEP, {}, this)
        assertTrue(state.isScrubbing)
        advanceUntilIdle()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RotaryVolumeStateTest {

    @Test
    fun `one detent moves volume by a single step`() = runTest {
        val state = RotaryVolumeState(0.5f) {}
        val consumed = state.onRotaryDelta(RotaryVolumeState.PIXELS_PER_STEP, {}, this)

        assertTrue(consumed)
        assertEquals(0.55f, state.currentVolume, 0.0001f)
        advanceUntilIdle()
    }

    @Test
    fun `volume is clamped to zero and one`() = runTest {
        val loud = RotaryVolumeState(0.98f) {}
        loud.onRotaryDelta(RotaryVolumeState.PIXELS_PER_STEP * 5, {}, this)
        assertEquals(1f, loud.currentVolume, 0.0001f)

        val quiet = RotaryVolumeState(0.02f) {}
        quiet.onRotaryDelta(-RotaryVolumeState.PIXELS_PER_STEP * 5, {}, this)
        assertEquals(0f, quiet.currentVolume, 0.0001f)
        advanceUntilIdle()
    }

    @Test
    fun `a fast spin dispatches one volume change`() = runTest {
        val dispatched = mutableListOf<Float>()
        val state = RotaryVolumeState(0.5f) { dispatched += it }

        repeat(4) { state.onRotaryDelta(RotaryVolumeState.PIXELS_PER_STEP, {}, this) }
        assertNull(dispatched.firstOrNull())

        advanceTimeBy(RotaryScrubState.DISPATCH_DEBOUNCE_MS + 1)
        advanceUntilIdle()

        assertEquals(1, dispatched.size)
        assertEquals(0.7f, dispatched.single(), 0.0001f)
    }
}
