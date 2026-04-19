package dev.jdtech.jellyfin.presentation.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/**
 * Tracks a short "I saw your tap, hold on" state for controls that trigger
 * slow work (activity launches, navigation into heavy screens). Flip it to
 * true from the control's onClick to show a spinner/label; it auto-clears on
 * [Lifecycle.Event.ON_RESUME] (user returned from the launched destination)
 * or after [timeoutMs] as a safety net if the tap never actually navigated.
 */
class TapPendingState internal constructor(
    private val setter: (Boolean) -> Unit,
    private val getter: () -> Boolean,
) {
    val value: Boolean get() = getter()
    fun begin() { setter(true) }
}

@Composable
fun rememberTapPending(timeoutMs: Long = 3_000L): TapPendingState {
    var pending by remember { mutableStateOf(false) }

    LaunchedEffect(pending) {
        if (pending) {
            delay(timeoutMs)
            pending = false
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) pending = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    return remember { TapPendingState(setter = { pending = it }, getter = { pending }) }
}
