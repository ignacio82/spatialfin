package dev.spatialfin.unified

import android.os.Handler
import android.os.Looper
import androidx.xr.runtime.Session
import androidx.xr.scenecore.SpatialCapability
import androidx.xr.scenecore.scene
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/** The two XR browsing/playback modes supported by SpatialFin. */
enum class XrSpaceMode { HOME, FULL }

/**
 * Runtime state exposed to Compose.
 *
 * [transitioning] flips true briefly around a mode switch so the UI can fade the
 * main surface instead of popping straight into the new layout. The controller
 * clears it on a short timer; Compose is expected to animate alpha when it
 * changes.
 */
data class XrSpaceUiState(
    val mode: XrSpaceMode = XrSpaceMode.HOME,
    val spatialUiAvailable: Boolean = false,
    val transitioning: Boolean = false,
)

/**
 * Owns XR space-mode transitions for [UnifiedMainActivity].
 *
 * Wraps [Session] so callers never call requestFullSpaceMode / requestHomeSpaceMode directly.
 * All transitions are guarded by a capability check so the app never crashes when spatial
 * features are unavailable.
 */
class XrSpaceController(
    private val session: Session,
    private val appPreferences: AppPreferences,
) {
    private val _uiState = MutableStateFlow(
        XrSpaceUiState(spatialUiAvailable = checkSpatialCapability())
    )
    val uiState: StateFlow<XrSpaceUiState> = _uiState.asStateFlow()

    val currentMode: XrSpaceMode get() = _uiState.value.mode

    /** Read the launch preference and enter Full Space if configured and capable. */
    fun applyLaunchPreference() {
        val launchMode = appPreferences.getValue(appPreferences.xrLaunchMode)
        val lastUsed = appPreferences.getValue(appPreferences.xrLastUsedMode)
        val wantFull = when (launchMode) {
            "full" -> true
            "last_used" -> lastUsed == "full"
            else -> false // "home" is the default
        }
        if (wantFull) enterFullSpace()
        // HOME is the default; no explicit request needed.
    }

    fun enterHomeSpace() {
        // Flip the mode atomically together with the transitioning flag so the
        // outgoing composable (FullSpaceContent) disposes immediately. Fading the
        // old tree while the Subspace is already tearing down raced the XR
        // SpatialPanel node teardown on device. Only the incoming HomeSpaceContent
        // animates alpha from 0 → 1.
        runCatching { session.scene.requestHomeSpaceMode() }
            .onFailure { Timber.w(it, "XrSpaceController: requestHomeSpaceMode failed") }
        _uiState.value = _uiState.value.copy(mode = XrSpaceMode.HOME, transitioning = true)
        appPreferences.setValue(appPreferences.xrLastUsedMode, "home")
        scheduleEndTransition()
    }

    fun enterFullSpace() {
        // Don't gate on SPATIAL_3D_CONTENT here — that capability only becomes true *after*
        // the app is already in Full Space, so checking it before requesting the transition
        // would always block the user-initiated switch.  Let the system decide; if the device
        // cannot honor it, requestFullSpaceMode() will throw and we log + stay in HOME.
        runCatching { session.scene.requestFullSpaceMode() }
            .onSuccess {
                _uiState.value = _uiState.value.copy(
                    mode = XrSpaceMode.FULL,
                    spatialUiAvailable = true,
                    transitioning = true,
                )
                appPreferences.setValue(appPreferences.xrLastUsedMode, "full")
                scheduleEndTransition()
            }
            .onFailure {
                Timber.w(it, "XrSpaceController: requestFullSpaceMode failed — staying in Home Space")
            }
    }

    private fun scheduleEndTransition() {
        transitionHandler.removeCallbacksAndMessages(null)
        transitionHandler.postDelayed(
            { _uiState.value = _uiState.value.copy(transitioning = false) },
            TRANSITION_FADE_MS,
        )
    }

    fun toggleSpace() = when (currentMode) {
        XrSpaceMode.HOME -> enterFullSpace()
        XrSpaceMode.FULL -> enterHomeSpace()
    }

    /** Re-check hardware capability (e.g. after a configuration change). */
    fun refreshCapabilities() {
        _uiState.value = _uiState.value.copy(spatialUiAvailable = checkSpatialCapability())
    }

    private fun checkSpatialCapability(): Boolean = runCatching {
        session.scene.spatialCapabilities.contains(SpatialCapability.SPATIAL_3D_CONTENT)
    }.getOrDefault(false)

    private val transitionHandler = Handler(Looper.getMainLooper())

    companion object {
        /** How long the fade overlay stays on-screen after a mode switch. */
        const val TRANSITION_FADE_MS = 320L
    }
}
