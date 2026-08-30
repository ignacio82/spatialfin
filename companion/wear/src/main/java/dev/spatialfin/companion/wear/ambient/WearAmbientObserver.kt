package dev.spatialfin.companion.wear.ambient

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf

/** True while the watch is in always-on (ambient) mode. */
val LocalAmbientMode = compositionLocalOf { false }

/**
 * Ambient state, driven by `AmbientLifecycleObserver` in `WearMainActivity`.
 *
 * In ambient mode the UI must drop smooth animations, stop rendering the poster, and
 * slow the scrubber to roughly 1 Hz — see the battery rules in docs/wear.md §5.
 */
class AmbientStateHolder {
    val isAmbient = mutableStateOf(false)
    val isBurnInProtection = mutableStateOf(false)
    val isLowBitAmbient = mutableStateOf(false)

    /** Bumped on each ambient tick so 1 Hz ambient consumers can recompose. */
    val ambientUpdateTick = mutableStateOf(0L)

    fun onEnterAmbient(burnInProtection: Boolean, lowBitAmbient: Boolean) {
        isBurnInProtection.value = burnInProtection
        isLowBitAmbient.value = lowBitAmbient
        isAmbient.value = true
    }

    fun onExitAmbient() {
        isAmbient.value = false
    }

    fun onUpdateAmbient() {
        ambientUpdateTick.value = ambientUpdateTick.value + 1
    }
}
