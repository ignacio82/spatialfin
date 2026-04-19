package dev.spatialfin.unified

import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.GroupEntity
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Persists the user-placed pose of the main XR app panel and provides a tracking
 * loop that mirrors the live entity pose to preferences without thrashing flash
 * storage.
 *
 * The class deliberately splits "what to do" (this controller) from "what's a
 * meaningful pose change / a legacy default that needs migrating" (the pure
 * [PanelPosePolicy] object) so the policy layer is testable on the JVM.
 */
class PanelPoseController(
    private val appPreferences: AppPreferences,
) {
    /**
     * Always spawn the panel at the centered default — the Full Space session
     * origin shifts on every Home→Full transition, so a persisted world pose
     * from a previous session lands on the user's face or behind them. The
     * MovableComponent still lets the user reposition during the current
     * session; we just don't restore across transitions. Mirrors the
     * player's loadSavedPlayerRootPose for the same reason.
     */
    fun loadPose(): Pose {
        val defaultPose = Pose(
            Vector3(0f, 0f, PanelPosePolicy.DEFAULT_DEPTH_METERS),
            Quaternion.Identity,
        )
        savePose(defaultPose)
        return defaultPose
    }

    /** Persist the entity pose into preferences and stamp the current version. */
    fun savePose(pose: Pose) {
        val translation = pose.translation
        val rotation = pose.rotation
        appPreferences.setValue(appPreferences.xrAppPanelX, translation.x)
        appPreferences.setValue(appPreferences.xrAppPanelY, translation.y)
        appPreferences.setValue(appPreferences.xrAppPanelZ, translation.z)
        appPreferences.setValue(appPreferences.xrAppPanelRotX, rotation.x)
        appPreferences.setValue(appPreferences.xrAppPanelRotY, rotation.y)
        appPreferences.setValue(appPreferences.xrAppPanelRotZ, rotation.z)
        appPreferences.setValue(appPreferences.xrAppPanelRotW, rotation.w)
        appPreferences.setValue(
            appPreferences.xrAppPanelPoseVersion,
            PanelPosePolicy.CURRENT_POSE_VERSION,
        )
    }

    /** Read the entity's current pose, swallowing transient runtime errors. */
    fun readEntityPose(entity: GroupEntity): Pose? {
        return runCatching { entity.getPose() }.getOrNull()
    }

    /**
     * Long-running pose tracking loop. Polls quickly while the user is moving the
     * panel, but only persists the pose after motion settles — writing on every
     * tick raced the MovableComponent's final pose with a stale intermediate
     * sample, so the user's resting position could be overwritten.
     */
    suspend fun trackEntityPose(entity: GroupEntity) {
        var lastSampled: Pose? = null
        var lastSaved: Pose? = null
        var stillSinceMs: Long = 0L
        while (true) {
            val current = readEntityPose(entity)
            if (current != null) {
                if (!PanelPosePolicy.approximatelyEqual(current, lastSampled)) {
                    lastSampled = current
                    stillSinceMs = System.currentTimeMillis()
                } else if (
                    !PanelPosePolicy.approximatelyEqual(current, lastSaved) &&
                    System.currentTimeMillis() - stillSinceMs >= POSE_STILL_DEBOUNCE_MS
                ) {
                    savePose(current)
                    lastSaved = current
                }
            }
            delay(POSE_SAMPLE_INTERVAL_MS)
        }
    }

    companion object {
        const val POSE_SAMPLE_INTERVAL_MS = 100L
        const val POSE_STILL_DEBOUNCE_MS = 300L
    }
}

/**
 * Pure helpers for pose persistence — no Android / XR runtime calls so the logic
 * is unit-testable.
 */
object PanelPosePolicy {
    /** Default panel depth (meters in front of the user) for fresh installs. */
    const val DEFAULT_DEPTH_METERS = -1.75f

    /**
     * Bumped whenever the on-disk pose layout changes (typically because the
     * default position moved). On read, anything below this version triggers
     * [migrateLegacyDefault].
     */
    const val CURRENT_POSE_VERSION = 5

    private const val POSE_EPSILON = 0.05f
    private const val APPROX_EPSILON = 1e-4f

    private val legacyDefaultDepths = floatArrayOf(-5f, -6f, -9f, -11f)

    /**
     * If [pose] looks like one of the previously-shipped centered default poses,
     * rewrite it to today's default. Otherwise return [pose] unchanged so a user
     * who actually moved the panel keeps their placement.
     */
    fun migrateLegacyDefault(pose: Pose): Pose {
        val translation = pose.translation
        val rotation = pose.rotation
        val usesLegacyDefaultPosition = abs(translation.x) <= POSE_EPSILON &&
            abs(translation.y) <= POSE_EPSILON &&
            legacyDefaultDepths.any { abs(translation.z - it) <= POSE_EPSILON }
        val usesIdentityRotation = abs(rotation.x) <= POSE_EPSILON &&
            abs(rotation.y) <= POSE_EPSILON &&
            abs(rotation.z) <= POSE_EPSILON &&
            abs(rotation.w - 1f) <= POSE_EPSILON
        return if (usesLegacyDefaultPosition && usesIdentityRotation) {
            Pose(Vector3(0f, 0f, DEFAULT_DEPTH_METERS), Quaternion.Identity)
        } else {
            pose
        }
    }

    /**
     * True when [a] and [b] are within [APPROX_EPSILON] on every component.
     * Used by the tracking loop to decide whether a pose change is worth a
     * preferences write.
     */
    fun approximatelyEqual(a: Pose, b: Pose?): Boolean {
        if (b == null) return false
        val ta = a.translation
        val tb = b.translation
        if (abs(ta.x - tb.x) > APPROX_EPSILON) return false
        if (abs(ta.y - tb.y) > APPROX_EPSILON) return false
        if (abs(ta.z - tb.z) > APPROX_EPSILON) return false
        val ra = a.rotation
        val rb = b.rotation
        if (abs(ra.x - rb.x) > APPROX_EPSILON) return false
        if (abs(ra.y - rb.y) > APPROX_EPSILON) return false
        if (abs(ra.z - rb.z) > APPROX_EPSILON) return false
        if (abs(ra.w - rb.w) > APPROX_EPSILON) return false
        return true
    }
}
