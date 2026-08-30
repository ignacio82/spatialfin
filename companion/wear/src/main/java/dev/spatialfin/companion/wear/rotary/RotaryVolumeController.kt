package dev.spatialfin.companion.wear.rotary

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Crown-driven volume, on the same accumulate-and-debounce contract as the scrubber. */
class RotaryVolumeState(
    initialVolume: Float,
    private val onVolumeDispatched: (Float) -> Unit,
) {
    var currentVolume by mutableFloatStateOf(initialVolume)
    var isAdjusting by mutableStateOf(false)

    private var accumulatedPixels = 0f
    private var debounceJob: Job? = null

    /** @return true when the delta produced at least one volume step and was consumed. */
    fun onRotaryDelta(pixels: Float, onHapticTick: () -> Unit, scope: CoroutineScope): Boolean {
        accumulatedPixels += pixels
        val steps = (accumulatedPixels / PIXELS_PER_STEP).toInt()
        if (steps == 0) return false

        accumulatedPixels -= steps * PIXELS_PER_STEP
        currentVolume = (currentVolume + steps * VOLUME_DELTA_PER_STEP).coerceIn(0f, 1f)
        isAdjusting = true

        onHapticTick()

        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(RotaryScrubState.DISPATCH_DEBOUNCE_MS)
            isAdjusting = false
            onVolumeDispatched(currentVolume)
        }
        return true
    }

    companion object {
        const val PIXELS_PER_STEP = 20f
        const val VOLUME_DELTA_PER_STEP = 0.05f
    }
}

@Composable
fun rememberRotaryVolumeState(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
): RotaryVolumeState {
    val latestOnChange by rememberUpdatedState(onVolumeChange)
    val state = remember {
        RotaryVolumeState(
            initialVolume = volume,
            onVolumeDispatched = { latestOnChange(it) },
        )
    }

    LaunchedEffect(volume) {
        if (!state.isAdjusting) {
            state.currentVolume = volume
        }
    }

    return state
}
