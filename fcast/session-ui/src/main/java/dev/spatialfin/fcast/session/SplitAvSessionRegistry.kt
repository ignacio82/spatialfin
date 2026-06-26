package dev.spatialfin.fcast.session

import dev.jdtech.jellyfin.cast.CastReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide read-side handle to the currently active split-A/V session's target. Written by
 * [SplitAvController] on start/stop; read by collaborators that don't have a direct Hilt
 * dependency on the controller (notably [SplitAvBridgeService], which runs as a plain Android
 * `Service`).
 *
 * Used solely for capability checks — the cross-process IPC bridge refuses to register a video
 * master proxy when no SplitAv-capable session is active. Routing audio control still goes
 * through the controller directly.
 */
object SplitAvSessionRegistry {

    private val _activeReceiver = MutableStateFlow<CastReceiver?>(null)
    val activeReceiver: StateFlow<CastReceiver?> = _activeReceiver.asStateFlow()

    internal fun setActive(receiver: CastReceiver?) {
        _activeReceiver.value = receiver
    }
}
