package dev.spatialfin.companion.wear.rotary

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the crown is currently driving. */
enum class CrownMode { Scrub, Volume }

/**
 * Controller for rotary crown media scrubbing with tactile haptic feedback.
 *
 * Accumulates raw `onRotaryScrollEvent` pixels into 5-second seek steps, updates the
 * local scrubber at frame rate, and debounces the outbound seek at 100 ms so a fast
 * spin does not flood the Data Layer.
 */
class RotaryScrubState(
    initialPositionSeconds: Long,
    val durationSeconds: Long,
    private val onSeekDispatched: (Long) -> Unit,
) {
    var currentScrubPositionSeconds by mutableLongStateOf(initialPositionSeconds)
    var isScrubbing by mutableStateOf(false)

    private var accumulatedPixels = 0f
    private var debounceJob: Job? = null

    /**
     * @param onHapticTick fired once per discrete step. Passed in rather than taking a
     *   `View` so the accumulator stays free of Android types and unit-testable on the JVM.
     * @return true when the delta produced at least one seek step and was consumed.
     */
    fun onRotaryDelta(pixels: Float, onHapticTick: () -> Unit, scope: CoroutineScope): Boolean {
        accumulatedPixels += pixels
        val steps = (accumulatedPixels / PIXELS_PER_STEP).toInt()
        if (steps == 0) return false

        accumulatedPixels -= steps * PIXELS_PER_STEP
        val deltaSeconds = steps * SECONDS_PER_STEP
        currentScrubPositionSeconds =
            (currentScrubPositionSeconds + deltaSeconds).coerceIn(0L, durationSeconds.coerceAtLeast(1L))
        isScrubbing = true

        onHapticTick()

        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DISPATCH_DEBOUNCE_MS)
            isScrubbing = false
            onSeekDispatched(currentScrubPositionSeconds)
        }
        return true
    }

    companion object {
        /** Roughly one crown detent per 5-second step. */
        const val PIXELS_PER_STEP = 24f
        const val SECONDS_PER_STEP = 5L
        const val DISPATCH_DEBOUNCE_MS = 100L
    }
}

internal fun performRotaryHaptic(view: View) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        // Purpose-built for exactly this: a light tick per discrete increment.
        view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
    } else {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }
}

@Composable
fun rememberRotaryScrubState(
    positionSeconds: Long,
    durationSeconds: Long,
    onSeek: (Long) -> Unit,
): RotaryScrubState {
    val latestOnSeek by rememberUpdatedState(onSeek)
    val state = remember(durationSeconds) {
        RotaryScrubState(
            initialPositionSeconds = positionSeconds,
            durationSeconds = durationSeconds,
            onSeekDispatched = { latestOnSeek(it) },
        )
    }

    LaunchedEffect(positionSeconds) {
        if (!state.isScrubbing) {
            state.currentScrubPositionSeconds = positionSeconds
        }
    }

    return state
}

/**
 * Routes crown events to whichever target [mode] selects.
 *
 * Events that do not produce a step — and every event while [enabled] is false, i.e.
 * nothing is playing — fall through to [scrollState] so the crown still scrolls the
 * list instead of being swallowed by a scrubber with nothing to scrub.
 *
 * [scrollState] is null on the player, which after the arc-timeline redesign has
 * nothing to scroll: there, an unconsumed event is simply dropped rather than
 * silently nudging a surface the user cannot see move.
 */
@Composable
fun Modifier.rotaryControl(
    scrubState: RotaryScrubState,
    volumeState: RotaryVolumeState,
    mode: CrownMode,
    scrollState: ScrollState?,
    enabled: Boolean,
): Modifier {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val haptics = remember(view) { { performRotaryHaptic(view) } }

    return this.onRotaryScrollEvent { event ->
        val pixels = event.verticalScrollPixels
        val consumed = when {
            !enabled -> false
            mode == CrownMode.Volume -> volumeState.onRotaryDelta(pixels, haptics, scope)
            else -> scrubState.onRotaryDelta(pixels, haptics, scope)
        }
        if (!consumed && scrollState != null) {
            scope.launch { scrollState.scrollBy(pixels) }
        }
        true
    }
}
