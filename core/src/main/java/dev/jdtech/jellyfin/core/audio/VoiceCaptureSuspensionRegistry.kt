package dev.jdtech.jellyfin.core.audio

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Process-wide microphone arbitration registry for Theater Audio calibration sweeps.
 *
 * While any [CalibrationMicLease] is held (e.g. during a Tier 2 UMIK-1 sweep), active voice
 * services (`SpatialVoiceService`, `BeamVoiceService`, `HomeVoiceController`) observe
 * [isSuspended] to cancel in-flight recognition and suppress restarts until the lease closes.
 */
object VoiceCaptureSuspensionRegistry {

    class CalibrationMicLease internal constructor(
        val reason: String,
        private val onRelease: (CalibrationMicLease) -> Unit,
    ) : AutoCloseable {
        private val released = AtomicBoolean(false)

        fun release() {
            if (released.compareAndSet(false, true)) {
                onRelease(this)
            }
        }

        override fun close() = release()
    }

    private val activeLeases = CopyOnWriteArraySet<CalibrationMicLease>()

    private val _isSuspended = MutableStateFlow(false)
    val isSuspended: StateFlow<Boolean> = _isSuspended.asStateFlow()

    fun acquire(reason: String): CalibrationMicLease {
        val lease = CalibrationMicLease(reason = reason) { released ->
            activeLeases.remove(released)
            val stillSuspended = activeLeases.isNotEmpty()
            _isSuspended.value = stillSuspended
            Timber.i(
                "VoiceCaptureSuspensionRegistry: released lease reason=%s activeCount=%d",
                released.reason,
                activeLeases.size,
            )
        }
        activeLeases.add(lease)
        _isSuspended.value = true
        Timber.i(
            "VoiceCaptureSuspensionRegistry: acquired lease reason=%s activeCount=%d",
            reason,
            activeLeases.size,
        )
        return lease
    }
}
