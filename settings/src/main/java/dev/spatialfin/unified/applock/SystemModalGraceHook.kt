package dev.spatialfin.unified.applock

import androidx.compose.runtime.staticCompositionLocalOf
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Seam for bracketing main-process system modals (e.g., BiometricPrompt / ConfirmDeviceCredential,
 * runtime permission dialogs) so App Lock background detection grants a bounded interactive grace
 * window while an OS modal temporarily pauses our Activity.
 */
fun interface SystemModalGraceHook {
    fun setSystemModalActive(active: Boolean)

    companion object {
        val NoOp = SystemModalGraceHook {}
    }
}

/**
 * Leaf zero-dependency singleton implementation of [SystemModalGraceHook].
 * Both [AppLockManager] and `XrPlayerSessionCoordinator` can inject this independently
 * without creating a Hilt singleton cycle.
 */
@Singleton
class SystemModalGraceState @Inject constructor() : SystemModalGraceHook {
    private val counter = AtomicInteger(0)
    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    override fun setSystemModalActive(active: Boolean) {
        val next =
            if (active) {
                counter.incrementAndGet()
            } else {
                counter.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
            }
        _activeCount.value = next
    }
}

val LocalSystemModalGraceHook =
    staticCompositionLocalOf<SystemModalGraceHook> { SystemModalGraceHook.NoOp }
