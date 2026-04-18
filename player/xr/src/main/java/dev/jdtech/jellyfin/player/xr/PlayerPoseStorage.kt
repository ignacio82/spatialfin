package dev.jdtech.jellyfin.player.xr

import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel

/**
 * Persistence and comparison helpers for the player's XR video-root pose.
 *
 * Extracted from SpatialPlayerScreen.kt so the god-file shrinks without changing
 * behavior — everything in this file was previously file-private in that screen.
 */

internal const val XR_PLAYER_POSE_VERSION_VIDEO_CENTER = 1

// Restore guardrails — a persisted pose outside these bounds is treated as stale
// (e.g. the user removed the headset while the panel was at an awkward angle) and
// the default cinema-centered pose is used instead.
internal const val MIN_RESTORABLE_VIDEO_DEPTH_METERS = 3.5f
internal const val MAX_RESTORABLE_VIDEO_YAW_DEGREES = 18f
internal const val MAX_RESTORABLE_VIDEO_PITCH_DEGREES = 14f

internal fun loadSavedPlayerRootPose(viewModel: PlayerViewModel): Pose {
    val defaultPose = Pose(Vector3(0f, 0f, -VIDEO_DEPTH_METERS), Quaternion.Identity)
    // Persisted XR placement has repeatedly restored uncomfortable launch poses. Always
    // spawn the player at the centered cinema baseline, while still allowing movement during
    // the current session.
    savePlayerRootPose(viewModel, defaultPose)
    savePlayerRootScale(viewModel, DEFAULT_VIDEO_PANEL_SCALE)
    return defaultPose
}

internal fun loadSavedPlayerRootScale(viewModel: PlayerViewModel): Float {
    val prefs = viewModel.appPreferences
    return prefs.getValue(prefs.xrPlayerPanelScale).coerceIn(0.2f, 5.0f)
}

internal fun savePlayerRootPose(viewModel: PlayerViewModel, pose: Pose) {
    val prefs = viewModel.appPreferences
    val translation = pose.translation
    val rotation = pose.rotation
    prefs.setValue(prefs.xrPlayerPanelX, translation.x)
    prefs.setValue(prefs.xrPlayerPanelY, translation.y)
    prefs.setValue(prefs.xrPlayerPanelZ, translation.z)
    prefs.setValue(prefs.xrPlayerPanelRotX, rotation.x)
    prefs.setValue(prefs.xrPlayerPanelRotY, rotation.y)
    prefs.setValue(prefs.xrPlayerPanelRotZ, rotation.z)
    prefs.setValue(prefs.xrPlayerPanelRotW, rotation.w)
    prefs.setValue(prefs.xrPlayerPanelPoseVersion, XR_PLAYER_POSE_VERSION_VIDEO_CENTER)
}

internal fun savePlayerRootScale(viewModel: PlayerViewModel, scale: Float) {
    val prefs = viewModel.appPreferences
    prefs.setValue(prefs.xrPlayerPanelScale, scale.coerceIn(0.2f, 5.0f))
}

internal fun posesApproximatelyEqual(a: Pose, b: Pose?): Boolean {
    if (b == null) return false
    val epsPos = 1e-4f
    val epsRot = 1e-4f
    val ta = a.translation
    val tb = b.translation
    if (kotlin.math.abs(ta.x - tb.x) > epsPos) return false
    if (kotlin.math.abs(ta.y - tb.y) > epsPos) return false
    if (kotlin.math.abs(ta.z - tb.z) > epsPos) return false
    val ra = a.rotation
    val rb = b.rotation
    if (kotlin.math.abs(ra.x - rb.x) > epsRot) return false
    if (kotlin.math.abs(ra.y - rb.y) > epsRot) return false
    if (kotlin.math.abs(ra.z - rb.z) > epsRot) return false
    if (kotlin.math.abs(ra.w - rb.w) > epsRot) return false
    return true
}

internal fun isRestorableVideoPose(pose: Pose): Boolean {
    val translation = pose.translation
    val distance =
        kotlin.math.sqrt(
            translation.x * translation.x +
                translation.y * translation.y +
                translation.z * translation.z,
        )
    if (distance <= MIN_RESTORABLE_VIDEO_DEPTH_METERS || translation.z >= -0.5f) {
        return false
    }
    val yawDegrees =
        Math.toDegrees(kotlin.math.atan2(kotlin.math.abs(translation.x), kotlin.math.abs(translation.z)).toDouble())
            .toFloat()
    val pitchDegrees =
        Math.toDegrees(kotlin.math.atan2(kotlin.math.abs(translation.y), kotlin.math.abs(translation.z)).toDouble())
            .toFloat()
    return yawDegrees <= MAX_RESTORABLE_VIDEO_YAW_DEGREES &&
        pitchDegrees <= MAX_RESTORABLE_VIDEO_PITCH_DEGREES
}
