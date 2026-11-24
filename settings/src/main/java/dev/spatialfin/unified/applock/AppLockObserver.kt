package dev.spatialfin.unified.applock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Narrow read-only observation seam for App Lock state so modules outside `:app:unified`
 * (`:core`, `:data`, `:player:local`, `:modes:audio`, `:modes:music`, `:fcast:session-ui`)
 * can gate background work, playback, and receivers without depending on mutating
 * [AppLockManager] APIs or instantiating AndroidKeyStore KEKs inside `:xrplayer`.
 */
interface AppLockObserver {
    val lockState: StateFlow<AppLockManager.LockState>
    val enrollmentVersion: StateFlow<Long>

    object NoOp : AppLockObserver {
        private val _lockState = MutableStateFlow(AppLockManager.LockState.DISABLED)
        private val _enrollmentVersion = MutableStateFlow(0L)

        override val lockState: StateFlow<AppLockManager.LockState> = _lockState.asStateFlow()
        override val enrollmentVersion: StateFlow<Long> = _enrollmentVersion.asStateFlow()
    }
}
