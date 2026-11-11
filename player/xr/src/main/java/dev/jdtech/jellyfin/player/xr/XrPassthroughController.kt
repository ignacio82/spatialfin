package dev.jdtech.jellyfin.player.xr

import androidx.xr.runtime.Session
import androidx.xr.scenecore.scene
import kotlin.math.roundToInt
import timber.log.Timber

/**
 * Pure policy and SceneCore helper for graduated XR passthrough dimming (`[0.0f, 1.0f]`).
 *
 * - `0.0f`: Full blackout virtual environment (0% camera passthrough).
 * - `0.35f` / `0.70f`: Intermediate dimmed passthrough presets for peripheral room awareness.
 * - `1.0f`: Full camera passthrough (100% opacity).
 */
object XrPassthroughController {
    val STEPPED_PRESETS: List<Float> = listOf(0.0f, 0.35f, 0.70f, 1.0f)
    val POPOVER_PRESETS: List<Float> = listOf(0.0f, 0.25f, 0.50f, 0.75f, 1.0f)

    fun clampOpacity(opacity: Float): Float =
        if (opacity.isNaN()) 0.0f else opacity.coerceIn(0.0f, 1.0f)

    /**
     * Cycles through [STEPPED_PRESETS] (`0% -> 35% -> 70% -> 100% -> 0%`).
     */
    fun nextSteppedPreset(currentOpacity: Float): Float {
        val clamped = clampOpacity(currentOpacity)
        return when {
            clamped < 0.18f -> 0.35f
            clamped < 0.52f -> 0.70f
            clamped < 0.85f -> 1.0f
            else -> 0.0f
        }
    }

    /**
     * Applies [opacity] (`[0.0f, 1.0f]`) to the active XR [Session]'s `spatialEnvironment`.
     */
    fun applyOpacity(session: Session?, opacity: Float): Float {
        val clamped = clampOpacity(opacity)
        if (session == null) return clamped
        runCatching {
            session.scene.spatialEnvironment.preferredPassthroughOpacity = clamped
        }.onFailure { e ->
            Timber.w(e, "Failed to set preferredPassthroughOpacity=%.2f", clamped)
        }
        return clamped
    }

    fun isPassthroughActive(opacity: Float): Boolean = clampOpacity(opacity) > 0.01f

    fun formatPercentLabel(opacity: Float): String {
        val pct = (clampOpacity(opacity) * 100f).roundToInt()
        return if (pct == 0) "0% (Blackout)" else "$pct%"
    }

    fun formatShortBadge(opacity: Float): String {
        val pct = (clampOpacity(opacity) * 100f).roundToInt()
        return if (pct == 0) "Off" else "$pct%"
    }
}
