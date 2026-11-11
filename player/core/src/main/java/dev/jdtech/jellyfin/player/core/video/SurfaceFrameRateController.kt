package dev.jdtech.jellyfin.player.core.video

import android.os.Build
import android.view.Surface
import android.view.Window
import kotlin.math.abs
import timber.log.Timber

/**
 * Coordinates display refresh-rate matching (`Surface.setFrameRate` and optional
 * `WindowManager.LayoutParams.preferredRefreshRate`) across Android TV, Beam phone/tablet
 * (including secondary DisplayPort `Presentation` displays), and Android XR players.
 */
object SurfaceFrameRateController {

    const val MIN_VALID_FRAME_RATE = 10f
    const val MAX_VALID_FRAME_RATE = 240f
    private const val FRAME_RATE_EPSILON = 0.005f

    private data class AppliedState(
        val surfaceIdentity: Int,
        val windowIdentity: Int,
        val frameRate: Float,
        val allowNonSeamlessSwitch: Boolean,
    )

    @Volatile
    private var lastAppliedState: AppliedState? = null

    /**
     * Returns [rawFrameRate] when it is finite and within [MIN_VALID_FRAME_RATE]..[MAX_VALID_FRAME_RATE],
     * or `null` when the stream omits or reports an invalid frame rate.
     */
    fun normalizeVideoFrameRate(rawFrameRate: Float): Float? =
        if (rawFrameRate.isFinite() && rawFrameRate in MIN_VALID_FRAME_RATE..MAX_VALID_FRAME_RATE) {
            rawFrameRate
        } else {
            null
        }

    /**
     * Maps [allowNonSeamlessSwitch] to the corresponding [Surface] frame-rate change strategy.
     */
    fun resolveChangeStrategy(allowNonSeamlessSwitch: Boolean): Int =
        if (allowNonSeamlessSwitch) {
            Surface.CHANGE_FRAME_RATE_ALWAYS
        } else {
            Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
        }

    /**
     * Applies [frameRate] to [surface] (and optionally [window] when [allowNonSeamlessSwitch] is true).
     * When [frameRate] is invalid or `<= 0f`, resets any previously applied frame rate via [clearFrameRate].
     */
    fun applyFrameRate(
        surface: Surface?,
        window: Window?,
        frameRate: Float,
        allowNonSeamlessSwitch: Boolean = false,
    ) {
        val normalized = normalizeVideoFrameRate(frameRate)
        if (normalized == null) {
            clearFrameRate(surface, window)
            return
        }

        val surfaceValid = runCatching { surface?.isValid == true }.getOrDefault(false)
        if (!surfaceValid && window == null) return

        val surfaceId = if (surfaceValid) System.identityHashCode(surface) else 0
        val windowId = System.identityHashCode(window)
        val previous = lastAppliedState
        if (
            previous != null &&
            previous.surfaceIdentity == surfaceId &&
            previous.windowIdentity == windowId &&
            abs(previous.frameRate - normalized) < FRAME_RATE_EPSILON &&
            previous.allowNonSeamlessSwitch == allowNonSeamlessSwitch
        ) {
            return
        }

        val strategy = resolveChangeStrategy(allowNonSeamlessSwitch)
        if (surface != null && surfaceValid && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                surface.setFrameRate(
                    normalized,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    strategy,
                )
                Timber.i("frame-rate: applied %.3f fps strategy=%d", normalized, strategy)
            }.onFailure { error ->
                Timber.w(error, "frame-rate: failed to set surface frame rate")
            }
        }

        if (window != null) {
            runCatching {
                val attrs = window.attributes
                val targetPreferred = if (allowNonSeamlessSwitch) normalized else 0f
                if (abs(attrs.preferredRefreshRate - targetPreferred) >= FRAME_RATE_EPSILON) {
                    attrs.preferredRefreshRate = targetPreferred
                    window.attributes = attrs
                }
            }.onFailure { error ->
                Timber.w(error, "frame-rate: failed to update window preferredRefreshRate")
            }
        }

        lastAppliedState = AppliedState(
            surfaceIdentity = surfaceId,
            windowIdentity = windowId,
            frameRate = normalized,
            allowNonSeamlessSwitch = allowNonSeamlessSwitch,
        )
    }

    /**
     * Resets the frame rate hint on [surface] and [window] back to the system default (`0f`).
     */
    fun clearFrameRate(surface: Surface?, window: Window?) {
        val surfaceId = System.identityHashCode(surface)
        val windowId = System.identityHashCode(window)
        val previous = lastAppliedState
        if (previous != null && (previous.surfaceIdentity == surfaceId || previous.windowIdentity == windowId)) {
            lastAppliedState = null
        }

        val surfaceValid = runCatching { surface?.isValid == true }.getOrDefault(false)
        if (surface != null && surfaceValid && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                surface.setFrameRate(
                    0f,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
                )
            }.onFailure { error ->
                Timber.w(error, "frame-rate: failed to clear surface frame rate")
            }
        }

        if (window != null) {
            runCatching {
                val attrs = window.attributes
                if (attrs.preferredRefreshRate != 0f) {
                    attrs.preferredRefreshRate = 0f
                    window.attributes = attrs
                }
            }.onFailure { error ->
                Timber.w(error, "frame-rate: failed to clear window preferredRefreshRate")
            }
        }
    }
}
